"""Prepare a private, reconciled Huiben snapshot. No database writes.

Input: official XLSX exports plus the DOM snapshots saved through the browser.
The output directory contains personal data and must stay outside Git/releases.
"""
import argparse
import collections
import concurrent.futures
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from decimal import Decimal

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def read_xlsx(path):
    """Read the exports without Excel type inference on barcodes/phone numbers."""
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            shared = ["".join(si.itertext()) for si in ET.fromstring(z.read("xl/sharedStrings.xml"))]
        sheet = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))
        matrix = []
        for row in sheet.findall("m:sheetData/m:row", NS):
            cells = {}
            for cell in row.findall("m:c", NS):
                column = re.match(r"[A-Z]+", cell.attrib["r"])[0]
                index = 0
                for char in column: index = index * 26 + ord(char) - 64
                kind = cell.get("t")
                value = cell.find("m:v", NS)
                text = value.text if value is not None else None
                if kind == "s" and text is not None: text = shared[int(text)]
                if kind == "inlineStr": text = "".join(cell.find("m:is", NS).itertext())
                cells[index - 1] = text
            matrix.append(cells)
        headers = [matrix[0].get(i) for i in range(max(matrix[0]) + 1)]
        return [dict(zip(headers, [row.get(i) for i in range(len(headers))])) for row in matrix[1:] if any(row.values())]


def load(path): return json.loads(path.read_text(encoding="utf-8"))
def dump(path, value): path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
def text(value): return str(value).strip() if value is not None else ""
def number(value): return int(Decimal(text(value) or "0"))
def money(value): return str(Decimal(text(value) or "0").quantize(Decimal("0.01")))
def query_id(link, key="uid"): return urllib.parse.parse_qs(urllib.parse.urlparse(link).query)[key][0]
def unique(items, key, label):
    found = {}
    for item in items:
        identity = key(item)
        if not identity or identity in found: raise ValueError("Missing or duplicate " + label)
        found[identity] = item
    return found


