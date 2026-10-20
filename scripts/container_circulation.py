"""B50-A synthetic counter circulation gate for fresh container acceptance only.

No production mode exists. Every HTTP call and SQL execution checks the exact
private mktemp deployment and Docker resources. Lifecycle checks can only replay
the terminal commands frozen by seed/capture; they cannot create new operations.
"""
from __future__ import annotations

import argparse
import base64
import copy
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.dont_write_bytecode = True
CONTRACT_SHA = "88d5ed9c3da1a5b828558b6d099133c2b2b58bdc7268b7a83401370ff26a032a"
PREFIX = "/api/circulation"
TABLES = ("circulation_request", "circulation_order", "circulation_order_item",
          "book", "book_copy", "borrow_record", "stock_flow")
NO_BODY = object()
MAX_ID = 9007199254740991


def require(value, message="B50-A isolated circulation assertion failed"):
    if not value:
        raise RuntimeError(message)


def packed(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate response property")
            result[key] = value
        return result
    def invalid(_):
        raise ValueError("Invalid JSON constant")
    result = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=pairs, parse_constant=invalid)
    def unicode(value):
        if isinstance(value, str):
            require(not any(0xD800 <= ord(c) <= 0xDFFF for c in value), "Invalid response Unicode")
        elif isinstance(value, list):
            for item in value: unicode(item)
        elif isinstance(value, dict):
            for key, item in value.items(): unicode(key); unicode(item)
    unicode(result)
    return result


def exact(value, fields):
    require(type(value) is dict and set(value) == set(fields.split()), "Frozen circulation fields differ")


def integer(value, zero=False, nullable=False):
    require(value is None and nullable or type(value) is int and (0 if zero else 1) <= value <= MAX_ID,
            "Safe integer required")


def utc(value):
    require(type(value) is str and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value), "UTC seconds required")
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


def date(value, nullable=False):
    require(value is None and nullable or type(value) is str and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value), "ISO date required")
    if value is not None: require(dt.date.fromisoformat(value).isoformat() == value)


def digest(value):
    require(type(value) is str and re.fullmatch("[0-9a-f]{64}", value), "SHA256 digest required")


def item(value, receipt=False):
    fields = "ordinal recordId userId userName bookId bookTitle copyId barcode beforeRecordStatus afterRecordStatus beforeCopyStatus afterCopyStatus copyVersionBefore copyVersionAfter borrowDate dueDate dueDateKind returnDate renewCount"
    exact(value, fields + (" stockFlowId" if receipt else ""))
    for field in ("ordinal", "userId", "bookId"): integer(value[field])
    integer(value["recordId"], nullable=not receipt)
    integer(value["copyId"], nullable=True)
    integer(value["renewCount"], zero=True)
    require((value["userName"] is None or type(value["userName"]) is str) and type(value["bookTitle"]) is str)
    require(value["beforeRecordStatus"] in (None, "BORROWING", "OVERDUE"))
    require(value["afterRecordStatus"] in ("BORROWING", "RETURNED"))
    if value["copyId"] is None:
        require(value["afterRecordStatus"] == "RETURNED" and all(value[k] is None for k in
                ("barcode", "beforeCopyStatus", "afterCopyStatus", "copyVersionBefore", "copyVersionAfter")),
                "Historical no-copy return acquired invented copy data")
    else:
        require(type(value["barcode"]) is str and value["beforeCopyStatus"] in ("AVAILABLE", "BORROWED")
                and value["afterCopyStatus"] in ("AVAILABLE", "BORROWED"))
        integer(value["copyVersionBefore"], zero=True)
        integer(value["copyVersionAfter"])
        require(value["copyVersionAfter"] == value["copyVersionBefore"] + 1, "Copy version must advance once")
    date(value["borrowDate"])
    date(value["dueDate"], nullable=True)
    date(value["returnDate"], nullable=True)
    require(value["dueDateKind"] in ("DATED", "UNLIMITED", "UNKNOWN")
            and (value["dueDate"] is not None) == (value["dueDateKind"] == "DATED"), "Historical due-date meaning changed")
    if receipt: integer(value["stockFlowId"])


def books(values, items, operation):
    require(type(values) is list and len(values) == len({i["bookId"] for i in items}))
    require(len({b["bookId"] for b in values}) == len(values))
    for book in values:
        exact(book, "bookId stockTotal stockAvailable deltaAvailable afterAvailable")
        integer(book["bookId"])
        for field in ("stockTotal", "stockAvailable", "afterAvailable"): integer(book[field], zero=True)
        quantity = sum(i["bookId"] == book["bookId"] for i in items)
        require(type(book["deltaAvailable"]) is int and book["deltaAvailable"] == quantity * (-1 if operation == "BORROW" else 1)
                and book["afterAvailable"] == book["stockAvailable"] + book["deltaAvailable"]
                and 0 <= book["stockAvailable"] <= book["stockTotal"]
                and 0 <= book["afterAvailable"] <= book["stockTotal"], "Stock impact differs")


