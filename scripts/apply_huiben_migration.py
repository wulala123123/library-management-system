"""Import a reconciled snapshot additively, in one transaction, after backup.

Run on the Docker host. Default mode only writes a private SQL preview.
Production apply requires a verified backup and stopped application containers.
The application schema must already include Flyway V4.
"""
import argparse
import datetime as dt
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import xml.etree.ElementTree as ET


def literal(value):
    if value is None: return "NULL"
    if value == "": return "''"
    if isinstance(value, (int, Decimal)): return str(value)
    return "CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4)"


def var(prefix, key): return "@hb_" + prefix + "_" + hashlib.sha256(str(key).encode()).hexdigest()[:20]
def compact(value): return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
def row_key(row): return int(row["id"])


def build_sql(payload, before, hashes, commit=False):
    if len(hashes) != len(payload["members"]) or any(not re.fullmatch(r"\$2[aby]\$12\$[./A-Za-z0-9]{53}", h) for h in hashes):
        raise ValueError("Supply one independently generated BCrypt cost-12 hash per member")
    if len(set(hashes)) != len(hashes): raise ValueError("Passwords must be independently generated")
    existing = {b["isbn"]: b for b in before["book"]}
    usernames = {u["username"] for u in before["sys_user"]}
    if usernames.intersection(m["username"] for m in payload["members"]): raise ValueError("Imported username already exists")
    for b in payload["books"]:
        previous = existing.get(b["isbn"])
        if previous and (previous["title"] != b["title"] or any(previous[k] and b[k] and previous[k] != b[k] for k in ("author", "publisher")) or str(previous["deleted"]) != "0"):
            raise ValueError("Existing ISBN conflicts with imported metadata; review before merging")
    sql = ["SET NAMES utf8mb4;", "START TRANSACTION;",
           "SELECT id FROM inventory_mutex WHERE id = 1 FOR UPDATE;"]
    def guard(condition): sql.append("INSERT INTO migration_check (id,ok) VALUES (1,IF(" + condition + ",1,0)) ON DUPLICATE KEY UPDATE ok=VALUES(ok);")
    def insert(table, fields):
        sql.append("INSERT INTO " + table + " (" + ",".join(fields) + ") VALUES (" + ",".join(literal(v) if not isinstance(v, Ref) else v.sql for v in fields.values()) + ");")
    for table in ("sys_user", "book", "borrow_record", "book_category", "fine_record"):
        guard(f"(SELECT COUNT(*) FROM {table}) = {len(before[table])}")
    guard("(SELECT COUNT(*) FROM migration_batch WHERE batch_key = " + literal(payload["batch_key"]) + ") = 0")
    insert("migration_batch", {"batch_key": payload["batch_key"], "source_hash": payload["source_hash"], "summary_json": compact(payload["summary"])})
    category = next((c for c in before["book_category"] if c["name"] == "绘本（原馆迁入）" and str(c["deleted"]) == "0"), None)
    if category: sql.append("SET @hb_category = " + str(int(category["id"])) + ";")
    else:
        insert("book_category", {"name": "绘本（原馆迁入）", "parent_id": 0, "level": 1, "sort_order": 0, "status": 1})
        sql.append("SET @hb_category = LAST_INSERT_ID();")
    for member, password in zip(payload["members"], hashes):
        insert("sys_user", {"username": member["username"], "password": password, "real_name": member["name"], "phone": member["phone"],
                            "role": "READER", "status": 1, "create_time": member["registered_at"]})
        user = var("user", member["uid"])
        sql.append("SET " + user + " = LAST_INSERT_ID();")
        profile = {k: member[k] for k in ("card_no", "plan_name", "service_start", "service_end", "used_uses", "total_uses", "deposit_amount", "deposit_status", "paid_fee")}
        insert("member_profile", {"user_id": Ref(user), "source_key": "huiben:" + member["uid"], **profile})
    for book in payload["books"]:
        if book["isbn"] in existing:
            book_id = int(existing[book["isbn"]]["id"])
            sql.append(f"UPDATE book SET stock_total = stock_total + {book['stock_total']}, stock_available = stock_available + {book['stock_available']} WHERE id = {book_id};")
            sql.append("SET " + var("book", book["isbn"]) + " = " + str(book_id) + ";")
        else:
            insert("book", {"isbn": book["isbn"], "title": book["title"], "author": book["author"], "publisher": book["publisher"],
                "category_id": Ref("@hb_category"), "price": book["price"], "stock_total": book["stock_total"], "stock_available": book["stock_available"],
                "cover_image": book["cover_path"], "status": 1, "create_time": book["created_at"]})
            sql.append("SET " + var("book", book["isbn"]) + " = LAST_INSERT_ID();")
    for copy in payload["copies"]:
        insert("book_copy", {"book_id": Ref(var("book", copy["isbn"])), "barcode": copy["barcode"], "status": copy["status"], "location": copy["location"] or None})
        sql.append("SET " + var("copy", copy["barcode"]) + " = LAST_INSERT_ID();")
    for loan in payload["loans"]:
        remark = "原系统借出：" + loan["borrowed_at"] + ("；归还：" + loan["returned_at"] if loan["returned_at"] else "；原期限：不限制")
        isbn = next(c["isbn"] for c in payload["copies"] if c["barcode"] == loan["barcode"])
        insert("borrow_record", {"source_key": loan["source_key"], "user_id": Ref(var("user", loan["uid"])), "book_id": Ref(var("book", isbn)),
            "copy_id": Ref(var("copy", loan["barcode"])), "borrow_date": loan["borrowed_at"][:10], "due_date": loan["due_date"],
            "due_date_kind": loan["due_date_kind"], "return_date": loan["returned_at"][:10] if loan["returned_at"] else None,
            "renew_count": 0, "status": loan["status"], "remark": remark, "create_time": loan["borrowed_at"],
            "update_time": loan["returned_at"] or loan["borrowed_at"]})
    def archive(kind, key, name, summary, details, user=None, book=None, time=None):
        insert("legacy_record", {"source_system": "huiben", "record_type": kind, "source_key": key,
            "display_name": name, "summary": summary, "details_json": compact(details),
            "user_id": Ref(var("user", user)) if user else None, "book_id": Ref(var("book", book)) if book else None, "source_time": time})
    for m in payload["members"]:
        archive("MEMBER", m["uid"], m["name"], f"{m['plan_name']}；服务至 {m['service_end']}", m["details"], user=m["uid"], time=m["registered_at"])
    for c in payload["copies"]:
        archive("BOOK", c["barcode"], c["details"]["书籍名称"], "ISBN " + c["isbn"] + "；" + ("已借出" if c["status"] == "BORROWED" else "在馆"),
                c["details"], book=c["isbn"], time=c["details"]["录入时间"])
    for o in payload["orders"]:
        archive("ORDER", o["id"], "订单 " + o["id"] + " · " + o["name"], o["status"], o["details"], user=o["uid"], time=o["created_at"])
    for p in payload["plans"]:
        archive("PLAN", p["id"], p["name"], "原系统借阅计划", p["details"])
    source_loans = "source_key LIKE 'huiben:%'"
    guard(f"(SELECT COUNT(*) FROM borrow_record WHERE {source_loans}) = {len(payload['loans'])}")
    guard(f"(SELECT COUNT(*) FROM borrow_record WHERE {source_loans} AND status = 'BORROWING') = {payload['summary']['activeLoans']}")
    guard("(SELECT COUNT(*) FROM borrow_record WHERE " + source_loans + " AND due_date IS NOT NULL) = 0")
    guard("(SELECT COUNT(*) FROM book_copy c JOIN book b ON c.book_id = b.id WHERE c.status = 'BORROWED' AND NOT EXISTS (SELECT 1 FROM borrow_record br WHERE br.copy_id = c.id AND br.status IN ('BORROWING','OVERDUE') AND br.deleted = 0)) = 0")
    for b in payload["books"]:
        baseline = existing.get(b["isbn"], {})
        total = int(baseline.get("stock_total", 0)) + b["stock_total"]
        available = int(baseline.get("stock_available", 0)) + b["stock_available"]
        guard(f"(SELECT stock_total = {total} AND stock_available = {available} FROM book WHERE id = {var('book', b['isbn'])})")
    sql.append("DELETE FROM migration_check WHERE id = 1;")
    sql.append("COMMIT;" if commit else "ROLLBACK;")
    return "\n".join(sql) + "\n"


