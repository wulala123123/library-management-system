"""Synthetic correction tests. --mysql additionally requires disposable CI MySQL."""
import contextlib
import concurrent.futures
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import uuid
import xml.etree.ElementTree as ET
import zipfile

from apply_huiben_migration import Database, compact, literal
import correct_huiben_unlimited_uses as correction
from prepare_huiben_migration import parse_total_uses


def write_xlsx(path, rows):
    headers = list(rows[0])
    sheet = ET.Element("worksheet", xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main")
    data = ET.SubElement(sheet, "sheetData")
    for index, values in enumerate([headers] + [[r[k] for k in headers] for r in rows], 1):
        row = ET.SubElement(data, "row", r=str(index))
        for column, value in enumerate(values):
            cell = ET.SubElement(row, "c", r=chr(65 + column) + str(index), t="inlineStr")
            ET.SubElement(ET.SubElement(cell, "is"), "t").text = str(value)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("xl/worksheets/sheet1.xml", ET.tostring(sheet, encoding="utf-8"))


def synthetic_source(root):
    folder = root / "source"; folder.mkdir()
    rows, pages, details, archives = [], [], [], {}
    for uid, exported, page_total in ((101, "0", "总:不限已:1次2本 余:不限"), (102, "12", "总:12次 已:1次2本 余:11次"),
            (103, "0", "总:0次已:0次0本余:0次"), (104, "0", "总:未知已:0次0本余:未知"),
            (105, "0", "总: 不限已:1次2本 余:不限"), (106, "0", "总次数：\n不 限 次 数 已：1次2本 余：不限")):
        # Shared phone numbers must still yield separate source identities.
        row = {"会员名字": "合成会员" + str(uid), "手机号": "13800000000", "总次数": exported}
        rows.append(row)
        cells = [""] * 14; cells[2] = "测试缴费状态"; cells[3] = "测试押金状态"; cells[7] = page_total
        pages.append({"cells": cells, "links": [{"href": "/users/exportuserbook?uid=" + str(uid), "text": row["手机号"]},
                      {"href": "/users/read?uid=" + str(uid), "text": row["会员名字"]}]})
        detail = "合成补充资料"; details.append({"link": "/users/read?uid=" + str(uid), "content": detail})
        archives[str(uid)] = {**row, "缴费状态": cells[2], "押金缴纳状态": cells[3], "原系统补充资料": detail}
    write_xlsx(folder / "members.xlsx", rows)
    for name, value in (("members-page.json", pages), ("member-details.json", details)):
        (folder / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.iterdir())}
    source_hash = hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()
    correction.private_json(root / "source-manifest.json", {"sha256": source_hash, "files": manifest})
    return correction.source_evidence(root), archives


def minimal_columns():
    definitions = {"inventory_mutex": ("id",), "legacy_record": ("id", "source_key", "details_json"),
        "member_profile": ("user_id", "source_key", "total_uses", "card_no"),
        "migration_batch": ("batch_key", "source_hash"), "sys_user": ("id", "password", "token_version")}
    return [{"table_name": table, "column_name": name, "column_type": "varchar(128)", "is_nullable": "NO",
             "column_key": "PRI" if index == 1 else "", "extra": "", "ordinal_position": str(index), "engine": "InnoDB"}
            for table, names in sorted(definitions.items()) for index, name in enumerate(names, 1)]


