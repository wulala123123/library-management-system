"""Versioned, targeted correction of Huiben's explicit unlimited-use totals.

Run on the Docker host; the default is a SELECT-only private JSON preview.
Apply requires the reviewed preview SHA256 and source SHA256. Production also
requires a verified backup and stopped frontend/backend. Production uses
library_migrator and MYSQL_MIGRATION_PASSWORD from the container environment.
No password column is selected. Only member_profile.total_uses is persistently written.

To restore, preview --restore-from a successful forward result, review that new
preview, then apply it. Only rows actually changed by that result are reversed;
their expected current total is protected. Other/new business is retained.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from apply_huiben_migration import Database as BaseDatabase, compact, literal, verify_backup
from prepare_huiben_migration import load, number, parse_total_uses, query_id, read_xlsx, text, unique


VERSION = "huiben-unlimited-uses-v2"
BATCH_KEY = "huiben-20261015"
SENSITIVE_COLUMNS = frozenset(("password", "password_hash", "password_salt", "token",
    "access_token", "refresh_token", "secret", "secret_key", "private_key", "credential"))
META_FIELDS = ("table_name", "column_name", "column_type", "is_nullable", "column_key", "extra", "ordinal_position", "engine")
META_FROM = (" FROM information_schema.COLUMNS c JOIN information_schema.TABLES t"
             " ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME"
             " WHERE c.TABLE_SCHEMA=DATABASE() AND t.TABLE_TYPE='BASE TABLE'")
META_EXPRESSIONS = ("c.TABLE_NAME", "c.COLUMN_NAME", "c.COLUMN_TYPE", "c.IS_NULLABLE", "c.COLUMN_KEY", "c.EXTRA",
                    "CAST(c.ORDINAL_POSITION AS CHAR)", "t.ENGINE")


class CorrectionDatabase(BaseDatabase):
    """Use the existing maintenance account, retaining constrained trial mode."""
    def run(self, sql, xml=False):
        if self.schema: return super().run(sql, xml)
        flags = " --xml" if xml else " --batch --skip-column-names"
        # Expansion happens inside the container. No host-side credential reads,
        # password arguments, root fallback, or production grant changes.
        command = ('MYSQL_PWD="${MYSQL_MIGRATION_PASSWORD:?Missing migration credential}" '
                   'exec mysql -u library_migrator --default-character-set=utf8mb4' + flags
                   + ' -D "${MYSQL_DATABASE:?Missing database name}"')
        result = subprocess.run(["docker", "exec", "-i", self.container, "sh", "-c", command],
                                input=sql, text=True, capture_output=True)
        if result.returncode: raise RuntimeError("Database operation failed: " + result.stderr[:300])
        return result.stdout


def require_maintenance_privileges(db):
    # information_schema.TRIGGERS hides triggers from users lacking TRIGGER.
    # Establish visibility before interpreting an empty trigger result. Schema
    # grant names may contain escaped underscores, matching MySQL grant patterns.
    grantee = "CONCAT(CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',1),CHAR(39),'@',CHAR(39),SUBSTRING_INDEX(CURRENT_USER(),'@',-1),CHAR(39))"
    privileges = ("SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=" + grantee
        + " UNION SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=" + grantee
        + " AND BINARY DATABASE() LIKE BINARY TABLE_SCHEMA")
    rows = db.rows("SELECT COUNT(DISTINCT PRIVILEGE_TYPE) AS total FROM (" + privileges
        + ") hb_privileges WHERE PRIVILEGE_TYPE IN ('CREATE TEMPORARY TABLES','TRIGGER')")
    if rows != [{"total": "2"}]:
        raise ValueError("Correction requires CREATE TEMPORARY TABLES and TRIGGER privileges; use the maintenance connection")


def sha256(data): return hashlib.sha256(data).hexdigest()


def identifier(name):
    if not re.fullmatch(r"[A-Za-z0-9_]+", name): raise ValueError("Unsupported database identifier")
    return "`" + name + "`"


def private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


def source_evidence(root, batch_key=BATCH_KEY):
    """Rehash the immutable source and reconstruct only the original member archive."""
    folder = root / "source"
    files = sorted(p for p in folder.iterdir() if p.is_file())
    if any(p.is_symlink() for p in files): raise ValueError("Source symlinks are not supported")
    manifest = {p.name: sha256(p.read_bytes()) for p in files}
    source_hash = sha256(json.dumps(manifest, sort_keys=True).encode())
    recorded = load(root / "source-manifest.json")
    if recorded != {"sha256": source_hash, "files": manifest}: raise ValueError("Source manifest changed")
    exported = read_xlsx(folder / "members.xlsx")
    by_pair = unique(exported, lambda m: (text(m["会员名字"]), text(m["手机号"])), "source member")
    details = unique(load(folder / "member-details.json"), lambda m: query_id(m["link"]), "member detail")
    seen, candidates = set(), []
    pages = load(folder / "members-page.json")
    for page in pages:
        uid = query_id(next(l["href"] for l in page["links"] if l["href"].startswith("/users/exportuserbook")))
        if not re.fullmatch(r"[0-9]+", uid) or uid in seen: raise ValueError("Invalid or duplicate source member UID")
        seen.add(uid)
        name = next(l["text"] for l in page["links"] if l["href"].startswith("/users/read?"))
        phone = next(l["text"] for l in page["links"] if l["href"].startswith("/users/exportuserbook"))
        row = by_pair.get((name.strip(), phone.strip()))
        if row is None or uid not in details: raise ValueError("Source member export/page/detail association differs")
        total = parse_total_uses(row["总次数"], page["cells"][7])
        # Preserve the old import's exact semantics to identify this particular bug.
        previous = None if "总: 不限" in page["cells"][7] else number(row["总次数"])
        if total is not None or previous is None: continue
        if previous != 0: raise ValueError("Unexpected original total; manual review required")
        archived = {**row, "缴费状态": page["cells"][2], "押金缴纳状态": page["cells"][3], "原系统补充资料": details[uid]["content"]}
        candidates.append({"source_member_id": uid, "source_key": "huiben:" + uid,
            "expected_before": 0, "desired_after": None, "details_sha256": sha256(compact(archived).encode())})
    if len(seen) != len(exported) or seen != set(details): raise ValueError("Source member coverage differs")
    return {"source_hash": source_hash, "batch_key": batch_key, "candidates": sorted(candidates, key=lambda c: c["source_member_id"])}


def schema_columns(db):
    require_maintenance_privileges(db)
    select = ",".join(expr + " AS " + name for expr, name in zip(META_EXPRESSIONS, META_FIELDS))
    columns = db.rows("SELECT " + select + META_FROM + " ORDER BY BINARY c.TABLE_NAME,c.ORDINAL_POSITION")
    # mysql --xml represents both SQL empty strings and empty elements without
    # field.text; these metadata columns are defined as non-NULL empty strings.
    for column in columns:
        for key in ("column_key", "extra"):
            if column[key] is None: column[key] = ""
    if not columns or any(c["engine"] != "InnoDB" for c in columns): raise ValueError("Expected transactional InnoDB tables")
    for c in columns:
        identifier(c["table_name"]); identifier(c["column_name"])
    if db.rows("SELECT COUNT(*) AS total FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='member_profile'")[0]["total"] != "0":
        raise ValueError("Review member_profile triggers before correction")
    return columns


def schema_hash(columns):
    return sha256("|".join(":".join(str(c[k]).encode().hex().upper() for k in META_FIELDS) for c in columns).encode())


def schema_hash_sql():
    fields = ",".join("HEX(" + expr + ")" for expr in META_EXPRESSIONS)
    return "(SELECT SHA2(GROUP_CONCAT(CONCAT_WS(':'," + fields + ") ORDER BY BINARY c.TABLE_NAME,c.ORDINAL_POSITION SEPARATOR '|'),256)" + META_FROM + ")"


def tables_from(columns):
    tables = {}
    for column in columns: tables.setdefault(column["table_name"], []).append(column)
    for name, fields in tables.items():
        if not any(c["column_key"] == "PRI" for c in fields): raise ValueError("Every protected table needs a primary key")
    if not {"member_profile", "legacy_record", "migration_batch", "inventory_mutex"} <= tables.keys():
        raise ValueError("Expected the deployed Huiben migration schema")
    return tables


def row_hash(fields, stable=False):
    selected = [identifier(c["column_name"]) for c in fields if c["column_name"].lower() not in SENSITIVE_COLUMNS
                and not (stable and c["table_name"] == "member_profile" and c["column_name"] == "total_uses")]
    return "SHA2(CAST(JSON_ARRAY(" + ",".join(selected) + ") AS CHAR CHARACTER SET utf8mb4),256)"


def fingerprints(db, columns):
    selects = []
    for table, fields in tables_from(columns).items():
        primary = ",".join(identifier(c["column_name"]) for c in fields if c["column_key"] == "PRI")
        selects.append("SELECT " + literal(table) + " AS table_name,CAST(JSON_ARRAY(" + primary + ") AS CHAR CHARACTER SET utf8mb4) AS row_key,"
                       + row_hash(fields) + " AS digest," + row_hash(fields, True) + " AS stable_digest FROM " + identifier(table))
    return db.rows(" UNION ALL ".join(selects) + " ORDER BY table_name,row_key")


def total_value(value):
    if value is None: return None
    if not re.fullmatch(r"[0-9]+", str(value)): raise ValueError("Unexpected target total-use value")
    return int(value)


def restore_targets(result, source):
    if result.get("tool_version") != VERSION or not result.get("applied") or result.get("direction") != "forward":
        raise ValueError("Restore needs a successful forward correction result")
    if result.get("source_hash") != source["source_hash"] or result.get("batch_key") != source["batch_key"]:
        raise ValueError("Restore result belongs to another source")
    originals = {t["source_key"]: t for t in result["targets"]}
    candidates = {c["source_key"]: c for c in source["candidates"]}
    targets = []
    changed = result["modified_source_keys"]
    if len(changed) != len(set(changed)): raise ValueError("Duplicate restored source key")
    for key in changed:
        if key not in originals or key not in candidates: raise ValueError("Restore identity is absent from the source evidence")
        old = originals[key]
        if any(old[k] != candidates[key][k] for k in candidates[key]): raise ValueError("Restore source evidence differs")
        targets.append({**candidates[key], "expected_before": None, "desired_after": 0})
    return targets


def make_preview(db, source, restore_result=None):
    columns = schema_columns(db)
    before = fingerprints(db, columns)
    batches = db.rows("SELECT batch_key,source_hash FROM migration_batch WHERE batch_key=" + literal(source["batch_key"]))
    if batches != [{"batch_key": source["batch_key"], "source_hash": source["source_hash"]}]:
        raise ValueError("Imported batch/source hash does not match")
    profiles = unique(db.rows("SELECT user_id,source_key,total_uses FROM member_profile"), lambda p: p["source_key"], "target source key")
    archives = unique(db.rows("SELECT source_key,user_id,SHA2(details_json,256) AS details_sha256 FROM legacy_record WHERE source_system='huiben' AND record_type='MEMBER'"),
                      lambda r: r["source_key"], "archived member")
    targets = restore_targets(restore_result, source) if restore_result is not None else source["candidates"]
    reviewed = []
    for target in targets:
        profile = profiles.get(target["source_key"])
        archive = archives.get(target["source_member_id"])
        if not profile or not archive or archive["user_id"] != profile["user_id"] or archive["details_sha256"] != target["details_sha256"]:
            raise ValueError("Member source key, identity or archived source differs")
        current = total_value(profile["total_uses"])
        if current not in (target["expected_before"], target["desired_after"]): raise ValueError("Target total-use value drifted")
        reviewed.append({**target, "user_id": int(profile["user_id"]), "preview_total_uses": current})
    return {"tool_version": VERSION, "direction": "restore" if restore_result is not None else "forward",
        "source_hash": source["source_hash"], "batch_key": source["batch_key"], "schema": columns, "before": before,
        "targets": reviewed, "created_at": dt.datetime.now(dt.timezone.utc).isoformat()}


def pk_condition(fields, key):
    primary = [c["column_name"] for c in fields if c["column_key"] == "PRI"]
    values = json.loads(key)
    if len(values) != len(primary): raise ValueError("Primary-key fingerprint differs")
    return " AND ".join(identifier(k) + " <=> " + literal(v) for k, v in zip(primary, values))


def build_correction_sql(preview):
    tables = tables_from(preview["schema"])
    targets = {int(t["user_id"]): t for t in preview["targets"]}
    if len(targets) != len(preview["targets"]): raise ValueError("Duplicate correction identity")
    sql = ["SET NAMES utf8mb4;", "SET SESSION group_concat_max_len=16777216;", "SET SESSION innodb_lock_wait_timeout=15;",
        "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;", "START TRANSACTION;",
        "CREATE TEMPORARY TABLE hb_unlimited_guard (ok INT NOT NULL CHECK (ok=1)) ENGINE=InnoDB;"]
    # Full scans at SERIALIZABLE lock existing rows and insertion gaps. All hashes
    # and source/value guards run again inside these locks before any update.
    lock_order = ["inventory_mutex"] + [table for table in tables if table != "inventory_mutex"]
    for table in lock_order:
        fields = tables[table]
        primary = ",".join(identifier(c["column_name"]) for c in fields if c["column_key"] == "PRI")
        sql.append("SELECT " + primary + " FROM " + identifier(table) + " ORDER BY " + primary + " FOR UPDATE;")
    def guard(condition): sql.append("INSERT INTO hb_unlimited_guard VALUES (IF(" + condition + ",1,0));")
    guard(schema_hash_sql() + "=" + literal(schema_hash(preview["schema"])))
    guard("(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='member_profile')=0")
    guard("(SELECT COUNT(*) FROM inventory_mutex WHERE id=1)=1")
    guard("(SELECT COUNT(*) FROM migration_batch WHERE batch_key=" + literal(preview["batch_key"]) + " AND source_hash=" + literal(preview["source_hash"]) + ")=1")
    def preserved():
        for table, fields in tables.items():
            rows = [r for r in preview["before"] if r["table_name"] == table]
            guard("(SELECT COUNT(*) FROM " + identifier(table) + ")=" + str(len(rows)))
            for row in rows:
                targeted = table == "member_profile" and int(json.loads(row["row_key"])[0]) in targets
                digest = row["stable_digest"] if targeted else row["digest"]
                guard("(SELECT " + row_hash(fields, targeted) + " FROM " + identifier(table) + " WHERE " + pk_condition(fields, row["row_key"]) + ")=" + literal(digest))
    preserved()
    for index, target in enumerate(preview["targets"]):
        where = "user_id=" + str(int(target["user_id"])) + " AND BINARY source_key=" + literal(target["source_key"])
        guard("(SELECT COUNT(*) FROM member_profile WHERE " + where + " AND (total_uses <=> " + literal(target["expected_before"]) + " OR total_uses <=> " + literal(target["desired_after"]) + "))=1")
        guard("(SELECT COUNT(*) FROM legacy_record WHERE BINARY source_system='huiben' AND BINARY record_type='MEMBER' AND BINARY source_key="
              + literal(target["source_member_id"]) + " AND user_id=" + str(int(target["user_id"])) + " AND SHA2(details_json,256)=" + literal(target["details_sha256"]) + ")=1")
    for index, target in enumerate(preview["targets"]):
        sql.append("UPDATE member_profile SET total_uses=" + literal(target["desired_after"]) + " WHERE user_id=" + str(int(target["user_id"]))
            + " AND BINARY source_key=" + literal(target["source_key"]) + " AND total_uses <=> " + literal(target["expected_before"]) + ";")
        sql.append("SET @hb_changed_" + str(index) + "=ROW_COUNT();")
    preserved()
    for target in preview["targets"]:
        guard("(SELECT total_uses <=> " + literal(target["desired_after"]) + " FROM member_profile WHERE user_id=" + str(int(target["user_id"])) + ")")
    sql.extend(("DROP TEMPORARY TABLE hb_unlimited_guard;", "COMMIT;"))
    for index, target in enumerate(preview["targets"]):
        sql.append("SELECT 'HB_CORRECTION_CHANGED'," + literal(target["source_key"]) + ",@hb_changed_" + str(index) + ";")
    return "\n".join(sql) + "\n"


def verify_after(db, preview):
    columns = schema_columns(db)
    if columns != preview["schema"]: raise ValueError("Schema changed after correction")
    current = fingerprints(db, columns)
    targets = {int(t["user_id"]): t for t in preview["targets"]}
    if len(current) != len(preview["before"]): raise ValueError("Protected record count changed")
    indexed = {(r["table_name"], r["row_key"]): r for r in current}
    for old in preview["before"]:
        new = indexed.get((old["table_name"], old["row_key"]))
        targeted = old["table_name"] == "member_profile" and int(json.loads(old["row_key"])[0]) in targets
        field = "stable_digest" if targeted else "digest"
        if new is None or old[field] != new[field]: raise ValueError("Unrelated record or field changed")
    profiles = {int(p["user_id"]): total_value(p["total_uses"]) for p in db.rows("SELECT user_id,total_uses FROM member_profile")}
    if any(profiles.get(uid) != target["desired_after"] for uid, target in targets.items()): raise ValueError("Correction total differs")


def apply_preview(db, preview):
    if schema_columns(db) != preview["schema"]: raise ValueError("Reviewed schema drifted")
    output = db.run(build_correction_sql(preview))
    changed, reported = [], set()
    allowed = {t["source_key"] for t in preview["targets"]}
    for line in output.splitlines():
        parts = line.split("\t")
        if parts[0] != "HB_CORRECTION_CHANGED": continue
        if len(parts) != 3 or parts[1] not in allowed or parts[1] in reported or parts[2] not in ("0", "1"):
            raise ValueError("Unexpected correction result")
        reported.add(parts[1])
        if parts[2] == "1": changed.append(parts[1])
    if reported != allowed: raise ValueError("Incomplete correction result")
    # Preservation and desired totals were checked under locks before COMMIT.
    # Do not turn a later connection/read failure into an apparent rollback.
    return {"tool_version": VERSION, "applied": True, "direction": preview["direction"], "source_hash": preview["source_hash"],
        "batch_key": preview["batch_key"], "targets": preview["targets"], "modified_source_keys": changed,
        "protected_records_preserved": True, "completed_at": dt.datetime.now(dt.timezone.utc).isoformat()}


def reviewed_preview(path, expected_hash, expected_source_hash, source):
    if not expected_hash or not expected_source_hash: raise ValueError("Apply requires reviewed preview and source SHA256")
    data = path.read_bytes()
    if sha256(data) != expected_hash: raise ValueError("Reviewed preview SHA256 differs")
    preview = json.loads(data)
    if preview.get("tool_version") != VERSION or preview.get("direction") not in ("forward", "restore"):
        raise ValueError("Unsupported correction preview")
    if preview["source_hash"] != expected_source_hash or expected_source_hash != source["source_hash"] or preview["batch_key"] != source["batch_key"]:
        raise ValueError("Reviewed source hash/batch differs")
    candidates = {c["source_key"]: c for c in source["candidates"]}
    keys = [t["source_key"] for t in preview["targets"]]
    if len(keys) != len(set(keys)) or not set(keys) <= candidates.keys(): raise ValueError("Reviewed source identities differ")
    if preview["direction"] == "forward" and set(keys) != candidates.keys(): raise ValueError("Forward preview omitted source candidates")
    for target in preview["targets"]:
        candidate = candidates[target["source_key"]]
        expected = {**candidate, "expected_before": None, "desired_after": 0} if preview["direction"] == "restore" else candidate
        if any(target[k] != expected[k] for k in expected): raise ValueError("Reviewed correction differs from source evidence")
    return preview


def production_gate(backup):
    if not backup: raise ValueError("Production apply requires a verified backup")
    verify_backup(backup)
    for name in ("library-backend-1", "library-frontend-1"):
        running = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Running}}", name], text=True).strip()
        if running != "false": raise ValueError("Stop frontend/backend before correction")


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--container", default="library-mysql-1")
    parser.add_argument("--trial-schema")
    parser.add_argument("--batch-key", default=BATCH_KEY)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--restore-from", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--reviewed-preview", type=Path)
    parser.add_argument("--expected-preview-sha256")
    parser.add_argument("--expected-source-hash")
    parser.add_argument("--backup", type=Path)
    args = parser.parse_args(argv)
    root = args.snapshot.resolve(strict=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    default_output = "unlimited-correction-result-" + stamp + ".json" if args.apply else "unlimited-correction-preview.json"
    output = (args.output or root / default_output).resolve()
    if output == root / "source" or root / "source" in output.parents: raise ValueError("Never write inside the immutable source")
    source = source_evidence(root, args.batch_key)
    if args.expected_source_hash and args.expected_source_hash != source["source_hash"]: raise ValueError("Expected source SHA256 differs")
    db = CorrectionDatabase(args.container, args.trial_schema)
    if args.apply:
        if not args.reviewed_preview or args.restore_from: raise ValueError("Apply the reviewed preview; restore-from is for generating a new preview")
        if output == args.reviewed_preview.resolve(): raise ValueError("Result must not overwrite the reviewed preview")
        if output.exists(): raise ValueError("Preserve earlier correction results; choose a new output path")
        preview = reviewed_preview(args.reviewed_preview, args.expected_preview_sha256, args.expected_source_hash, source)
        if not args.trial_schema: production_gate(args.backup)
        report = apply_preview(db, preview)
        report["reviewed_preview_sha256"] = args.expected_preview_sha256
        private_json(output, report)
        print(compact({"applied": True, "direction": report["direction"], "modified": len(report["modified_source_keys"]),
            "alreadyAtDesiredValue": len(report["targets"]) - len(report["modified_source_keys"]), "protectedRecordsPreserved": True,
            "resultFile": str(output), "resultSha256": sha256(output.read_bytes())}))
    else:
        restore = load(args.restore_from) if args.restore_from else None
        if args.restore_from and output == args.restore_from.resolve(): raise ValueError("Preview must not overwrite the restore evidence")
        preview = make_preview(db, source, restore)
        private_json(output, preview)
        pending = sum(t["preview_total_uses"] != t["desired_after"] for t in preview["targets"])
        print(compact({"previewReady": True, "direction": preview["direction"], "pending": pending,
            "alreadyAtDesiredValue": len(preview["targets"]) - pending, "sourceHash": source["source_hash"],
            "previewFile": str(output), "previewSha256": sha256(output.read_bytes())}))


if __name__ == "__main__": main()