class Ref:
    def __init__(self, sql): self.sql = sql


class Database:
    def __init__(self, container, schema=None):
        self.container, self.schema = container, schema
        if schema and not re.fullmatch(r"library_transfer_trial_[A-Za-z0-9_]{1,40}", schema): raise ValueError("Trial database name is outside the allowed prefix")

    def run(self, sql, xml=False):
        password = '"$MYSQL_ROOT_PASSWORD"' if self.schema else '"$MYSQL_PASSWORD"'
        user = "root" if self.schema else "library_app"
        database = shlex.quote(self.schema) if self.schema else '"$MYSQL_DATABASE"'
        flags = " --xml" if xml else " --batch --skip-column-names"
        command = f"MYSQL_PWD={password} exec mysql -u {user} --default-character-set=utf8mb4{flags} -D {database}"
        result = subprocess.run(["docker", "exec", "-i", self.container, "sh", "-c", command], input=sql, text=True, capture_output=True)
        if result.returncode: raise RuntimeError("Database operation failed: " + result.stderr[:300])
        return result.stdout

    def rows(self, sql):
        document = ET.fromstring(self.run(sql, xml=True))
        return [{field.get("name"): None if field.get("{http://www.w3.org/2001/XMLSchema-instance}nil") == "true" else field.text for field in row} for row in document.findall("row")]

    def inventory(self):
        return {table: self.rows("SELECT * FROM " + table + " ORDER BY id") for table in ("sys_user", "book_category", "book", "borrow_record", "fine_record")}