class ParserTests(unittest.TestCase):
    def test_explicit_unlimited_text_and_presentation(self):
        for page in ("总:不限已:1次2本 余:不限", "总：　不\n限 已：1次2本 余：不限", "总 次 数 ： 不 限 次 数 已：1次2本",
                     "总:不限制次数", "总:无限次数", "总:无上限", "总：次数不限"):
            with self.subTest(page=page): self.assertIsNone(parse_total_uses("０", page))
        for value in ("不限", " 不\n限次数 ", "无限制", "借阅次数不限"):
            self.assertIsNone(parse_total_uses(value))

    def test_finite_zero_and_unknown_page_not_unlimited(self):
        for export, page, expected in (("0", "总:0次已:0次0本余:0次", 0), ("１２", "总:１２次已:1次余:11次", 12),
                ("0", "总:未知 余:不限", 0), ("0", "已:不限 余:不限", 0), ("0", "总:不限制0次", 0),
                ("7.0", "", 7), ("0", "总:不限?", 0)):
            with self.subTest(export=export, page=page): self.assertEqual(parse_total_uses(export, page), expected)

    def test_unknown_missing_and_conflicting_values_rejected(self):
        for value in (None, "", "未知", "未设置", "-1", "0.5", "Infinity", "不限0次", "2147483648"):
            with self.subTest(value=value), self.assertRaises(ValueError): parse_total_uses(value)
        for exported, page in (("5", "总:不限"), ("12", "总:11次"), ("不限", "总:0次")):
            with self.subTest(exported=exported), self.assertRaises(ValueError): parse_total_uses(exported, page)


class MaintenanceConnectionTests(unittest.TestCase):
    def test_production_uses_only_container_migration_credential(self):
        result = mock.Mock(returncode=0, stdout="ok", stderr="")
        with mock.patch.object(correction.subprocess, "run", return_value=result) as run, \
                mock.patch.dict(os.environ, {"MYSQL_MIGRATION_PASSWORD": "host-value-must-never-be-used"}):
            self.assertEqual(correction.CorrectionDatabase("synthetic-container").run("SELECT 1;"), "ok")
        args, kwargs = run.call_args
        self.assertEqual(args[0][:6], ["docker", "exec", "-i", "synthetic-container", "sh", "-c"])
        command = args[0][6]
        self.assertIn("-u library_migrator", command)
        self.assertIn("${MYSQL_MIGRATION_PASSWORD:?", command)
        self.assertNotIn("-u root", command)
        self.assertNotIn("MYSQL_ROOT_PASSWORD", command)
        self.assertNotIn("library_app", command)
        self.assertNotIn("host-value-must-never-be-used", repr(run.call_args))
        self.assertEqual(kwargs["input"], "SELECT 1;")

    def test_trial_root_is_explicit_and_prefix_restricted(self):
        result = mock.Mock(returncode=0, stdout="", stderr="")
        with mock.patch.object(correction.subprocess, "run", return_value=result) as run:
            correction.CorrectionDatabase("synthetic-container", "library_transfer_trial_unit").run("SELECT 1;", xml=True)
        command = run.call_args.args[0][6]
        self.assertIn("-u root", command)
        self.assertIn('"$MYSQL_ROOT_PASSWORD"', command)
        self.assertIn("--xml", command)
        self.assertIn("library_transfer_trial_unit", command)
        for schema in ("production", "library_transfer_trial_", "library_transfer_trial_x;DROP DATABASE production"):
            with self.subTest(schema=schema), self.assertRaises(ValueError): correction.CorrectionDatabase("synthetic-container", schema)

    def test_insufficient_privileges_cannot_claim_no_triggers(self):
        db = mock.Mock(); db.rows.return_value = [{"total": "0"}]
        with self.assertRaisesRegex(ValueError, "TRIGGER privileges"):
            correction.schema_columns(db)
        self.assertEqual(db.rows.call_count, 1)
        self.assertIn("information_schema.SCHEMA_PRIVILEGES", db.rows.call_args.args[0])
        self.assertNotIn("information_schema.TRIGGERS", db.rows.call_args.args[0])


class SourceAndSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.archives = synthetic_source(self.root)

    def test_source_mapping_and_immutable_manifest(self):
        self.assertEqual([c["source_member_id"] for c in self.source["candidates"]], ["101", "106"])
        (self.root / "source/members-page.json").write_text("[]", encoding="utf-8")
        with self.assertRaises(ValueError): correction.source_evidence(self.root)

    def preview(self):
        db = mock.Mock()
        def rows(sql):
            self.assertTrue(sql.startswith("SELECT "), sql)
            if "FROM migration_batch" in sql: return [{"batch_key": self.source["batch_key"], "source_hash": self.source["source_hash"]}]
            if "FROM member_profile" in sql: return [{"source_key": "huiben:" + uid, "user_id": uid, "total_uses": "0"} for uid in ("101", "106")]
            if "FROM legacy_record" in sql: return [{"source_key": c["source_member_id"], "user_id": c["source_member_id"], "details_sha256": c["details_sha256"]} for c in self.source["candidates"]]
            raise AssertionError(sql)
        db.rows.side_effect = rows
        before = [{"table_name": "member_profile", "row_key": "[101]", "digest": "a" * 64, "stable_digest": "b" * 64}]
        with mock.patch.object(correction, "schema_columns", return_value=minimal_columns()), mock.patch.object(correction, "fingerprints", return_value=before):
            preview = correction.make_preview(db, self.source)
        db.run.assert_not_called()
        return preview

    def test_preview_only_reads_and_sql_never_selects_password(self):
        preview = self.preview()
        sql = correction.build_correction_sql(preview)
        updates = [line for line in sql.splitlines() if line.startswith("UPDATE ")]
        self.assertEqual(len(updates), 2)
        self.assertTrue(all(line.startswith("UPDATE member_profile SET total_uses=NULL WHERE ") for line in updates))
        self.assertNotIn("`password`", sql)
        self.assertIn("`token_version`", correction.row_hash(correction.tables_from(preview["schema"])["sys_user"]))
        self.assertIn("SERIALIZABLE", sql)
        self.assertIn("FOR UPDATE", sql)
        self.assertIn("CREATE TEMPORARY TABLE", sql)
        self.assertNotIn("INSERT INTO migration_check", sql)

    def test_review_requires_hashes_and_source_evidence(self):
        path = self.root / "preview.json"; preview = self.preview(); correction.private_json(path, preview)
        digest = correction.sha256(path.read_bytes())
        self.assertEqual(correction.reviewed_preview(path, digest, self.source["source_hash"], self.source), preview)
        for preview_hash, source_hash in ((None, self.source["source_hash"]), (digest, None), ("0" * 64, self.source["source_hash"]), (digest, "0" * 64)):
            with self.assertRaises(ValueError): correction.reviewed_preview(path, preview_hash, source_hash, self.source)
        preview["targets"][0]["expected_before"] = 7; correction.private_json(path, preview)
        with self.assertRaises(ValueError): correction.reviewed_preview(path, correction.sha256(path.read_bytes()), self.source["source_hash"], self.source)

    def test_restore_only_actual_changes(self):
        preview = self.preview()
        result = {**preview, "applied": True, "modified_source_keys": ["huiben:101"]}
        targets = correction.restore_targets(result, self.source)
        self.assertEqual(len(targets), 1)
        self.assertIsNone(targets[0]["expected_before"])
        self.assertEqual(targets[0]["desired_after"], 0)
        result["direction"] = "restore"
        with self.assertRaises(ValueError): correction.restore_targets(result, self.source)

    def test_production_backup_and_stopped_application_gates(self):
        with self.assertRaises(ValueError): correction.production_gate(None)
        with mock.patch.object(correction, "verify_backup", side_effect=ValueError("bad checksum")):
            with self.assertRaises(ValueError): correction.production_gate(self.root)
        with mock.patch.object(correction, "verify_backup") as backup, mock.patch.object(correction.subprocess, "check_output", return_value="true\n"):
            with self.assertRaises(ValueError): correction.production_gate(self.root)
            backup.assert_called_once_with(self.root)
        with mock.patch.object(correction, "verify_backup"), mock.patch.object(correction.subprocess, "check_output", return_value="false\n") as inspect:
            correction.production_gate(self.root)
            self.assertEqual(inspect.call_count, 2)

    def test_cli_default_never_applies(self):
        preview = self.preview(); db = mock.Mock()
        with mock.patch.object(correction, "CorrectionDatabase", return_value=db) as connection, mock.patch.object(correction, "source_evidence", return_value=self.source), \
                mock.patch.object(correction, "make_preview", return_value=preview), mock.patch.object(correction, "apply_preview") as apply, contextlib.redirect_stdout(io.StringIO()):
            correction.main([str(self.root)])
        apply.assert_not_called(); db.run.assert_not_called()
        connection.assert_called_once_with("library-mysql-1", None)
        self.assertTrue((self.root / "unlimited-correction-preview.json").is_file())

    def test_cli_preserves_original_apply_receipt(self):
        receipt = self.root / "original-result.json"; receipt.write_text("preserve", encoding="utf-8")
        with mock.patch.object(correction, "CorrectionDatabase"), mock.patch.object(correction, "source_evidence", return_value=self.source), \
                mock.patch.object(correction, "apply_preview") as apply:
            with self.assertRaisesRegex(ValueError, "Preserve earlier correction results"):
                correction.main([str(self.root), "--apply", "--reviewed-preview", str(self.root / "approved-preview.json"), "--output", str(receipt)])
        apply.assert_not_called()
        self.assertEqual(receipt.read_text(encoding="utf-8"), "preserve")