def preview(value):
    exact(value, "schemaVersion operation businessDate borrower items books activeCountBefore activeCountAfter warnings")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1
            and value["operation"] in ("BORROW", "RETURN") and type(value["items"]) is list
            and 1 <= len(value["items"]) <= (10 if value["operation"] == "BORROW" else 100)
            and type(value["warnings"]) is list)
    date(value["businessDate"])
    for entry in value["items"]: item(entry)
    require([i["ordinal"] for i in value["items"]] == list(range(1, len(value["items"]) + 1)))
    if value["operation"] == "BORROW":
        exact(value["borrower"], "userId userName cardNo status")
        integer(value["borrower"]["userId"])
        require(value["borrower"]["status"] == 1 and (value["borrower"]["userName"] is None or type(value["borrower"]["userName"]) is str)
                and (value["borrower"]["cardNo"] is None or type(value["borrower"]["cardNo"]) is str))
        integer(value["activeCountBefore"], zero=True)
        integer(value["activeCountAfter"])
        require(value["activeCountAfter"] == value["activeCountBefore"] + len(value["items"]) <= 10)
        require(all(i["recordId"] is None and i["userId"] == value["borrower"]["userId"]
                    and i["beforeRecordStatus"] is None and i["afterRecordStatus"] == "BORROWING"
                    and i["beforeCopyStatus"] == "AVAILABLE" and i["afterCopyStatus"] == "BORROWED"
                    and i["dueDateKind"] == "DATED" and i["returnDate"] is None for i in value["items"]))
    else:
        require(value["borrower"] is None and value["activeCountBefore"] is None and value["activeCountAfter"] is None)
        require(all(i["recordId"] is not None and i["beforeRecordStatus"] in ("BORROWING", "OVERDUE")
                    and i["afterRecordStatus"] == "RETURNED" and i["returnDate"] == value["businessDate"] for i in value["items"]))
    books(value["books"], value["items"], value["operation"])


def receipt(value):
    exact(value, "schemaVersion operationKey operation actorId confirmedAt businessDate orderId previewDigest items books")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1 and value["operation"] in ("BORROW", "RETURN"))
    integer(value["actorId"])
    utc(value["confirmedAt"])
    date(value["businessDate"])
    digest(value["previewDigest"])
    integer(value["orderId"], nullable=value["operation"] == "RETURN")
    require((value["orderId"] is None) == (value["operation"] == "RETURN"))
    require(type(value["items"]) is list and value["items"])
    for entry in value["items"]: item(entry, True)
    require(len({i["recordId"] for i in value["items"]}) == len(value["items"])
            and len({i["stockFlowId"] for i in value["items"]}) == len(value["items"]))
    books(value["books"], value["items"], value["operation"])


def view(value, actor=None, key=None):
    exact(value, "schemaVersion operationKey state operation actorId createdAt expiresAt businessDate input previewDigest preview receipt cancelledAt")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1
            and value["state"] in ("PREVIEWED", "COMMITTED", "CANCELLED"))
    integer(value["actorId"])
    require((actor is None or value["actorId"] == actor) and (key is None or value["operationKey"] == key), "Request ownership/key changed")
    created = utc(value["createdAt"])
    if value["input"] is None:
        require(value["state"] == "CANCELLED" and all(value[k] is None for k in
                ("operation", "expiresAt", "businessDate", "previewDigest", "preview", "receipt")))
    else:
        require(value["operation"] == value["input"]["operation"] == value["preview"]["operation"]
                and value["businessDate"] == value["preview"]["businessDate"])
        date(value["businessDate"])
        digest(value["previewDigest"])
        require((utc(value["expiresAt"]) - created).total_seconds() == 900, "Preview must last exactly 15 minutes")
        preview(value["preview"])
        inp, entries = value["input"], value["preview"]["items"]
        if value["operation"] == "BORROW":
            exact(inp, "operation userId items remark")
            integer(inp["userId"])
            require(type(inp["items"]) is list and len(inp["items"]) == len(entries)
                    and value["preview"]["borrower"]["userId"] == inp["userId"])
            for planned, shown in zip(inp["items"], entries):
                exact(planned, "bookId copyId borrowDays")
                for field in ("bookId", "copyId", "borrowDays"): integer(planned[field])
                require(planned["bookId"] == shown["bookId"] and planned["copyId"] == shown["copyId"]
                        and planned["borrowDays"] <= 90
                        and (dt.date.fromisoformat(shown["dueDate"]) - dt.date.fromisoformat(shown["borrowDate"])).days == planned["borrowDays"],
                        "Preview targets/due dates differ from original input")
        else:
            exact(inp, "operation recordIds remark")
            require(inp["recordIds"] == [entry["recordId"] for entry in entries], "Return preview changed explicit record identities")
        require(type(inp["remark"]) is str)
    if value["state"] == "COMMITTED":
        receipt(value["receipt"])
        require(value["receipt"]["actorId"] == value["actorId"]
                and value["receipt"]["operationKey"] == value["operationKey"]
                and value["receipt"]["previewDigest"] == value["previewDigest"]
                and value["receipt"]["books"] == value["preview"]["books"])
        require(len(value["receipt"]["items"]) == len(value["preview"]["items"]))
        for before, after in zip(value["preview"]["items"], value["receipt"]["items"]):
            expected = {k: v for k, v in after.items() if k != "stockFlowId"}
            if value["operation"] == "BORROW": expected["recordId"] = None
            require(expected == before, "Receipt differs from original preview")
    else: require(value["receipt"] is None)
    if value["state"] == "CANCELLED": utc(value["cancelledAt"])
    else: require(value["cancelledAt"] is None)


def order(value, detail=False):
    fields = "orderId userId userName cardNo actorId createdAt businessDate itemCount activeCount returnedCount state remark"
    exact(value, fields + (" items schemaVersion" if detail else ""))
    for field in ("orderId", "userId", "actorId", "itemCount"): integer(value[field])
    for field in ("activeCount", "returnedCount"): integer(value[field], zero=True)
    require(all(value[field] is None or type(value[field]) is str for field in ("userName", "cardNo"))
            and type(value["remark"]) is str)
    require(value["activeCount"] + value["returnedCount"] == value["itemCount"]
            and value["state"] == ("RETURNED" if value["activeCount"] == 0 else "BORROWING" if value["returnedCount"] == 0 else "PARTIALLY_RETURNED"))
    utc(value["createdAt"])
    date(value["businessDate"])
    if detail:
        require(value["schemaVersion"] == 1 and len(value["items"]) == value["itemCount"])
        for entry in value["items"]:
            exact(entry, "ordinal recordId bookId bookTitle copyId barcode status borrowDate dueDate dueDateKind returnDate renewCount")
            for field in ("ordinal", "recordId", "bookId", "copyId"): integer(entry[field])
        require([i["ordinal"] for i in value["items"]] == list(range(1, value["itemCount"] + 1))
                and len({i["recordId"] for i in value["items"]}) == value["itemCount"])
        require(sum(i["status"] in ("BORROWING", "OVERDUE") for i in value["items"]) == value["activeCount"])