def prepare(root):
    source = root / "source"
    books = read_xlsx(source / "books.xlsx")
    members = read_xlsx(source / "members.xlsx")
    active = read_xlsx(source / "active-loans.xlsx")
    book_by_code = unique(books, lambda b: text(b["馆内编码"]), "book barcode")
    pages = [r for p in source.glob("books-page-*.json") for r in load(p)]
    covers = {}
    for row in pages:
        match = re.search(r"自编码：\s*(.*?)20\d{2}-\d{2}-\d{2}", row["cells"][1], re.S)
        if not match: raise ValueError("Book page barcode missing")
        code = match[1].strip()
        if code in covers: raise ValueError("Repeated book page")
        covers[code] = row
    if set(covers) != set(book_by_code): raise ValueError("Book pages and export do not match")

    member_rows = load(source / "members-page.json")
    member_by_pair = unique(members, lambda m: (text(m["会员名字"]), text(m["手机号"])), "member")
    member_details = {query_id(m["link"]): m["content"] for m in load(source / "member-details.json")}
    prepared_members, loans = [], []
    for index, page_row in enumerate(member_rows, 1):
        uid = query_id(next(l["href"] for l in page_row["links"] if l["href"].startswith("/users/exportuserbook")))
        name = next(l["text"] for l in page_row["links"] if l["href"].startswith("/users/read?"))
        phone = next(l["text"] for l in page_row["links"] if l["href"].startswith("/users/exportuserbook"))
        row = member_by_pair[(name.strip(), phone.strip())]
        total = None if "总: 不限" in page_row["cells"][7] else number(row["总次数"])
        prepared_members.append({"uid": uid, "username": "huiben_" + uid, "name": name.strip(), "phone": phone.strip(),
            "card_no": row["卡号"], "plan_name": row["借阅计划类型"], "service_start": row["服务开始时间"],
            "service_end": row["服务结束时间"], "used_uses": number(row["已借阅次数"]), "total_uses": total,
            "deposit_amount": money(row["实际缴纳押金"]), "deposit_status": page_row["cells"][3].split()[0] + " / " + text(row["押金"]),
            "paid_fee": money(row["实际缴纳费用"]), "registered_at": row["注册时间"],
            "details": {**row, "缴费状态": page_row["cells"][2], "押金缴纳状态": page_row["cells"][3], "原系统补充资料": member_details[uid]}})
        history_path = source / f"member-history-{index:02}.xlsx"
        history = read_xlsx(history_path) if history_path.exists() else []
        history_count = re.search(r"已:\s*\d+\s*次\s*(\d+)\s*本", page_row["cells"][7])
        if not history_count or len(history) != int(history_count[1]): raise ValueError("Member history export is incomplete")
        for ordinal, loan in enumerate(history, 1):
            if text(loan["姓名"]) != name.strip() or text(loan["手机号"]) != phone.strip():
                raise ValueError("Member history owner mismatch")
            code = text(loan["馆内编码"])
            if code not in book_by_code: raise ValueError("Historical book missing from catalog")
            if text(loan["书籍ISBN"]) != text(book_by_code[code]["ISBN"]): raise ValueError("History ISBN mismatch")
            borrowed, returned = text(loan["借阅时间"]), text(loan["入库时间"])
            dt.datetime.fromisoformat(borrowed)
            if returned and dt.datetime.fromisoformat(returned) < dt.datetime.fromisoformat(borrowed):
                raise ValueError("Return precedes borrow")
            loans.append({"source_key": f"huiben:{uid}:{ordinal}", "uid": uid, "barcode": code,
                "borrowed_at": borrowed, "returned_at": returned or None,
                "status": "RETURNED" if returned else "BORROWING",
                "due_date_kind": "UNKNOWN" if returned else "UNLIMITED", "due_date": None,
                "details": loan})
    if len(prepared_members) != len(members): raise ValueError("Member count mismatch")
    unique(prepared_members, lambda m: m["uid"], "member UID")

    open_loans = unique([l for l in loans if not l["returned_at"]], lambda l: l["barcode"], "active barcode")
    active_codes = unique(active, lambda l: text(l["馆内编码"]), "exported active barcode")
    if set(open_loans) != set(active_codes): raise ValueError("Open history differs from unreturned export")
    members_by_uid = {m["uid"]: m for m in prepared_members}
    for code, loan in open_loans.items():
        row, member = active_codes[code], members_by_uid[loan["uid"]]
        if (text(row["会员名字"]), text(row["手机号"]), text(row["出库时间"])) != (member["name"], member["phone"], loan["borrowed_at"]):
            raise ValueError("Active loan owner/date mismatch")
    active_orders = load(source / "active-orders.json")
    if any(r["cells"][7] != "不限制" for r in active_orders["rows"]):
        raise ValueError("Source has dated loans: map each due date before importing")

    groups = collections.defaultdict(list)
    for book in books:
        code = text(book["馆内编码"])
        if number(book["购买数量"]) != 1: raise ValueError("Multiple stock units share one barcode")
        if number(book["库存"]) != (0 if code in open_loans else 1): raise ValueError("Stock/history discrepancy")
        if book["书籍状态"] != "正常" or book["是否外借"] != "允许外借": raise ValueError("Unsupported book availability")
        isbn = text(book["ISBN"])
        if not re.fullmatch(r"[A-Za-z0-9-]{1,20}", isbn): raise ValueError("Invalid catalog identifier")
        groups[isbn].append(book)
    prepared_books, copies = [], []
    for isbn, rows in groups.items():
        if len({r["书籍名称"] for r in rows}) != 1: raise ValueError("Same ISBN has different titles")
        first = rows[0]
        code = text(first["馆内编码"])
        cover_url = covers[code]["images"][0]
        parsed = urllib.parse.urlparse(cover_url)
        if parsed.scheme != "https" or parsed.hostname != "staticcdn.huiben.cn": raise ValueError("Unrecognized cover host")
        filename = "huiben-" + hashlib.sha256(cover_url.encode()).hexdigest()[:32] + "." + ("png" if parsed.path.lower().endswith(".png") else "jpg")
        prepared_books.append({"isbn": isbn, "title": first["书籍名称"], "author": first["作者姓名"], "publisher": first["出版社"],
            "price": money(first["精装价格"] if Decimal(first["精装价格"] or "0") > 0 else first["平装价格"]),
            "stock_total": len(rows), "stock_available": sum(number(r["库存"]) for r in rows),
            "created_at": first["录入时间"], "cover_url": cover_url, "cover_file": filename,
            "cover_path": "/uploads/book-covers/" + filename})
        for row in rows:
            code = text(row["馆内编码"])
            copies.append({"barcode": code, "isbn": isbn, "status": "BORROWED" if code in open_loans else "AVAILABLE",
                "location": " ".join(filter(None, [row["书架位置"], row["书位号"]])),
                "details": {**row, "原书籍信息": covers[code]["cells"][1], "封面原地址": covers[code]["images"][0]}})

    order_pages = load(source / "orders-pages.json")
    order_details = {query_id(o["link"], "lendid"): o for o in load(source / "order-details.json")}
    orders = []
    for page in order_pages:
        for row in page["rows"]:
            link = next(l["href"] for l in row["links"] if l["text"] == "详情")
            oid, uid = query_id(link, "lendid"), query_id(link)
            if uid not in members_by_uid: raise ValueError("Order refers to missing member")
            detail = order_details[oid]
            fields = dict(zip(page["headers"], row["cells"]))
            fields.pop("操作", None)
            for cells in detail["tables"][0]["rows"]:
                if len(cells) >= 2: fields[cells[0]] = cells[1]
            fields.pop("返 回", None)
            orders.append({"id": oid, "uid": uid, "name": members_by_uid[uid]["name"], "status": row["cells"][6],
                "created_at": row["cells"][4], "details": fields})
    unique(orders, lambda o: o["id"], "order")
    if len(orders) != len(order_details): raise ValueError("Order detail count mismatch")

    plan_page = load(source / "plans-page.json")
    plan_details = {query_id(p["link"], "id"): p for p in load(source / "plan-details.json")}
    plans = []
    for row in plan_page["rows"]:
        if len(row["cells"]) != len(plan_page["headers"]): continue
        pid = query_id(next(l["href"] for l in row["links"] if l["text"] == "编辑"), "id")
        fields = dict(zip(plan_page["headers"], row["cells"]))
        fields.pop("操作", None)
        fields["原计划设置"] = "\n\n".join(text(f["text"]) + "\n当前值：" + ", ".join(text(i["value"]) for i in f["inputs"] if i["type"] not in ("hidden", "password") and (i["type"] not in ("radio", "checkbox") or i["checked"])) for f in plan_details[pid]["fields"])
        name = next((fields[k] for k in fields if "名称" in (k or "")), pid)
        plans.append({"id": pid, "name": name, "details": fields})
    if len(plans) != len(plan_details): raise ValueError("Plan detail count mismatch")

    summary = {"bookKinds": len(prepared_books), "copies": len(copies), "available": sum(b["stock_available"] for b in prepared_books),
        "members": len(prepared_members), "orders": len(orders), "loanRecords": len(loans), "activeLoans": len(open_loans),
        "returnedLoans": len(loans) - len(open_loans), "plans": len(plans),
        "paidDeposits": str(sum(Decimal(m["deposit_amount"]) for m in prepared_members)), "unlimitedDueDates": len(open_loans)}
    manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(source.iterdir()) if p.is_file()}
    source_hash = hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()
    payload = {"batch_key": "huiben-20261015", "source_hash": source_hash, "summary": summary,
        "books": prepared_books, "copies": copies, "members": prepared_members, "loans": loans, "orders": orders, "plans": plans}
    dump(root / "source-manifest.json", {"sha256": source_hash, "files": manifest})
    dump(root / "prepared.json", payload)
    dump(root / "reconciliation.json", {"sourceHash": source_hash, "checksPassed": True, **summary})
    return payload