def mysql_acceptance(db):
    """Requires an already-created isolated schema; all records are synthetic."""
    if not db.schema or not db.schema.startswith("library_transfer_trial_"): raise RuntimeError("Expected an isolated trial schema")
    project = Path(__file__).resolve().parent.parent
    for migration in sorted((project / "backend/src/main/resources/db/migration").glob("V*.sql")):
        db.run(migration.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp); source, archives = synthetic_source(root)
        db.run("INSERT INTO migration_batch(batch_key,source_hash,summary_json) VALUES (" + literal(source["batch_key"]) + "," + literal(source["source_hash"]) + ",'{}');")
        for uid, total in ((101, 0), (102, 12), (103, 0), (104, 0), (105, None), (106, 0)):
            key = str(uid)
            db.run("INSERT INTO sys_user(id,username,password,real_name,role) VALUES (" + key + ",'synthetic_" + key + "','synthetic-only-password','合成会员','READER');"
                "INSERT INTO member_profile(user_id,source_key,total_uses,used_uses,card_no,plan_name,service_start,service_end,deposit_amount,deposit_status,paid_fee) VALUES ("
                + key + "," + literal("huiben:" + key) + "," + literal(total) + ",2,'000001','合成套餐','2026-01-01','2027-01-01',100,'合成状态',50);"
                "INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER',"
                + literal(key) + ",'合成会员'," + literal(compact(archives[key])) + "," + key + ");")
        db.run("INSERT INTO sys_user(id,username,password,real_name,role) VALUES (999,'unrelated','synthetic-only-password','无关账号','ADMIN');"
            "INSERT INTO member_profile(user_id,source_key,total_uses,used_uses) VALUES (999,'other:999',9,1);"
            "INSERT INTO book(isbn,title,stock_total,stock_available) VALUES ('synthetic-book','合成绘本',1,1);")
        original = db.rows("SELECT * FROM member_profile ORDER BY user_id")
        all_before = correction.fingerprints(db, correction.schema_columns(db))
        preview = correction.make_preview(db, source)
        assert correction.fingerprints(db, preview["schema"]) == all_before, "Preview wrote to the database"
        assert [t["source_member_id"] for t in preview["targets"]] == ["101", "106"]
        assert correction.schema_hash(preview["schema"]) == db.rows("SET SESSION group_concat_max_len=16777216; SELECT " + correction.schema_hash_sql() + " AS digest")[0]["digest"]
        sql = correction.build_correction_sql(preview)
        assert "`password`" not in sql
        # Force a failure after a real UPDATE and verify connection-close rollback.
        failed = sql.replace("SET @hb_changed_0=ROW_COUNT();", "SET @hb_changed_0=ROW_COUNT();\nINSERT INTO hb_unlimited_guard VALUES (0);")
        try: db.run(failed)
        except RuntimeError: pass
        else: raise AssertionError("Expected mid-correction failure")
        assert db.rows("SELECT * FROM member_profile ORDER BY user_id") == original
        assert correction.fingerprints(db, preview["schema"]) == all_before
        # Drift in values, source association, batch, archive and unrelated fields
        # must be rejected by the transaction, including after a preview was made.
        scenarios = [
            ("UPDATE member_profile SET total_uses=7 WHERE user_id=106;", "UPDATE member_profile SET total_uses=0 WHERE user_id=106;"),
            ("UPDATE member_profile SET source_key='other:106' WHERE user_id=106;", "UPDATE member_profile SET source_key='huiben:106' WHERE user_id=106;"),
            ("UPDATE legacy_record SET user_id=999 WHERE source_key='106';", "UPDATE legacy_record SET user_id=106 WHERE source_key='106';"),
            ("UPDATE migration_batch SET source_hash='" + "f" * 64 + "';", "UPDATE migration_batch SET source_hash=" + literal(source["source_hash"]) + ";"),
            ("UPDATE legacy_record SET details_json='{}' WHERE source_key='106';", "UPDATE legacy_record SET details_json=" + literal(compact(archives["106"])) + " WHERE source_key='106';"),
            ("UPDATE member_profile SET used_uses=3 WHERE user_id=999;", "UPDATE member_profile SET used_uses=1 WHERE user_id=999;"),
        ]
        for change, undo in scenarios:
            db.run(change)
            drifted = correction.fingerprints(db, preview["schema"])
            try: correction.apply_preview(db, preview)
            except RuntimeError: pass
            else: raise AssertionError("Expected reviewed-preview drift rejection")
            assert correction.fingerprints(db, preview["schema"]) == drifted, "Rejected correction changed records"
            db.run(undo)
            # Some protected tables have automatic update timestamps. Review anew
            # after deliberately changing those records in a synthetic scenario.
            preview = correction.make_preview(db, source)
        # Hold the correction after its row/gap locks are acquired. A concurrent
        # writer must time out without modifying even an unrelated profile.
        original_run = db.run
        lock_name = "hb_ci_" + correction.sha256(db.schema.encode())[:20]
        def run_with_hold(statement, xml=False):
            if "CREATE TEMPORARY TABLE hb_unlimited_guard" in statement:
                position = statement.rindex("FOR UPDATE;") + len("FOR UPDATE;")
                statement = statement[:position] + "\nSELECT GET_LOCK(" + literal(lock_name) + ",0); SELECT SLEEP(3);" + statement[position:]
            return original_run(statement, xml)
        with mock.patch.object(db, "run", side_effect=run_with_hold), concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(correction.apply_preview, db, preview)
            deadline = time.monotonic() + 10
            while db.rows("SELECT IS_USED_LOCK(" + literal(lock_name) + ") AS owner")[0]["owner"] is None:
                if time.monotonic() > deadline: raise AssertionError("Correction never acquired its locks")
                time.sleep(0.02)
            try: db.run("SET SESSION innodb_lock_wait_timeout=1; UPDATE member_profile SET used_uses=99 WHERE user_id=999;")
            except RuntimeError as error: assert "Lock wait timeout" in str(error), "Expected a row-lock timeout"
            else: raise AssertionError("A concurrent writer bypassed correction locks")
            result = future.result(timeout=10)
        assert result["modified_source_keys"] == ["huiben:101", "huiben:106"]
        correction.verify_after(db, preview)
        after = db.rows("SELECT * FROM member_profile ORDER BY user_id")
        expected = copy.deepcopy(original)
        for row in expected:
            if row["user_id"] in ("101", "106"): row["total_uses"] = None
        assert after == expected, "Correction changed a non-target record or field"
        repeated = correction.apply_preview(db, preview)
        assert repeated["modified_source_keys"] == [], "Repeat was not idempotent"
        assert db.rows("SELECT * FROM member_profile ORDER BY user_id") == after
        # New business after correction survives targeted restoration. A changed
        # target total blocks reversal instead of overwriting that business.
        db.run("UPDATE member_profile SET used_uses=3 WHERE user_id=101; UPDATE member_profile SET total_uses=8 WHERE user_id=999;")
        restore = correction.make_preview(db, source, result)
        db.run("UPDATE member_profile SET total_uses=6 WHERE user_id=106;")
        drifted = correction.fingerprints(db, restore["schema"])
        try: correction.apply_preview(db, restore)
        except RuntimeError: pass
        else: raise AssertionError("Restore overwrote a changed entitlement")
        assert correction.fingerprints(db, restore["schema"]) == drifted
        db.run("UPDATE member_profile SET total_uses=NULL WHERE user_id=106;")
        restore = correction.make_preview(db, source, result)
        restored = correction.apply_preview(db, restore)
        assert restored["modified_source_keys"] == result["modified_source_keys"]
        correction.verify_after(db, restore)
        expected_restore = copy.deepcopy(original)
        for row in expected_restore:
            if row["user_id"] == "101": row["used_uses"] = "3"
            if row["user_id"] == "999": row["total_uses"] = "8"
        assert db.rows("SELECT * FROM member_profile ORDER BY user_id") == expected_restore
        assert correction.apply_preview(db, restore)["modified_source_keys"] == []
        # A result which made no changes must never manufacture reverse changes.
        assert correction.make_preview(db, source, repeated)["targets"] == []
    print("Unlimited-use MySQL acceptance passed: SELECT-only preview, precise targets, finite/zero/unknown preservation, atomic rollback, drift rejection, concurrent writer lock, idempotence, protected fields, targeted restore retaining new business.")