def inspect_json(arguments):
    result = subprocess.run(["docker", *arguments], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    return strict_json(result.stdout)


class Owner:
    def __init__(self, base, env_file, root, release, mode):
        self.base, self.env_file, self.root, self.release, self.mode = base, env_file, root, release, mode
        self.path_guard()
        self.env = dict(line.split("=", 1) for line in env_file.read_text(encoding="utf-8").splitlines()
                        if line and not line.startswith("#") and "=" in line)
        self.verify()

    def path_guard(self):
        require(os.environ.get("CI") == "true" or os.environ.get("LIBRARY_ACCEPTANCE") == "1", "Explicit isolated acceptance flag required")
        u = urllib.parse.urlsplit(self.base)
        require(u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1") and u.port in (18089, 18090)
                and not u.username and not u.password and u.path in ("", "/") and not u.query and not u.fragment,
                "Exact localhost acceptance URL required")
        require(self.root.is_dir() and not self.root.is_symlink() and self.root.resolve() == self.root
                and self.root.parent == Path(tempfile.gettempdir()).resolve() and self.root.name.startswith("tmp."),
                "Only fresh mktemp acceptance root is permitted")
        if os.name == "posix":
            require(self.root.stat().st_uid == os.getuid() and stat.S_IMODE(self.root.stat().st_mode) & 0o077 == 0,
                    "Private acceptance root required")
        self.project = "library" if u.port == 18089 else "library-recovery"
        require(self.mode not in ("seed", "capture") or self.project == "library", "Restored deployment is check only")
        expected = self.root / ("install/.env" if u.port == 18089 else "recovery/.env")
        require(self.env_file == expected and self.env_file.is_file() and not self.env_file.is_symlink()
                and self.env_file.resolve() == self.env_file, "Exact private acceptance environment required")
        require(self.release.is_dir() and not self.release.is_symlink() and self.release.resolve() == self.release
                and self.release.parent == self.root / "install/releases" and (self.release / "compose.yaml").is_file()
                and (self.root / "install/current").resolve() == self.release, "Exact installed current release required")
        return u

    def verify(self):
        u = self.path_guard()
        prefix = "172.30.89" if u.port == 18089 else "172.30.90"
        require(self.env.get("FRONTEND_PORT") == str(u.port) and self.env.get("APP_NETWORK_SUBNET") == prefix + ".0/24"
                and self.env.get("FRONTEND_INTERNAL_IP") == prefix + ".10" and self.env.get("ADMIN_USERNAME") == "acceptance_admin"
                and self.env.get("JWT_SECRET"), "Exact isolated circulation settings required")
        names = [self.project + "-" + service + "-1" for service in ("mysql", "backend", "frontend")]
        containers = inspect_json(["inspect", *names])
        require(len(containers) == 3)
        network_name = self.project + "_app"
        for service, name, container in zip(("mysql", "backend", "frontend"), names, containers):
            labels = container["Config"]["Labels"]
            require(container["Name"] == "/" + name and container["State"]["Running"] is True
                    and labels.get("com.docker.compose.project") == self.project
                    and labels.get("com.docker.compose.service") == service
                    and labels.get("com.docker.compose.project.working_dir") == str(self.release)
                    and labels.get("com.docker.compose.project.config_files") == str(self.release / "compose.yaml"),
                    "Circulation container ownership differs")
            mounts = container.get("Mounts", [])
            volumes = {m["Destination"]: m["Name"] for m in mounts if m["Type"] == "volume"}
            binds = [m for m in mounts if m["Type"] == "bind"]
            expected_volumes = {"/var/lib/mysql": self.project + "_mysql-data"} if service == "mysql" else {
                "/app/uploads": self.project + "_uploads", "/app/logs": self.project + "_logs"} if service == "backend" else {}
            require(volumes == expected_volumes and len(mounts) == len(expected_volumes) + (1 if service == "mysql" else 0), "Unexpected circulation data mounts")
            if service == "mysql":
                require(len(binds) == 1 and binds[0]["Source"] == str(self.release / "backend/docker/10-accounts.sh")
                        and binds[0]["Destination"] == "/docker-entrypoint-initdb.d/10-accounts.sh" and binds[0]["RW"] is False)
            else: require(not binds, "Unexpected circulation bind mount")
            runtime = dict(s.split("=", 1) for s in container["Config"].get("Env", []) if "=" in s)
            if service == "backend": require(runtime.get("JWT_SECRET") == self.env["JWT_SECRET"], "Running acceptance JWT configuration differs")
            if service == "mysql":
                require(runtime.get("MYSQL_DATABASE") == self.env.get("MYSQL_DB", "library_db")
                        and runtime.get("MYSQL_MIGRATION_PASSWORD") == self.env.get("MYSQL_MIGRATION_PASSWORD"), "Running acceptance database differs")
            networks = container["NetworkSettings"]["Networks"]
            require(set(networks) == {network_name}, "Unexpected circulation network")
            if service == "frontend":
                require(networks[network_name]["IPAddress"] == prefix + ".10"
                        and container["NetworkSettings"]["Ports"] == {"80/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(u.port)}]}, "Frontend must bind isolated localhost port")
            else: require(not container["HostConfig"].get("PortBindings"), "Backend/MySQL must not publish ports")
        network = inspect_json(["network", "inspect", network_name])
        require(len(network) == 1 and network[0]["Name"] == network_name
                and network[0]["Labels"].get("com.docker.compose.project") == self.project
                and network[0]["Labels"].get("com.docker.compose.network") == "app"
                and len(network[0]["IPAM"]["Config"]) == 1
                and network[0]["IPAM"]["Config"][0]["Subnet"] == prefix + ".0/24"
                and {v["Name"] for v in network[0].get("Containers", {}).values()} == set(names), "Exact three owned network participants required")

    def sql(self, source, write=False):
        self.verify()
        require(not write or self.mode == "seed" and self.project == "library", "Only seed may insert synthetic historical fixtures")
        command = 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names --skip-reconnect "$MYSQL_DATABASE"'
        result = subprocess.run(["docker", "exec", "-i", self.project + "-mysql-1", "sh", "-c", command],
                                input=source.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=60)
        require(len(result.stdout) <= 2097152, "Oversized synthetic SQL result")
        return result.stdout.decode("utf-8", "strict")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args): return None


class Client:
    def __init__(self, owner, allowed_replays=None):
        self.owner, self.allowed_replays = owner, allowed_replays
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.transcript = []

    def call(self, actor, role, path, body=NO_BODY, status=200, reason=None, raw=None, content_type="application/json"):
        require(path.startswith("/api/") and not any(c in path for c in "\r\n"), "Unsafe circulation path")
        self.owner.verify()
        method = "GET" if body is NO_BODY and raw is None else "POST"
        payload = raw if raw is not None else None if body is NO_BODY else packed(body).encode("utf-8")
        if self.allowed_replays is not None and method == "POST":
            require(any(r["actor"] == actor and r["role"] == role and r["path"] == path
                        and r["requestBody"] == payload.decode("utf-8") for r in self.allowed_replays), "Check mode only replays frozen terminal commands")
        headers = {"Content-Type": content_type}
        if actor is not None:
            enc = lambda v: base64.urlsafe_b64encode(v).rstrip(b"=")
            claims = dict(sub=str(actor), role=role, version=0, iat=int(time.time()), exp=int(time.time()) + 180)
            unsigned = enc(b'{"alg":"HS256","typ":"JWT"}') + b"." + enc(packed(claims).encode())
            headers["Authorization"] = "Bearer " + (unsigned + b"." + enc(hmac.new(self.owner.env["JWT_SECRET"].encode(), unsigned, hashlib.sha256).digest())).decode()
        request = urllib.request.Request(self.owner.base + path, payload, headers, method=method)
        try:
            with self.opener.open(request, timeout=60) as response:
                actual, response_raw = response.status, response.read(2097153)
        except urllib.error.HTTPError as error:
            actual, response_raw = error.code, error.read(2097153)
        require(actual == status and len(response_raw) <= 2097152,
                "Unexpected circulation HTTP status/size: expected=" + str(status) + " actual=" + str(actual))
        envelope = strict_json(response_raw)
        exact(envelope, "code message data")
        require(type(envelope["code"]) is int and envelope["code"] == status and type(envelope["message"]) is str)
        if reason is not None:
            exact(envelope["data"], "schemaVersion reasonCode")
            require(envelope["data"]["schemaVersion"] == 1 and envelope["data"]["reasonCode"] == reason, "Circulation refusal reason differs")
        if path.startswith(PREFIX):
            self.transcript.append(dict(actor=actor, role=role, method=method, path=path,
                contentType=content_type, requestBody=None if payload is None else payload.decode("utf-8"),
                status=actual, responseBody=response_raw.decode("utf-8")))
        return envelope["data"]


def endpoint(key, action=None):
    return PREFIX + "/requests/" + key + ("/" + action if action else "")


def new_user(client, marker, suffix, role, enabled=True):
    username = marker + "_" + suffix
    result = client.call(1, "ADMIN", "/api/users", dict(username=username, password=uuid.uuid4().hex,
                         realName="Synthetic Reader", role=role, status=1 if enabled else 0))
    integer(result["id"])
    require(result["username"] == username and result["role"] == role and result["status"] == (1 if enabled else 0))
    return result["id"]


def new_inventory(client, marker):
    categories = client.call(1, "ADMIN", "/api/categories")
    category = next(c["id"] for c in categories if c.get("status") == 1)
    key = str(uuid.uuid4())
    lines = [dict(lineKey=str(uuid.uuid4()), target=dict(bookId=None, newBook=dict(
        isbn=marker + "_" + str(i), title="B50 synthetic " + str(i), categoryId=category), purchaseItemId=None),
        copies=[dict(input="SCANNED", barcode="000" + marker + "_" + str(i) + "_" + str(j), location=None)
                for j in range(2 if i == 0 else 1)]) for i in range(11)]
    body = dict(idempotencyKey=key, source=dict(type="MANUAL", purchaseOrderId=None), reason="B50 synthetic circulation intake", lines=lines)
    result = client.call(1, "ADMIN", "/api/inventory-intakes/previews", body)
    result = client.call(1, "ADMIN", "/api/inventory-intakes/" + str(result["intakeId"]) + "/confirm",
                         dict(idempotencyKey=key, previewDigest=result["previewDigest"]))
    require(len(result["books"]) == 11 and len(result["copies"]) == 12)
    inventory = []
    for line in lines:
        barcode = line["copies"][0]["barcode"]
        copies = [c for c in result["copies"] if c["barcode"] in [v["barcode"] for v in line["copies"]]]
        require(len(copies) == len(line["copies"]) and all(c["bookId"] == copies[0]["bookId"] for c in copies))
        chosen = next(c for c in copies if c["barcode"] == barcode)
        inventory.append(dict(bookId=chosen["bookId"], copyId=chosen["copyId"], barcode=barcode, copies=copies))
    return inventory


def sql_text(value):
    return "CONVERT(UNHEX('" + value.encode("utf-8").hex() + "') USING utf8mb4)"


def legacy_fixtures(owner, marker, member):
    """Explicit invented history; no existing row is modified or backfilled."""
    integer(member)
    require(re.fullmatch("b50_[0-9a-f]{8}", marker))
    statements = ["START TRANSACTION;"]
    for index, kind in enumerate(("DATED", "UNLIMITED", "UNKNOWN")):
        label = marker + "_legacy_" + str(index)
        due = "'2020-01-31'" if kind == "DATED" else "NULL"
        statements += ["INSERT INTO book(isbn,title,stock_total,stock_available,status,deleted) VALUES (" + sql_text(label) + "," + sql_text("B50 synthetic historical " + kind) + ",1,0,1,0);",
            "SET @b50_book=LAST_INSERT_ID();",
            "INSERT INTO borrow_record(user_id,book_id,borrow_date,due_date,renew_count,status,remark,deleted,copy_id,due_date_kind,source_key) VALUES (" + str(member) + ",@b50_book,'2020-01-01'," + due + ",0,'" + ("OVERDUE" if kind == "DATED" else "BORROWING") + "'," + sql_text("B50_SYNTHETIC_ONLY") + ",0,NULL,'" + kind + "'," + sql_text(label) + ");",
            "SELECT @b50_book,LAST_INSERT_ID(),'" + kind + "';"]
    statements.append("COMMIT;")
    rows = [line.split("\t") for line in owner.sql("\n".join(statements), write=True).splitlines()]
    require(len(rows) == 3 and all(len(r) == 3 for r in rows), "Historical synthetic insert differs")
    result = []
    for row, kind in zip(rows, ("DATED", "UNLIMITED", "UNKNOWN")):
        require(row[2] == kind and re.fullmatch("[1-9][0-9]*", row[0]) and re.fullmatch("[1-9][0-9]*", row[1]))
        result.append(dict(bookId=int(row[0]), recordId=int(row[1]), dueDateKind=kind))
    return result


def physical(client, inventory):
    result = {}
    for row in inventory:
        book = client.call(1, "ADMIN", "/api/books/" + str(row["bookId"]))
        copies = client.call(1, "ADMIN", "/api/books/" + str(row["bookId"]) + "/copies")
        result[str(row["bookId"])] = dict(stockTotal=book["stockTotal"], stockAvailable=book["stockAvailable"],
            copies={str(c["id"]): dict(barcode=c["barcode"], status=c["status"], actionVersion=c["actionVersion"]) for c in copies})
    return result


def prepare(client, actor, role, body, requests):
    key = str(uuid.uuid4())
    result = client.call(actor, role, endpoint(key, "preview"), body)
    view(result, actor, key)
    require(result["state"] == "PREVIEWED" and result["input"] == body, "Original input changed")
    require(client.call(actor, role, endpoint(key)) == result and client.call(actor, role, endpoint(key, "preview"), body) == result,
            "Original preview replay changed")
    saved = dict(key=key, actor=actor, role=role, input=body, preview=result)
    requests.append(saved)
    return saved


def commit(client, saved, replays):
    key, actor, role = saved["key"], saved["actor"], saved["role"]
    body = dict(previewDigest=saved["preview"]["previewDigest"])
    result = client.call(actor, role, endpoint(key, "confirm"), body)
    receipt(result)
    current = client.call(actor, role, endpoint(key))
    view(current, actor, key)
    require(current["state"] == "COMMITTED" and current["receipt"] == result and current["preview"] == saved["preview"]["preview"])
    require(client.call(actor, role, endpoint(key, "confirm"), body) == result, "Confirmation replay changed")
    replays.append(copy.deepcopy(client.transcript[-1]))
    saved["receipt"] = result
    return result


def borrow_input(member, inventory):
    return dict(operation="BORROW", userId=member, items=[dict(bookId=r["bookId"], copyId=r["copyId"], borrowDays=1 if i == 0 else 90) for i, r in enumerate(inventory)], remark="B50 synthetic counter loan 📚")


def return_input(record_ids):
    return dict(operation="RETURN", recordIds=record_ids, remark="B50 synthetic explicit return")


def get_order(client, order_id):
    result = client.call(1, "ADMIN", PREFIX + "/orders/" + str(order_id))
    order(result, True)
    return result


def order_links(value, original_receipt):
    require(value["orderId"] == original_receipt["orderId"] and value["actorId"] == original_receipt["actorId"]
            and value["itemCount"] == len(original_receipt["items"])
            and all(i["userId"] == value["userId"] for i in original_receipt["items"]), "Order owner/count differs from original receipt")
    for actual, original in zip(value["items"], original_receipt["items"]):
        require(all(actual[field] == original[field] for field in
                ("ordinal", "recordId", "bookId", "bookTitle", "copyId", "barcode", "borrowDate", "dueDate", "dueDateKind", "renewCount")),
                "Order lost an original explicit record/copy/title/barcode link")


def reject_inputs(client, actor, role, member, inventory):
    good = borrow_input(member, inventory[:1])
    bad = [{**good, "extra": True}, {**good, "items": []}, {**good, "items": good["items"] * 2},
           {**good, "items": [dict(bookId=r["bookId"], copyId=r["copyId"], borrowDays=30) for r in inventory]},
           {**good, "remark": "bad\ncontrol"}, return_input([]), return_input([1, 1]), return_input(list(range(1, 102)))]
    for value in (str(member), float(member), True, 0, MAX_ID + 1): bad.append({**good, "userId": value})
    for value in (0, 91, "30", 30.0, True): bad.append({**good, "items": [{**good["items"][0], "borrowDays": value}]})
    for body in bad: client.call(actor, role, endpoint(str(uuid.uuid4()), "preview"), body, 400, "INVALID_INPUT")
    raw_values = [b'{"operation":"RETURN","operation":"RETURN","recordIds":[1],"remark":""}',
                  packed(good).encode() + b' {}', b'{"operation":"RETURN","recordIds":[1],"remark":"\\ud800"}']
    for raw in raw_values: client.call(actor, role, endpoint(str(uuid.uuid4()), "preview"), status=400, reason="INVALID_INPUT", raw=raw)
    client.call(actor, role, endpoint(str(uuid.uuid4()), "preview"), raw=b" " * 32769, status=413, reason="BODY_TOO_LARGE")
    client.call(actor, role, endpoint(str(uuid.uuid4()), "preview"), good, 400, "INVALID_INPUT", content_type="text/plain")
    client.call(actor, role, endpoint("invalid", "preview"), good, 400, "INVALID_INPUT")
    for query in ("size=0", "size=101", "page=1&page=1", "unknown=1", "userId=true"):
        client.call(actor, role, PREFIX + "/orders?" + query, status=400, reason="INVALID_INPUT")


def columns(owner):
    table_names = ",".join("'" + name + "'" for name in TABLES)
    raw = owner.sql("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN (" + table_names + ") ORDER BY TABLE_NAME,ORDINAL_POSITION;")
    result = {name: [] for name in TABLES}
    for row in raw.splitlines():
        fields = row.split("\t")
        require(len(fields) == 2 and fields[0] in result and re.fullmatch("[a-z][a-z0-9_]*", fields[1]), "Unexpected snapshot column")
        result[fields[0]].append(fields[1])
    require(all(result.values()), "All circulation snapshot tables are required")
    return result


def seed(client, state_path):
    require(not state_path.exists() and not state_path.with_suffix(".started").exists(), "Never reseed circulation lifecycle")
    with state_path.with_suffix(".started").open("xb") as file: file.write(b"B50-A one-shot synthetic seed started\n")
    marker = "b50_" + uuid.uuid4().hex[:8]
    actors = {name: new_user(client, marker, name, role) for name, role in (("admin", "ADMIN"), ("libr", "LIBRARIAN"), ("other", "ADMIN"))}
    members = dict(main=new_user(client, marker, "member", "READER"), historical=new_user(client, marker, "history", "READER", False))
    inventory = new_inventory(client, marker)
    legacy = legacy_fixtures(client.owner, marker, members["historical"])
    initial = physical(client, inventory)
    requests, replays = [], []
    reject_inputs(client, actors["admin"], "ADMIN", members["main"], inventory)
    first = prepare(client, actors["admin"], "ADMIN", borrow_input(members["main"], inventory[:2]), requests)
    require(physical(client, inventory) == initial, "Preview reserved inventory")
    client.call(actors["admin"], "ADMIN", endpoint(first["key"], "confirm"), dict(previewDigest="0" * 64), 409, "IDEMPOTENCY_KEY_REUSED")
    first_receipt = commit(client, first, replays)
    first_order = get_order(client, first_receipt["orderId"])
    order_links(first_order, first_receipt)
    require(first_order["state"] == "BORROWING" and first_order["itemCount"] == 2 and first_order["activeCount"] == 2)
    for planned, returned in zip(first["input"]["items"], first_receipt["items"]):
        require((dt.date.fromisoformat(returned["dueDate"]) - dt.date.fromisoformat(returned["borrowDate"])).days == planned["borrowDays"], "Per-item due date changed")
    duplicate = borrow_input(members["main"], inventory[:1])
    duplicate["items"][0]["copyId"] = next(c["copyId"] for c in inventory[0]["copies"] if c["copyId"] != inventory[0]["copyId"])
    client.call(actors["admin"], "ADMIN", endpoint(str(uuid.uuid4()), "preview"), duplicate, 409, "DUPLICATE_BOOK")
    cap = prepare(client, actors["libr"], "LIBRARIAN", borrow_input(members["main"], inventory[2:10]), requests)
    require(cap["preview"]["preview"]["activeCountBefore"] == 2 and cap["preview"]["preview"]["activeCountAfter"] == 10)
    cap_receipt = commit(client, cap, replays)
    order_links(get_order(client, cap_receipt["orderId"]), cap_receipt)
    client.call(actors["libr"], "LIBRARIAN", endpoint(str(uuid.uuid4()), "preview"), borrow_input(members["main"], inventory[10:]), 409, "BORROW_LIMIT")
    partial = prepare(client, actors["libr"], "LIBRARIAN", return_input([first_receipt["items"][0]["recordId"]]), requests)
    commit(client, partial, replays)
    partial_order = get_order(client, first_receipt["orderId"])
    require(partial_order["state"] == "PARTIALLY_RETURNED" and partial_order["activeCount"] == partial_order["returnedCount"] == 1)
    rest = prepare(client, actors["admin"], "ADMIN", return_input([first_receipt["items"][1]["recordId"]]), requests)
    commit(client, rest, replays)
    require(get_order(client, first_receipt["orderId"])["state"] == "RETURNED")
    batch = prepare(client, actors["libr"], "LIBRARIAN", return_input([r["recordId"] for r in cap_receipt["items"]]), requests)
    commit(client, batch, replays)
    historical = prepare(client, actors["admin"], "ADMIN", return_input([r["recordId"] for r in legacy]), requests)
    historical_receipt = commit(client, historical, replays)
    require({r["dueDateKind"] for r in historical_receipt["items"]} == {"DATED", "UNLIMITED", "UNKNOWN"}
            and all(r["copyId"] is None and r["barcode"] is None and r["userId"] == members["historical"] for r in historical_receipt["items"]), "Historical disabled-member/no-copy return differs")
    client.call(actors["admin"], "ADMIN", endpoint(str(uuid.uuid4()), "preview"), return_input([first_receipt["items"][0]["recordId"]]), 409, "RECORD_NOT_ACTIVE")
    cancelled = prepare(client, actors["admin"], "ADMIN", borrow_input(members["main"], inventory[10:]), requests)
    cancelled_view = client.call(actors["admin"], "ADMIN", endpoint(cancelled["key"], "cancel"), {})
    view(cancelled_view, actors["admin"], cancelled["key"])
    require(cancelled_view["state"] == "CANCELLED")
    replays.append(copy.deepcopy(client.transcript[-1]))
    require(client.call(actors["admin"], "ADMIN", endpoint(cancelled["key"], "preview"), cancelled["input"]) == cancelled_view)
    client.call(actors["admin"], "ADMIN", endpoint(cancelled["key"], "confirm"), dict(previewDigest=cancelled["preview"]["previewDigest"]), 409, "REQUEST_CANCELLED")
    tombstone_key = str(uuid.uuid4())
    tombstone = client.call(actors["libr"], "LIBRARIAN", endpoint(tombstone_key, "cancel"), {})
    view(tombstone, actors["libr"], tombstone_key)
    require(tombstone["input"] is None and tombstone["state"] == "CANCELLED")
    replays.append(copy.deepcopy(client.transcript[-1]))
    requests.append(dict(key=tombstone_key, actor=actors["libr"], role="LIBRARIAN", input=None))
    client.call(actors["libr"], "LIBRARIAN", endpoint(tombstone_key, "preview"), borrow_input(members["main"], inventory[:1]), 409, "REQUEST_CANCELLED")
    committed_view = client.call(actors["admin"], "ADMIN", endpoint(first["key"], "cancel"), {})
    require(committed_view["state"] == "COMMITTED" and committed_view["receipt"] == first_receipt)
    replays.append(copy.deepcopy(client.transcript[-1]))
    client.call(actors["other"], "ADMIN", endpoint(first["key"]), status=403, reason="OWNER_REQUIRED")
    client.call(actors["other"], "ADMIN", endpoint(first["key"], "confirm"), dict(previewDigest=first["preview"]["previewDigest"]), 403, "OWNER_REQUIRED")
    client.call(actors["other"], "ADMIN", endpoint(first["key"], "cancel"), {}, 403, "OWNER_REQUIRED")
    client.call(members["main"], "READER", PREFIX + "/orders", status=403, reason="ROLE_REQUIRED")
    client.call(members["main"], "READER", endpoint(str(uuid.uuid4()), "preview"), borrow_input(members["main"], inventory[:1]), 403, "ROLE_REQUIRED")
    client.call(None, None, PREFIX + "/orders", status=401)
    final = physical(client, inventory)
    for book_id, before in initial.items():
        after = final[book_id]
        require(after["stockTotal"] == after["stockAvailable"] == before["stockTotal"])
        for copy_id, original in before["copies"].items():
            now = after["copies"][copy_id]
            used = any(r["copyId"] == int(copy_id) for r in first_receipt["items"] + cap_receipt["items"])
            require(now == {**original, "actionVersion": original["actionVersion"] + (2 if used else 0)}, "Borrow/return did not preserve exact copy identity and status")
    state = dict(schemaVersion=1, syntheticOnly=True, contractSha256=CONTRACT_SHA, marker=marker,
        actors=actors, members=members, inventory=inventory, legacy=legacy, requests=requests, replays=replays,
        orderIds=[first_receipt["orderId"], cap_receipt["orderId"]], physical=final,
        initialOrder=first_order, partialOrder=partial_order, transcript=client.transcript, columns=columns(client.owner))
    with state_path.open("xb") as file: file.write(encoded(state))
    print("B50-A synthetic seed passed: 2 orders/10 linked loans, partial/batch returns, 3 historical no-copy returns, cancellation and strict permissions")


def capture(client, state, path):
    require(not path.exists(), "Never replace original circulation capture")
    state = copy.deepcopy(state)
    for saved in state["requests"]:
        saved["view"] = client.call(saved["actor"], saved["role"], endpoint(saved["key"]))
        view(saved["view"], saved["actor"], saved["key"])
        require(saved["view"]["input"] == saved["input"])
        if saved["input"] is not None:
            replayed = client.call(saved["actor"], saved["role"], endpoint(saved["key"], "preview"), saved["input"])
            require(replayed == saved["view"], "Terminal preview key replay changed original result")
            state["replays"].append(copy.deepcopy(client.transcript[-1]))
    state["orders"] = {str(i): get_order(client, i) for i in state["orderIds"]}
    require(all(o["state"] == "RETURNED" for o in state["orders"].values()))
    for saved in state["requests"]:
        if saved.get("receipt", {}).get("operation") == "BORROW":
            order_links(state["orders"][str(saved["receipt"]["orderId"])], saved["receipt"])
    state["orderPage"] = client.call(state["actors"]["libr"], "LIBRARIAN", PREFIX + "/orders?userId=" + str(state["members"]["main"]) + "&state=RETURNED&page=1&size=100")
    page = state["orderPage"]
    exact(page, "schemaVersion records total page size pages")
    require(page["schemaVersion"] == 1 and page["total"] == 2 and page["page"] == page["pages"] == 1 and page["size"] == 100
            and {o["orderId"] for o in page["records"]} == set(state["orderIds"]))
    for entry in page["records"]: order(entry)
    state["transcript"] += client.transcript
    with path.open("xb") as file: file.write(encoded(state))
    with path.with_suffix(".sha256").open("x", encoding="ascii") as file: file.write(sha(path.read_bytes()) + "\n")
    print("B50-A exact HTTP request/response capture frozen; no authorization header or credential is saved")


def read_state(path, frozen=True):
    require(path.is_file() and not path.is_symlink(), "Missing private circulation state")
    raw = path.read_bytes()
    if frozen:
        require(path.with_suffix(".sha256").is_file() and not path.with_suffix(".sha256").is_symlink()
                and sha(raw) == path.with_suffix(".sha256").read_text(encoding="ascii").strip(), "Frozen circulation state differs")
    state = strict_json(raw)
    require(state["schemaVersion"] == 1 and state["syntheticOnly"] is True and state["contractSha256"] == CONTRACT_SHA
            and re.fullmatch("b50_[0-9a-f]{8}", state["marker"]), "Circulation state binding differs")
    for value in [*state["actors"].values(), *state["members"].values(), *state["orderIds"]]: integer(value)
    require(len(state["requests"]) == 8 and len({r["key"] for r in state["requests"]}) == 8
            and len(state["replays"]) == (16 if frozen else 9) and set(state["columns"]) == set(TABLES))
    for table, names in state["columns"].items():
        require(names and len(names) == len(set(names)) and all(re.fullmatch("[a-z][a-z0-9_]*", n) for n in names), "Unsafe snapshot columns")
    for saved in state["requests"]:
        require(saved["actor"] in state["actors"].values() and re.fullmatch(r"[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}", saved["key"]))
        if frozen: view(saved["view"], saved["actor"], saved["key"])
    return state


def snapshot_sql(owner, state):
    owner.verify()
    book_ids = [r["bookId"] for r in state["inventory"] + state["legacy"]]
    for value in book_ids: integer(value)
    ids = ",".join(map(str, sorted(book_ids)))
    lines = ["SET time_zone='+00:00'; START TRANSACTION WITH CONSISTENT SNAPSHOT;"]
    for table in TABLES:
        where = "1=1" if table.startswith("circulation_") else ("id" if table == "book" else "book_id") + " IN (" + ids + ")"
        order_by = "operation_key" if table == "circulation_request" else "order_id,ordinal" if table == "circulation_order_item" else "id"
        cells = ",".join("IF(`" + field + "` IS NULL,NULL,HEX(CAST(`" + field + "` AS BINARY)))" for field in state["columns"][table])
        lines += ["SELECT 'count:" + table + "',COUNT(*) FROM `" + table + "` WHERE " + where + ";",
                  "SELECT 'columns:" + table + "',COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + table + "';",
                  "SELECT '" + table + "',SHA2(CAST(JSON_ARRAY(" + cells + ") AS CHAR),256) FROM `" + table + "` WHERE " + where + " ORDER BY " + order_by + ";"]
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


def verify_snapshot(path, state):
    expected = dict(circulation_request=8, circulation_order=2, circulation_order_item=10,
                    book=14, book_copy=12, borrow_record=13, stock_flow=34)
    rows = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines()]
    index = 0
    for table in TABLES:
        require(rows[index:index + 2] == [["count:" + table, str(expected[table])], ["columns:" + table, str(len(state["columns"][table]))]], "Circulation SQL row/column counts differ: " + table)
        index += 2
        selected = rows[index:index + expected[table]]
        require(len(selected) == expected[table] and all(len(r) == 2 and r[0] == table and re.fullmatch("[0-9a-f]{64}", r[1]) for r in selected), "Missing byte-exact circulation snapshot rows")
        index += expected[table]
    require(index == len(rows), "Unexpected circulation snapshot rows")


def check(client, state):
    for saved in state["requests"]:
        actual = client.call(saved["actor"], saved["role"], endpoint(saved["key"]))
        view(actual, saved["actor"], saved["key"])
        require(actual == saved["view"], "Original circulation input/preview/receipt changed")
    for replay in state["replays"]:
        actual = client.call(replay["actor"], replay["role"], replay["path"], raw=replay["requestBody"].encode())
        require(actual == strict_json(replay["responseBody"].encode())["data"], "Original terminal command replay changed")
    for order_id, expected in state["orders"].items():
        require(get_order(client, int(order_id)) == expected, "Original order or live return state changed")
    page = client.call(state["actors"]["libr"], "LIBRARIAN", PREFIX + "/orders?userId=" + str(state["members"]["main"]) + "&state=RETURNED&page=1&size=100")
    require(page == state["orderPage"] and physical(client, state["inventory"]) == state["physical"], "Order list/copy identity/stock changed")
    print("B50-A lifecycle preserved exact requests, 2 orders, 13 returned records, copy versions and replayed receipts")


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    for name in ("env", "root", "release", "state"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--mode", required=True, choices=("guard", "seed", "capture", "check", "snapshot-sql", "verify-snapshot"))
    parser.add_argument("--snapshot", type=Path)
    args = parser.parse_args(argv)
    owner = Owner(args.base, args.env, args.root, args.release, args.mode)
    require(args.state == args.root / "circulation-state.json", "Exact circulation state path required")
    seed_path = args.root / "circulation-seed.json"
    if args.mode == "guard": return
    if args.mode == "seed": seed(Client(owner), seed_path); return
    if args.mode == "capture": capture(Client(owner), read_state(seed_path, False), args.state); return
    state = read_state(args.state)
    if args.mode == "snapshot-sql": print(snapshot_sql(owner, state), end=""); return
    if args.mode == "verify-snapshot":
        require(args.snapshot is not None and args.snapshot.parent == args.root and args.snapshot.is_file() and not args.snapshot.is_symlink(), "Exact private SQL snapshot required")
        verify_snapshot(args.snapshot, state)
        return
    check(Client(owner, state["replays"]), state)


if __name__ == "__main__":
    try: main()
    except RuntimeError as error: raise SystemExit("B50-A isolated circulation gate failed: " + str(error))
    except Exception: raise SystemExit("B50-A isolated circulation gate failed; no private input or credential is printed")