def download_covers(root, payload):
    folder = root / "covers"
    folder.mkdir(exist_ok=True)
    unique_books = {b["cover_file"]: b for b in payload["books"]}
    def download(book):
        target = folder / book["cover_file"]
        if target.exists() and target.stat().st_size: return
        request = urllib.request.Request(book["cover_url"], headers={"User-Agent": "LibraryDataTransfer/1.0"})
        with urllib.request.urlopen(request, timeout=25) as response:
            if urllib.parse.urlparse(response.url).hostname != "staticcdn.huiben.cn": raise ValueError("Cover redirected outside source CDN")
            content = response.read(5 * 1024 * 1024 + 1)
        if len(content) > 5 * 1024 * 1024 or not content.startswith((b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n", b"GIF8", b"RIFF")):
            raise ValueError("Cover is not an accepted image")
        target.write_bytes(content)
    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        tasks = {pool.submit(download, book): name for name, book in unique_books.items()}
        for task in concurrent.futures.as_completed(tasks):
            try: task.result()
            except Exception as error: failures.append({"file": tasks[task], "error": type(error).__name__})
    dump(root / "cover-downloads.json", {"expected": len(unique_books), "completed": len(unique_books) - len(failures), "failed": failures})
    if failures: raise ValueError(f"{len(failures)} cover downloads failed; retry preserves successful files")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--download-covers", action="store_true")
    args = parser.parse_args()
    payload = prepare(args.snapshot)
    print(json.dumps(payload["summary"], ensure_ascii=False))
    if args.download_covers: download_covers(args.snapshot, payload)
