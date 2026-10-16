"""CI-only MySQL import acceptance using synthetic records, never a live snapshot."""
import copy
import json
import os
from pathlib import Path
import subprocess
import uuid
from apply_huiben_migration import Database, build_sql, verify_preserved


def run():
    if os.environ.get("CI") != "true" or os.environ.get("LIBRARY_TRANSFER_ACCEPTANCE") != "1":
        raise SystemExit("Run only in an isolated CI job with LIBRARY_TRANSFER_ACCEPTANCE=1")
    names = subprocess.check_output(["docker", "ps", "--filter", "ancestor=mysql:8.0.44", "--format", "{{.Names}}"], text=True).splitlines()
    if len(names) != 1: raise RuntimeError("Expected one disposable CI MySQL container")
    container = names[0]
    info = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
    if "library" in container.lower() or (info["Config"].get("Labels") or {}).get("com.docker.compose.project"):
        raise RuntimeError("Refusing an application Compose container")
    if "MYSQL_ROOT_PASSWORD=ci-disposable-root-password" not in info["Config"]["Env"]:
        raise RuntimeError("Expected the disposable CI service")
    schema = "library_transfer_trial_" + uuid.uuid4().hex[:20]
    def admin(sql):
        result = subprocess.run(["docker", "exec", "-i", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --default-character-set=utf8mb4'], input=sql, text=True, capture_output=True)
        if result.returncode: raise RuntimeError(result.stderr[:200])
    admin("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4;")
    try:
        db = Database(container, schema)
        root = Path(__file__).resolve().parent.parent
        for migration in sorted((root / "backend/src/main/resources/db/migration").glob("V*.sql"),
                                key=lambda path: int(path.name[1:].split("__", 1)[0])):
            db.run(migration.read_text(encoding="utf-8"))
        db.run("INSERT INTO sys_user(username,password,real_name,role) VALUES ('existing_admin','preserved-hash','原管理员','ADMIN');"
               "INSERT INTO book(isbn,title,author,publisher,stock_total,stock_available) VALUES ('fixture-001','测试绘本','作者','出版社',2,1);"
               "INSERT INTO borrow_record(user_id,book_id,borrow_date,due_date,status) VALUES (1,1,'2026-01-01','2026-12-01','BORROWING');")
        before = db.inventory()
        members = [{"uid": str(i), "username": "huiben_" + str(i), "name": "测试'会员" + str(i), "phone": "13800000000",
            "card_no": None, "plan_name": "年卡", "service_start": "2026-01-01", "service_end": "2027-01-01", "used_uses": 1,
            "total_uses": None, "deposit_amount": "200.00", "deposit_status": "已缴 / 未退", "paid_fee": "100.00", "registered_at": "2026-01-01 10:00:00", "details": {"备注": "保留引号 ' 和文字 <script>"}} for i in (101, 102)]
        payload = {"batch_key": "synthetic-ci", "source_hash": "a" * 64,
            "summary": {"activeLoans": 1, "loanRecords": 2}, "members": members,
            "books": [{"isbn": "fixture-001", "title": "测试绘本", "author": "作者", "publisher": "出版社", "stock_total": 2, "stock_available": 1,
                "price": "20.00", "created_at": "2026-01-01 10:00:00", "cover_path": "/uploads/book-covers/synthetic.jpg"}],
            "copies": [{"barcode": code, "isbn": "fixture-001", "status": state, "location": "", "details": {"书籍名称": "测试绘本", "录入时间": "2026-01-01 10:00:00"}} for code, state in (("000001", "AVAILABLE"), ("000002", "BORROWED"))],
            "loans": [{"source_key": "huiben:101:1", "uid": "101", "barcode": "000001", "borrowed_at": "2026-01-02 10:00:00", "returned_at": "2026-01-03 10:00:00", "due_date": None, "due_date_kind": "UNKNOWN", "status": "RETURNED"},
                      {"source_key": "huiben:102:1", "uid": "102", "barcode": "000002", "borrowed_at": "2026-01-04 10:00:00", "returned_at": None, "due_date": None, "due_date_kind": "UNLIMITED", "status": "BORROWING"}],
            "orders": [{"id": "1", "uid": "102", "name": "测试会员", "status": "配书未还", "created_at": "2026-01-04 10:00:00", "details": {"借阅内容": "测试绘本"}}],
            "plans": [{"id": "1", "name": "年卡", "details": {"次数": "不限", "押金": "200.00"}}]}
        # Test-only hashes; no real accounts or personal data enter CI.
        hashes = ["$2a$12$" + char * 53 for char in ("a", "b")]
        sql = build_sql(payload, before, hashes, commit=True)
        failed = sql.replace("DELETE FROM migration_check WHERE id = 1;", "INSERT INTO migration_check (id,ok) VALUES (9,0);")
        try: db.run(failed)
        except RuntimeError: pass
        else: raise AssertionError("Expected mid-import validation failure")
        assert db.inventory() == before, "A failed transaction changed existing records"
        assert db.rows("SELECT COUNT(*) AS total FROM migration_batch")[0]["total"] == "0"
        db.run(build_sql(payload, before, hashes, commit=False))
        assert db.inventory() == before, "Rollback preview changed data"
        db.run(sql)
        after = db.inventory()
        verify_preserved(payload, before, after)
        assert db.rows("SELECT COUNT(*) AS total FROM legacy_record")[0]["total"] == "6"
        assert db.rows("SELECT COUNT(*) AS total FROM member_profile WHERE total_uses IS NULL AND deposit_amount = 200")[0]["total"] == "2"
        assert db.rows("SELECT barcode FROM book_copy WHERE status='BORROWED'")[0]["barcode"] == "000002"
        assert db.rows("SELECT COUNT(*) AS total FROM borrow_record WHERE source_key LIKE 'huiben:%' AND due_date IS NULL")[0]["total"] == "2"
        try: db.run(sql)
        except RuntimeError: pass
        else: raise AssertionError("Repeated import must fail without duplicating records")
        assert db.inventory() == after
        conflict = copy.deepcopy(payload); conflict["books"][0]["title"] = "另一本书"
        try: build_sql(conflict, before, hashes)
        except ValueError: pass
        else: raise AssertionError("Conflicting ISBN metadata must stop the import")
        print("Import acceptance passed: atomic rollback, preservation, shared ISBN, duplicate phone numbers, leading-zero barcodes, unlimited dates, archive, deposits, repeat prevention.")
    finally:
        admin("DROP DATABASE " + schema + ";")


if __name__ == "__main__": run()