def mysql_permission_acceptance(admin_db, connect_user, test_password):
    """Exercise actual DML-only and maintenance grants in a disposable schema.

    connect_user(username) must connect to that same isolated schema with the
    synthetic test_password supplied through the client environment, never argv.
    """
    if not admin_db.schema or not admin_db.schema.startswith("library_transfer_trial_"):
        raise RuntimeError("Permission acceptance requires an isolated trial schema")
    suffix = uuid.uuid4().hex[:16]
    app_user, maintenance_user = "hb_dml_" + suffix, "hb_maintenance_" + suffix
    grant_schema = admin_db.schema.replace("_", "\\_")
    if not re.fullmatch(r"[A-Za-z0-9_-]{24,128}", test_password): raise ValueError("Use an isolated synthetic test credential")
    app_identity, maintenance_identity = "'" + app_user + "'@'%'", "'" + maintenance_user + "'@'%'"
    project = Path(__file__).resolve().parent.parent
    try:
        for migration in sorted((project / "backend/src/main/resources/db/migration").glob("V*.sql")):
            admin_db.run(migration.read_text(encoding="utf-8"))
        admin_db.run("CREATE USER " + app_identity + " IDENTIFIED BY '" + test_password + "';"
            "CREATE USER " + maintenance_identity + " IDENTIFIED BY '" + test_password + "';"
            "GRANT SELECT,INSERT,UPDATE,DELETE ON `" + grant_schema + "`.* TO " + app_identity + ";"
            "GRANT ALL PRIVILEGES ON `" + grant_schema + "`.* TO " + maintenance_identity + ";")
        app, maintenance = connect_user(app_user), connect_user(maintenance_user)
        with tempfile.TemporaryDirectory() as temp:
            source, archives = synthetic_source(Path(temp))
            admin_db.run("INSERT INTO migration_batch(batch_key,source_hash,summary_json) VALUES (" + literal(source["batch_key"]) + "," + literal(source["source_hash"]) + ",'{}');")
            for target in source["candidates"]:
                uid = target["source_member_id"]
                admin_db.run("INSERT INTO member_profile(user_id,source_key,total_uses,used_uses) VALUES (" + uid + "," + literal(target["source_key"]) + ",0,2);"
                    "INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER',"
                    + literal(uid) + ",'synthetic'," + literal(compact(archives[uid])) + "," + uid + ");")
            assert maintenance.rows("SELECT SUBSTRING_INDEX(CURRENT_USER(),'@',1) AS username")[0]["username"] == maintenance_user
            assert app.rows("SELECT COUNT(*) AS total FROM member_profile") == [{"total": "2"}]
            preview = correction.make_preview(maintenance, source)
            sql = correction.build_correction_sql(preview)
            before = correction.fingerprints(admin_db, preview["schema"])
            try: app.run(sql)
            except RuntimeError as error:
                assert any(code in str(error) for code in ("ERROR 1044", "ERROR 1142")) and "denied" in str(error).lower(), "Expected DML-only temporary-table denial"
            else: raise AssertionError("DML-only application account created a temporary table")
            assert correction.fingerprints(admin_db, preview["schema"]) == before
            try: correction.schema_columns(app)
            except ValueError as error: assert "TRIGGER privileges" in str(error)
            else: raise AssertionError("DML-only account claimed it could inspect triggers")
            result = correction.apply_preview(maintenance, preview)
            assert len(result["modified_source_keys"]) == 2
            correction.verify_after(maintenance, preview)
            admin_db.run("CREATE TRIGGER hb_permission_trigger BEFORE UPDATE ON member_profile FOR EACH ROW SET NEW.used_uses=NEW.used_uses;")
            visible = "SELECT COUNT(*) AS total FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='member_profile'"
            assert app.rows(visible) == [{"total": "0"}], "Expected restricted trigger visibility"
            assert maintenance.rows(visible) == [{"total": "1"}], "Maintenance user could not see the trigger"
            try: correction.schema_columns(maintenance)
            except ValueError as error: assert "triggers" in str(error)
            else: raise AssertionError("Correction accepted a table with a visible trigger")
            try: correction.apply_preview(maintenance, preview)
            except ValueError as error: assert "triggers" in str(error)
            else: raise AssertionError("Apply bypassed the trigger rejection")
        print("Correction permission acceptance passed: DML-only CREATE TEMPORARY TABLES denied without writes; missing TRIGGER visibility rejected; maintenance grants execute the same correction SQL; actual trigger visible and rejected.")
    finally:
        admin_db.run("DROP TRIGGER IF EXISTS hb_permission_trigger;")
        admin_db.run("DROP USER IF EXISTS " + app_identity + "," + maintenance_identity + ";")