def verify_preserved(payload, before, after):
    additions = {b["isbn"]: b for b in payload["books"]}
    for table, original in before.items():
        current = {row_key(r): r for r in after[table]}
        for old in original:
            new = current[row_key(old)]
            expected = dict(old)
            if table == "book" and old["isbn"] in additions:
                b = additions[old["isbn"]]
                expected["stock_total"] = str(int(old["stock_total"]) + b["stock_total"])
                expected["stock_available"] = str(int(old["stock_available"]) + b["stock_available"])
                expected["update_time"] = new["update_time"]
            if expected != new: raise ValueError("Pre-existing record changed unexpectedly: " + table)
    if len(after["sys_user"]) != len(before["sys_user"]) + len(payload["members"]): raise ValueError("User count mismatch")
    if len(after["borrow_record"]) != len(before["borrow_record"]) + len(payload["loans"]): raise ValueError("Borrow count mismatch")
    old_isbns = {b["isbn"] for b in before["book"]}
    if len(after["book"]) != len(before["book"]) + sum(b["isbn"] not in old_isbns for b in payload["books"]): raise ValueError("Catalog count mismatch")


def verify_backup(path):
    path = path.resolve(strict=True)
    base = Path("/opt/library-management-system/backups").resolve()
    if base not in path.parents: raise ValueError("Backup must be inside the library backup directory")
    hashes = {}
    for line in (path / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split(None, 1)
        name = name.strip().lstrip("*")
        if Path(name).name != name: raise ValueError("Invalid backup manifest path")
        hashes[name] = digest
    for name in ("database.sql", "uploads.tar.gz", "environment.env", "application-version.txt"):
        if hashlib.sha256((path / name).read_bytes()).hexdigest() != hashes.get(name): raise ValueError("Backup checksum mismatch")
    if (path / "database.sql").stat().st_size < 100: raise ValueError("Empty database backup")


def install_covers(root, payload):
    info = json.loads(subprocess.check_output(["docker", "inspect", "library-backend-1"], text=True))[0]
    mount = next(m for m in info["Mounts"] if m["Destination"] == "/app/uploads")
    if mount["Type"] != "volume" or mount["Name"] != "library_uploads": raise ValueError("Unexpected uploads volume")
    target = Path(mount["Source"]).resolve(strict=True) / "book-covers"
    target.mkdir(exist_ok=True)
    target.chmod(0o755)
    for name in {b["cover_file"] for b in payload["books"]}:
        source, dest = root / "covers" / name, target / name
        data = source.read_bytes()
        if not data: raise ValueError("Empty cover")
        if dest.exists() and dest.read_bytes() != data: raise ValueError("Existing cover collision")
        if not dest.exists(): shutil.copyfile(source, dest); dest.chmod(0o644)


if __name__ == "__main__":
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--container", default="library-mysql-1")
    parser.add_argument("--trial-schema")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup", type=Path)
    parser.add_argument("--expected-source-hash")
    args = parser.parse_args()
    root = args.snapshot.resolve()
    from prepare_huiben_migration import prepare
    payload = prepare(root)
    if args.expected_source_hash and payload["source_hash"] != args.expected_source_hash: raise ValueError("Source snapshot changed")
    db = Database(args.container, args.trial_schema)
    existing_batch = db.rows("SELECT source_hash, summary_json FROM migration_batch WHERE batch_key = " + literal(payload["batch_key"]))
    if existing_batch:
        if existing_batch[0]["source_hash"] != payload["source_hash"]: raise ValueError("Batch exists with a different source snapshot")
        print(compact({"alreadyApplied": True, "summary": json.loads(existing_batch[0]["summary_json"])}))
        raise SystemExit(0)
    if args.apply and not args.trial_schema:
        if not args.backup or not args.expected_source_hash: raise ValueError("Production apply requires backup and expected source hash")
        verify_backup(args.backup)
        for name in ("library-backend-1", "library-frontend-1"):
            running = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Running}}", name], text=True).strip()
            if running != "false": raise ValueError("Stop frontend/backend after backup before the final import")
    before = db.inventory()
    (root / "target-before.json").write_text(compact(before), encoding="utf-8")
    hashes = json.loads((root / "password-hashes.json").read_text(encoding="utf-8-sig"))
    sql = build_sql(payload, before, hashes, commit=args.apply)
    (root / "import-preview.sql").write_text(sql, encoding="utf-8")
    if not args.apply:
        print(compact({"previewReady": True, "targetCounts": {t: len(r) for t, r in before.items()}, "source": payload["summary"], "sourceHash": payload["source_hash"]}))
        raise SystemExit(0)
    if not args.trial_schema: install_covers(root, payload)
    db.run(sql)
    after = db.inventory()
    verify_preserved(payload, before, after)
    report = {"applied": True, "trial": bool(args.trial_schema), "sourceHash": payload["source_hash"], "originalRecordsPreserved": True,
        "targetCounts": {t: len(r) for t, r in after.items()}, "source": payload["summary"], "completedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    (root / "import-result.json").write_text(compact(report), encoding="utf-8")
    print(compact(report))