def ci_mysql():
    if os.environ.get("CI") != "true" or os.environ.get("LIBRARY_TRANSFER_ACCEPTANCE") != "1":
        raise SystemExit("MySQL acceptance requires the isolated CI job")
    names = subprocess.check_output(["docker", "ps", "--filter", "ancestor=mysql:8.0.44", "--format", "{{.Names}}"], text=True).splitlines()
    if len(names) != 1: raise RuntimeError("Expected one disposable CI MySQL container")
    container = names[0]
    info = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
    if "library" in container.lower() or (info["Config"].get("Labels") or {}).get("com.docker.compose.project"):
        raise RuntimeError("Refusing an application Compose container")
    if "MYSQL_ROOT_PASSWORD=ci-disposable-root-password" not in info["Config"]["Env"]:
        raise RuntimeError("Expected the disposable CI service")
    def admin(sql):
        process = subprocess.run(["docker", "exec", "-i", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --default-character-set=utf8mb4'],
            input=sql, text=True, capture_output=True)
        if process.returncode: raise RuntimeError(process.stderr[:200])
    for acceptance in (mysql_acceptance, mysql_permission_acceptance):
        schema = "library_transfer_trial_" + uuid.uuid4().hex[:20]
        admin("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4;")
        try:
            db = correction.CorrectionDatabase(container, schema)
            if acceptance is mysql_acceptance: acceptance(db)
            else:
                class FixtureUserDatabase(Database):
                    def __init__(self, username):
                        super().__init__(container, schema); self.username = username
                    def run(self, sql, xml=False):
                        flags = " --xml" if xml else " --batch --skip-column-names"
                        command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u ' + shlex.quote(self.username) + ' --default-character-set=utf8mb4' + flags + ' -D ' + shlex.quote(self.schema)
                        process = subprocess.run(["docker", "exec", "-i", self.container, "sh", "-c", command], input=sql, text=True, capture_output=True)
                        if process.returncode: raise RuntimeError("Database operation failed: " + process.stderr[:300])
                        return process.stdout
                acceptance(db, FixtureUserDatabase, "ci-disposable-root-password")
        finally: admin("DROP DATABASE " + schema + ";")


if __name__ == "__main__":
    with_mysql = "--mysql" in sys.argv
    if with_mysql: sys.argv.remove("--mysql")
    suite = unittest.main(exit=False)
    if not suite.result.wasSuccessful(): raise SystemExit(1)
    if with_mysql: ci_mysql()
