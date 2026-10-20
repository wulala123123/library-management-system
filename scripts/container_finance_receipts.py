"""B40-B synthetic HTTP lifecycle gate. Never an operational finance seed tool.

The enclosing container-acceptance.sh owns a fresh mktemp deployment. Every SQL
render and every HTTP POST revalidates exact Docker ownership before proceeding.
The old B40 schema asset/validator is imported read-only after its exact hash is
checked; the sole accepted RecoveryRecord delta produces the pinned schema hash.
"""
from __future__ import annotations
import sys
sys.dont_write_bytecode = True
import argparse, base64, copy, datetime as dt, hashlib, hmac, importlib.util
import json, os, re, stat, subprocess, tempfile, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path

BASE_SHA = "2bd8b8070e809b59929ce970b3c2bed3590d62f5e895d58d9f2a969132e19961"
EFFECTIVE_SHA = "d98bb9a1be36fe841d617f2ffc723ceba92b16fafe4274089c5a600600b3afed"
LEGACY_SHA = "6d79ad84d33423608c7e57875043d73213ef70d5da9367269381bd5bf0f6bfe5"
ASSET_SHA = "08b4f2b744a3f42071415093c3b0f149682cc1c4be051a9fa8e1917676fff9d1"
PREFIX = "/api/member-finance/operations"
ZONE = dt.timezone(dt.timedelta(hours=8))
MUTATING_MODES = {"activation-sql", "seed"}


def require(value, message="B40-B isolated receipt acceptance failed"):
    if not value:
        raise RuntimeError(message)


def packed(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def digest(value):
    # Wire object keys are the frozen ASCII protocol fields, with no JSON floats.
    return sha(json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode())


def strict_json(raw):
    def pairs(items):
        output = {}
        for key, value in items:
            require(key not in output, "Duplicate response property")
            output[key] = value
        return output
    def invalid(value):
        raise ValueError("Invalid JSON constant")
    value = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=pairs, parse_constant=invalid)
    def strings(item):
        if isinstance(item, str):
            require(not any(0xD800 <= ord(c) <= 0xDFFF for c in item), "Invalid response Unicode")
        elif isinstance(item, list):
            for child in item: strings(child)
        elif isinstance(item, dict):
            for key, child in item.items(): strings(key); strings(child)
    strings(value)
    return value


def load_contract(helper_path):
    raw = helper_path.read_bytes().replace(b"\r\n", b"\n")
    require(sha(raw) == LEGACY_SHA, "Original B40 helper differs")
    spec = importlib.util.spec_from_file_location("_b40_original_receipts_schema", helper_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    require(sha(module.SCHEMA_BYTES) == BASE_SHA, "Original protocol asset differs")
    asset_path = Path(__file__).with_name("container_finance_receipts_contract.json")
    asset_raw = asset_path.read_bytes().replace(b"\r\n", b"\n")
    require(sha(asset_raw) == ASSET_SHA, "B40-B contract delta differs")
    asset = strict_json(asset_raw)
    schema = strict_json(module.SCHEMA_BYTES)
    for step in asset["patch"]:
        parts = step["path"].split("/")[1:]
        parent = schema
        for part in parts[:-1]: parent = parent[part]
        key = parts[-1]
        if step["op"] == "test": require(parent[key] == step["value"], "Schema patch precondition")
        elif isinstance(parent, list) and key == "-": parent.append(copy.deepcopy(step["value"]))
        else: require(step["op"] == "add"); parent[key] = copy.deepcopy(step["value"])
    require(sha(encoded(schema)) == EFFECTIVE_SHA and len(schema["$defs"]) == 109, "Effective schema differs")
    require(asset["baseSha256"] == BASE_SHA and asset["effectiveSha256"] == EFFECTIVE_SHA)
    module.DEFS = schema["$defs"]
    return module


def inspect_json(arguments):
    completed = subprocess.run(["docker", *arguments], check=True, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=30)
    return strict_json(completed.stdout)


class Owner:
    def __init__(self, base, env_file, root, release, mode):
        self.base, self.env_file, self.root, self.release, self.mode = base, env_file, root, release, mode
        require(os.environ.get("LIBRARY_ACCEPTANCE") == "1", "Explicit isolated acceptance flag required")
        u = urllib.parse.urlsplit(base)
        require(u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1") and u.port in (18089, 18090)
                and not u.username and not u.password and u.path in ("", "/") and not u.query and not u.fragment,
                "Exact isolated receipt URL required")
        require(root.is_dir() and not root.is_symlink() and root.resolve() == root
                and root.parent == Path(tempfile.gettempdir()).resolve() and root.name.startswith("tmp."),
                "Do not read an environment outside a fresh mktemp root")
        require(env_file == root / ("install/.env" if u.port == 18089 else "recovery/.env")
                and not env_file.is_symlink() and env_file.resolve() == env_file,
                "Do not read an environment outside the acceptance root")
        self.env = dict(line.split("=", 1) for line in env_file.read_text().splitlines()
                        if line and not line.startswith("#") and "=" in line)
        self.verify()

    def verify(self):
        require(os.environ.get("LIBRARY_ACCEPTANCE") == "1", "Explicit isolated acceptance flag required")
        u = urllib.parse.urlsplit(self.base)
        require(u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1") and u.port in (18089, 18090)
                and not u.username and not u.password and u.path in ("", "/") and not u.query and not u.fragment,
                "Exact isolated receipt URL required")
        self.project = "library" if u.port == 18089 else "library-recovery"
        require(self.mode not in MUTATING_MODES or self.project == "library", "Recovered deployment is check only")
        prefix = "172.30.89" if u.port == 18089 else "172.30.90"
        require(self.root.is_dir() and not self.root.is_symlink() and self.root.resolve() == self.root
                and self.root.parent == Path(tempfile.gettempdir()).resolve() and self.root.name.startswith("tmp."),
                "Only mktemp acceptance root is permitted")
        if os.name == "posix":
            require(self.root.stat().st_uid == os.getuid() and stat.S_IMODE(self.root.stat().st_mode) & 0o077 == 0,
                    "Private acceptance root required")
        expected_env = self.root / ("install/.env" if u.port == 18089 else "recovery/.env")
        require(self.env_file == expected_env and self.env_file.is_file() and not self.env_file.is_symlink()
                and self.env_file.resolve() == self.env_file, "Exact acceptance environment path required")
        releases = self.root / "install/releases"
        require(self.release.is_dir() and not self.release.is_symlink() and self.release.parent == releases
                and self.release.resolve() == self.release and (self.release / "compose.yaml").is_file(),
                "Exact installed release root required")
        require((self.root / "install/current").resolve() == self.release, "Current release differs")
        require(self.env.get("FRONTEND_PORT") == str(u.port) and self.env.get("APP_NETWORK_SUBNET") == prefix + ".0/24"
                and self.env.get("FRONTEND_INTERNAL_IP") == prefix + ".10"
                and self.env.get("ADMIN_USERNAME") == "acceptance_admin"
                and self.env.get("LIBRARY_FINANCE_RECEIPTS_ENABLED") == "true", "Exact receipt deployment settings required")
        start = self.env.get("LIBRARY_FINANCE_RECEIPTS_START_DATE", "")
        require(re.fullmatch(r"[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}", start) and dt.date.fromisoformat(start).isoformat() == start,
                "Explicit fixed Shanghai activation date required")
        names = [self.project + "-" + service + "-1" for service in ("mysql", "backend", "frontend")]
        containers = inspect_json(["inspect", *names])
        require(len(containers) == 3, "Exact three acceptance containers required")
        network_name = self.project + "_app"
        for service, name, container in zip(("mysql", "backend", "frontend"), names, containers):
            labels = container["Config"]["Labels"]
            require(container["Name"] == "/" + name and container["State"]["Running"] is True
                    and labels.get("com.docker.compose.project") == self.project
                    and labels.get("com.docker.compose.service") == service
                    and labels.get("com.docker.compose.project.working_dir") == str(self.release)
                    and labels.get("com.docker.compose.project.config_files") == str(self.release / "compose.yaml"),
                    "Receipt container ownership differs")
            mounts = container.get("Mounts", [])
            volumes = {m["Destination"]: m["Name"] for m in mounts if m["Type"] == "volume"}
            binds = [m for m in mounts if m["Type"] == "bind"]
            expected_volumes = {"/var/lib/mysql": self.project + "_mysql-data"} if service == "mysql" else {
                "/app/uploads": self.project + "_uploads", "/app/logs": self.project + "_logs"} if service == "backend" else {}
            require(volumes == expected_volumes and len(mounts) == len(expected_volumes) + (1 if service == "mysql" else 0), "Unexpected receipt data mounts")
            if service == "mysql":
                require(len(binds) == 1 and binds[0]["Source"] == str(self.release / "backend/docker/10-accounts.sh")
                        and binds[0]["Destination"] == "/docker-entrypoint-initdb.d/10-accounts.sh" and binds[0]["RW"] is False,
                        "MySQL initialization mount differs")
            else:
                require(not binds, "Unexpected receipt bind mount")
            runtime_env = dict(item.split("=", 1) for item in container["Config"].get("Env", []) if "=" in item)
            if service == "backend":
                require(runtime_env.get("JWT_SECRET") == self.env.get("JWT_SECRET")
                        and runtime_env.get("LIBRARY_FINANCE_RECEIPTS_ENABLED") == "true"
                        and runtime_env.get("LIBRARY_FINANCE_RECEIPTS_START_DATE") == start,
                        "Running receipt configuration differs")
            if service == "mysql":
                require(runtime_env.get("MYSQL_DATABASE") == self.env.get("MYSQL_DB", "library_db")
                        and runtime_env.get("MYSQL_MIGRATION_PASSWORD") == self.env.get("MYSQL_MIGRATION_PASSWORD"),
                        "Running acceptance database differs")
            networks = container["NetworkSettings"]["Networks"]
            require(set(networks) == {network_name}, "Unexpected acceptance container network")
            if service == "frontend":
                require(networks[network_name]["IPAddress"] == prefix + ".10"
                        and container["NetworkSettings"]["Ports"] == {"80/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(u.port)}]},
                        "Receipt frontend binding differs")
            else:
                require(not container["HostConfig"].get("PortBindings"), "Backend/MySQL must not publish ports")
        network = inspect_json(["network", "inspect", network_name])
        require(len(network) == 1 and network[0]["Name"] == network_name
                and network[0]["Labels"].get("com.docker.compose.project") == self.project
                and network[0]["Labels"].get("com.docker.compose.network") == "app"
                and len(network[0]["IPAM"]["Config"]) == 1
                and network[0]["IPAM"]["Config"][0]["Subnet"] == prefix + ".0/24",
                "Receipt network ownership differs")
        require({item["Name"] for item in network[0].get("Containers", {}).values()} == set(names),
                "Only the three owned containers may share the acceptance network")


def validate_receipt(receipt, operation):
    request = operation["request"]
    inp = request["input"]
    require(receipt["requestId"] == request["requestId"] and receipt["operationKey"] == operation["operationKey"]
            and receipt["memberId"] == operation["memberId"] and receipt["accountId"] == request["accountId"]
            and receipt["fund"] == inp["fund"] and receipt["currency"] == "CNY" and receipt["action"] == "RECEIPT"
            and receipt["previewDigest"] == request["previewDigest"]
            and receipt["policyDigest"] == request["preview"]["policy"]["digest"], "Receipt binding differs")
    audit = receipt["audit"]
    require(audit["requestId"] == receipt["requestId"] and audit["accountId"] == receipt["accountId"]
            and audit["operatorId"] == operation["actorId"] and audit["beforeRevision"] == receipt["beforeRevision"]
            and audit["afterRevision"] == receipt["afterRevision"]
            and int(receipt["afterRevision"]) == int(receipt["beforeRevision"]) + 1, "Audit binding differs")
    for phase, revision in (("before", receipt["beforeRevision"]), ("after", receipt["afterRevision"])):
        balance = audit[phase]
        require(balance["memberId"] == receipt["memberId"] and balance["accountId"] == receipt["accountId"]
                and balance["fund"] == receipt["fund"] and balance["currency"] == "CNY"
                and balance["financeRevision"] == revision, "Audit balance binding differs")
    require(len(receipt["entriesAtCommit"]) == 1, "Exactly one receipt entry required")
    entry = receipt["entriesAtCommit"][0]
    require(entry["requestId"] == receipt["requestId"] and entry["accountId"] == receipt["accountId"]
            and entry["ordinal"] == 1 and entry["kind"] == "RECEIPT" and entry["amount"] == inp["payload"]["amount"]
            and entry["rootFactKey"] == inp["payload"]["factKey"] and entry["businessRef"] == inp["payload"]["businessRef"]
            and entry["actualAt"] == inp["payload"]["actualAt"] and entry["methodCode"] == inp["payload"]["methodCode"]
            and entry["operatorId"] == operation["actorId"] and entry["recordedAt"] == receipt["confirmedAt"] == audit["recordedAt"]
            and entry["policyDigest"] == receipt["policyDigest"]
            and entry["allocations"] == [] and receipt["entitlementEffect"] == "NONE"
            and receipt["impact"]["cashExecution"] == "NONE", "Entry binding differs")


def validate_operation(operation, actor, key=None, member=None):
    if operation is None: return
    require(operation["actorId"] == actor and (key is None or operation["operationKey"] == key)
            and (member is None or operation["memberId"] == member), "Operation binding differs")
    inp, request = operation["input"], operation["request"]
    if inp is not None:
        require(inp["memberId"] == operation["memberId"] and inp["payload"]["businessRef"]["id"] == inp["payload"]["factKey"],
                "Input binding differs")
        require(operation["draftHash"] == digest(dict(schemaVersion=1, actorId=actor, memberId=operation["memberId"],
                operationKey=operation["operationKey"], canonicalInput=inp)), "Frozen input hash differs")
    if request is not None:
        require(request["operationId"] == operation["operationId"] and request["operationKey"] == operation["operationKey"]
                and request["memberId"] == operation["memberId"] and request["input"] == inp
                and request["fund"] == inp["fund"] and request["currency"] == inp["currency"] and request["action"] == inp["action"]
                and request["inputHash"] == operation["draftHash"] and request["previewDigest"] == request["preview"]["previewDigest"]
                and request["previewDigest"] == operation["request"]["previewDigest"]
                and request["state"] == operation["state"] and request["result"] == operation["result"], "Request binding differs")
        require(request["preview"]["inputHash"] == operation["draftHash"]
                and request["preview"]["identity"]["memberId"] == operation["memberId"], "Preview binding differs")
        preview = request["preview"]
        for balance in (preview["impact"]["before"], preview["impact"]["after"]):
            require(balance["memberId"] == operation["memberId"] and balance["fund"] == inp["fund"] and balance["currency"] == "CNY", "Preview balance binding differs")
        require(preview["impact"]["before"]["accountId"] == request["accountId"], "Preview account binding differs")
        policy = preview["policy"]
        require(policy["digest"] == digest({k: v for k, v in policy.items() if k != "digest"}), "Policy hash differs")
    result = operation["result"]
    if result is not None and result["receipt"] is not None:
        validate_receipt(result["receipt"], operation)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args): return None


class Client:
    def __init__(self, owner, contract, allowed_replays=None):
        self.owner, self.contract, self.allowed_replays = owner, contract, allowed_replays
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.calls = 0

    def token(self, actor, role):
        enc = lambda value: base64.urlsafe_b64encode(value).rstrip(b"=")
        payload = dict(sub=actor, role=role, version=0, iat=int(time.time()), exp=int(time.time()) + 180)
        unsigned = enc(b'{"alg":"HS256","typ":"JWT"}') + b"." + enc(packed(payload).encode())
        return (unsigned + b"." + enc(hmac.new(self.owner.env["JWT_SECRET"].encode(), unsigned, hashlib.sha256).digest())).decode()

    def call(self, actor, path, definition, body=None, status=200, reason=None, role="ADMIN", key=None, member=None, raw=None):
        require(path.startswith("/api/") and not any(c in path for c in "\r\n"), "Unsafe receipt path")
        method = "POST" if body is not None or raw is not None else "GET"
        if isinstance(body, dict):
            key = key or body.get("operationKey")
            member = member or body.get("memberId") or body.get("input", {}).get("memberId")
        if method == "POST":
            self.owner.verify()
            if self.allowed_replays is not None:
                require(raw is None and any(item["actor"] == actor and item["path"] == path and item["body"] == body
                        for item in self.allowed_replays), "Check mode only replays frozen receipts")
        headers = {"Content-Type": "application/json"}
        if actor is not None: headers["Authorization"] = "Bearer " + self.token(actor, role)
        request = urllib.request.Request(self.owner.base + path, raw if raw is not None else None if body is None else packed(body).encode(), headers, method=method)
        try:
            with self.opener.open(request, timeout=60) as response:
                actual, payload = response.status, response.read(2097153)
        except urllib.error.HTTPError as error:
            actual, payload = error.code, error.read(2097153)
        require(actual == status and len(payload) <= 2097152,
                "Unexpected receipt HTTP status or size: " + method + " expected=" + str(status) + " actual=" + str(actual))
        envelope = strict_json(payload)
        self.calls += 1
        if definition == "UserEnvelope":
            require(set(envelope) == {"code", "message", "data"} and envelope["code"] == 200, "User envelope differs")
            return envelope["data"]
        self.contract.check_definition(definition, envelope)
        require(envelope["code"] == status, "Receipt envelope status differs")
        data = envelope["data"]
        if definition == "ErrorEnvelope":
            require(data["errorCode"] == reason, "Receipt refusal differs")
            if status in (401, 403):
                require(all(data[field] is None for field in ("operationKey", "requestId", "state", "operation")) and data["fieldErrors"] == [], "Unsafe permission error")
            if data["operation"] is not None: validate_operation(data["operation"], actor, key, member)
        elif definition in ("OperationEnvelope", "ActiveEnvelope"):
            validate_operation(data, actor, key, member)
        elif definition == "CancelEnvelope":
            require(body is not None and data["operationKey"] == body["operationKey"] and data["memberId"] == body["memberId"]
                    and data["cancelKey"] == body["cancelKey"] and data["previewDigest"] == body["previewDigest"]
                    and data["reason"] == body["reason"] and data["cancelHash"] == digest(body), "Cancel intent binding differs")
        elif definition == "FinanceEnvelope":
            require(member is None or data["memberId"] == member, "Finance target binding differs")
        return data


def endpoint(key, action=None):
    return PREFIX + "/by-key/" + key + ("/" + action if action else "")


def stable_operation(value):
    result = copy.deepcopy(value)
    if result is not None: result.pop("serverNow")
    return result


def stable_finance(value):
    result = copy.deepcopy(value)
    result.pop("asRecordedAt")
    for account in result["accounts"]: account.pop("asRecordedAt")
    return result


def new_user(client, marker, suffix, role):
    username = marker + "_" + suffix
    require(len(username) <= 20)
    user = client.call("1", "/api/users", "UserEnvelope", dict(username=username, password=uuid.uuid4().hex,
                       realName="Synthetic Ledger", role=role, status=1))
    require(type(user["id"]) is int and user["id"] > 0 and user["username"] == username and user["role"] == role and user["status"] == 1)
    return str(user["id"])


def reserve(client, actor, member):
    body = dict(schemaVersion=1, operationKey=str(uuid.uuid4()), memberId=member, editorId=str(uuid.uuid4()))
    operation = client.call(actor, PREFIX, "OperationEnvelope", body, key=body["operationKey"], member=member)
    require(operation["state"] == "RESERVED" and operation["editor"] == dict(editorId=body["editorId"], epoch="1"))
    return operation, body


def finance(client, actor, member):
    return client.call(actor, "/api/members/" + member + "/finance", "FinanceEnvelope", member=member)


def draft(client, actor, operation, fund, method, amount, fact=None):
    summary = finance(client, actor, operation["memberId"])
    account = next(a for a in summary["accounts"] if a["fund"] == fund)
    fact = fact or str(uuid.uuid4())
    inp = dict(memberId=operation["memberId"], memberRevision=summary["memberRevision"], financeRevision=account["financeRevision"],
        fund=fund, currency="CNY", reason="B40B isolated synthetic receipt", evidenceRefs=[dict(type="VERIFICATION_NOTE", reference="B40B_SYNTHETIC_ONLY", digest=None)],
        action="RECEIPT", payload=dict(factKey=fact, amount=amount, actualAt=operation["serverNow"], methodCode=method, paymentState="RECORDED",
        businessRef=dict(type="COUNTER_REGISTRATION", id=fact)))
    body = dict(schemaVersion=1, operationKey=operation["operationKey"], editor=operation["editor"], input=inp)
    saved = client.call(actor, endpoint(operation["operationKey"], "draft"), "OperationEnvelope", body, key=operation["operationKey"], member=operation["memberId"])
    require(saved["state"] == "DRAFT_READY" and saved["request"] is None and saved["draftHash"] is not None)
    return saved, body


def preview(client, actor, operation):
    body = dict(schemaVersion=1, operationKey=operation["operationKey"], editor=operation["editor"], draftHash=operation["draftHash"])
    saved = client.call(actor, endpoint(operation["operationKey"], "preview"), "OperationEnvelope", body, key=operation["operationKey"], member=operation["memberId"])
    require(saved["state"] == "PREVIEWED" and saved["request"]["input"] == operation["input"])
    return saved


def confirm_body(operation):
    return dict(schemaVersion=1, operationKey=operation["operationKey"], editor=operation["editor"], previewDigest=operation["request"]["previewDigest"])


def seed(client, state_path, activation, contract):
    require(not state_path.exists() and not state_path.with_suffix(".started").exists(), "Never reseed receipt lifecycle")
    with state_path.with_suffix(".started").open("xb") as file: file.write(b"B40B one-shot synthetic seed started\n")
    require(activation["policyKey"] == "B40_RECEIPT_V1" and activation["activationDate"] == client.owner.env["LIBRARY_FINANCE_RECEIPTS_START_DATE"]
            and activation["financialTimeZone"] == "Asia/Shanghai" and activation["cutoffAt"] == cutoff(activation["activationDate"]), "Activation anchor differs")
    marker = "b4b_" + uuid.uuid4().hex[:6]
    actors = {name: new_user(client, marker, name, "ADMIN") for name in ("fee", "deposit", "draft", "preview", "cancel", "missing")}
    members = {name: new_user(client, marker, name, "READER") for name in ("m1", "m2", "m3")}
    require(len(set([*actors.values(), *members.values()])) == 9 and "1" not in [*actors.values(), *members.values()], "Synthetic identities must be distinct newly created users")
    operations, replays = {}, []
    for name, fund, method, amount in (("fee", "MEMBER_FEE", "CASH", "12.30"), ("deposit", "DEPOSIT", "WECHAT", "23.45")):
        actor, member = actors[name], members["m1"]
        operation, reservation = reserve(client, actor, member)
        require(stable_operation(client.call(actor, PREFIX, "OperationEnvelope", reservation, key=operation["operationKey"], member=member)) == stable_operation(operation), "Reserve replay changed")
        operation, _ = draft(client, actor, operation, fund, method, amount)
        operation = preview(client, actor, operation)
        require(operation["request"]["preview"]["canConfirm"] is True and operation["request"]["preview"]["sourceProofDigest"] is None)
        body = confirm_body(operation)
        operation = client.call(actor, endpoint(operation["operationKey"], "confirm"), "OperationEnvelope", body, key=operation["operationKey"], member=member)
        require(operation["state"] == "COMMITTED")
        operations[name] = operation
        replays.append(dict(actor=actor, path=endpoint(operation["operationKey"], "confirm"), body=body, definition="OperationEnvelope", expected=operation))
    # A distinct operation using the same real fact UUID must not create entry 2.
    operation, _ = reserve(client, actors["fee"], members["m1"])
    operation, _ = draft(client, actors["fee"], operation, "MEMBER_FEE", "CASH", "12.30", operations["fee"]["input"]["payload"]["factKey"])
    operation = preview(client, actors["fee"], operation)
    require(operation["request"]["preview"]["canConfirm"] is False and any(b["code"] == "BUSINESS_FACT_EXISTS" for b in operation["request"]["preview"]["blocks"]))
    error = client.call(actors["fee"], endpoint(operation["operationKey"], "confirm"), "ErrorEnvelope", confirm_body(operation), status=409, reason="BUSINESS_FACT_EXISTS", key=operation["operationKey"], member=members["m1"])
    require(error["certainty"] == "SAVED_TERMINAL" and error["operation"]["state"] == "REJECTED")
    operations["duplicate"] = error["operation"]
    # A recoverable nonempty server draft, transferred to a different document.
    operation, _ = reserve(client, actors["draft"], members["m2"])
    operation, original_draft = draft(client, actors["draft"], operation, "MEMBER_FEE", "CASH", "3.21")
    claim = dict(schemaVersion=1, operationKey=operation["operationKey"], memberId=operation["memberId"], editorId=str(uuid.uuid4()), expectedEpoch="1", claimKey=str(uuid.uuid4()))
    operation = client.call(actors["draft"], endpoint(operation["operationKey"], "claim"), "OperationEnvelope", claim, key=operation["operationKey"], member=operation["memberId"])
    require(operation["editor"] == dict(editorId=claim["editorId"], epoch="2"))
    replay = client.call(actors["draft"], endpoint(operation["operationKey"], "claim"), "OperationEnvelope", claim, key=operation["operationKey"], member=operation["memberId"])
    require(stable_operation(replay) == stable_operation(operation))
    client.call(actors["draft"], endpoint(operation["operationKey"], "draft"), "ErrorEnvelope", original_draft, status=409, reason="EDITOR_CHANGED", key=operation["operationKey"], member=operation["memberId"])
    resolve = dict(schemaVersion=1, operationKey=operation["operationKey"], memberId=operation["memberId"])
    operation = client.call(actors["draft"], endpoint(operation["operationKey"], "resolve"), "OperationEnvelope", resolve, key=operation["operationKey"], member=operation["memberId"])
    require(operation["state"] == "DRAFT_READY" and operation["input"] == original_draft["input"])
    operations["draft"] = operation
    # Keep an original nonempty PREVIEWED request; lifecycle checks never expire it.
    operation, _ = reserve(client, actors["preview"], members["m3"])
    operation, _ = draft(client, actors["preview"], operation, "DEPOSIT", "WECHAT", "4.56")
    operations["preview"] = preview(client, actors["preview"], operation)
    # Cancel a preview with its complete immutable intent and preserved input.
    operation, _ = reserve(client, actors["cancel"], members["m1"])
    operation, _ = draft(client, actors["cancel"], operation, "MEMBER_FEE", "CASH", "5.67")
    operation = preview(client, actors["cancel"], operation)
    cancel = dict(schemaVersion=1, operationKey=operation["operationKey"], memberId=operation["memberId"], editor=operation["editor"],
                  cancelKey=str(uuid.uuid4()), previewDigest=operation["request"]["previewDigest"], reason="USER_CANCELLED")
    cancelled = client.call(actors["cancel"], endpoint(operation["operationKey"], "cancel"), "CancelEnvelope", cancel)
    require(cancelled["outcome"] == "CANCELLED" and cancelled["cancelHash"] == digest(cancel))
    operations["cancel"] = client.call(actors["cancel"], endpoint(operation["operationKey"]), "OperationEnvelope", key=operation["operationKey"], member=operation["memberId"])
    replays.append(dict(actor=actors["cancel"], path=endpoint(operation["operationKey"], "cancel"), body=cancel, definition="CancelEnvelope", expected=cancelled))
    # A missing-key cancellation is a tombstone, not an unregistered empty slot.
    missing_key = str(uuid.uuid4())
    cancel = dict(schemaVersion=1, operationKey=missing_key, memberId=members["m2"], editor=dict(editorId=str(uuid.uuid4()), epoch="0"),
                  cancelKey=str(uuid.uuid4()), previewDigest=None, reason="ABANDON_UNSAVED_DRAFT")
    cancelled = client.call(actors["missing"], endpoint(missing_key, "cancel"), "CancelEnvelope", cancel)
    require(cancelled["outcome"] == "CANCELLED" and cancelled["requestId"] is None and cancelled["cancelHash"] == digest(cancel))
    operations["missing"] = client.call(actors["missing"], endpoint(missing_key), "OperationEnvelope", key=missing_key, member=members["m2"])
    require(operations["missing"]["request"] is None and operations["missing"]["input"] is None and operations["missing"]["editor"]["epoch"] == "1")
    replays.append(dict(actor=actors["missing"], path=endpoint(missing_key, "cancel"), body=cancel, definition="CancelEnvelope", expected=cancelled))
    # Strict parsing and permission errors must remain full original envelopes.
    operation = operations["draft"]
    bad = dict(schemaVersion=1, operationKey=operation["operationKey"], memberId=operation["memberId"], unknown=True)
    client.call(actors["draft"], endpoint(operation["operationKey"], "resolve"), "ErrorEnvelope", bad, status=400, reason="UNKNOWN_FIELD")
    raw = ('{"schemaVersion":1,"schemaVersion":1,"operationKey":"' + operation["operationKey"] + '","memberId":"' + operation["memberId"] + '"}').encode()
    client.call(actors["draft"], endpoint(operation["operationKey"], "resolve"), "ErrorEnvelope", status=400, reason="DUPLICATE_FIELD", raw=raw)
    client.call(None, PREFIX + "/active", "ErrorEnvelope", status=401, reason="UNAUTHENTICATED")
    client.call(members["m1"], PREFIX + "/active", "ErrorEnvelope", status=403, reason="ADMIN_REQUIRED", role="READER")
    active = {name: client.call(actor, PREFIX + "/active", "ActiveEnvelope") for name, actor in actors.items()}
    require(active["draft"]["state"] == "DRAFT_READY" and active["preview"]["state"] == "PREVIEWED"
            and all(active[name] is None for name in ("fee", "deposit", "cancel", "missing")))
    state = dict(schemaVersion=1, syntheticOnly=True, effectiveSchemaSha256=EFFECTIVE_SHA, marker=marker,
                 activation=activation, actors=actors, members=members, operations=operations, active=active, replays=replays)
    state["finance"] = stable_finance(finance(client, actors["fee"], members["m1"]))
    for account in state["finance"]["accounts"]:
        expected = "12.30" if account["fund"] == "MEMBER_FEE" else "23.45"
        require(account["openingState"] == "UNKNOWN" and account["balance"]["state"] == "UNKNOWN"
                and account["verifiedPaid"]["state"] == "UNKNOWN" and account["verifiedRefunded"]["state"] == "UNKNOWN"
                and account["newReceipts"] == expected and account["newRefunds"] == "0.00" and account["newNet"] == expected
                and account["financeRevision"] == "1" and account["scopeCutoffAt"] == activation["cutoffAt"])
        require(account["feeHeadroom"] is None if account["fund"] == "DEPOSIT" else account["feeHeadroom"]["state"] == "UNKNOWN")
    with state_path.open("xb") as file: file.write(encoded(state))
    with state_path.with_suffix(".sha256").open("x", encoding="ascii") as file: file.write(sha(state_path.read_bytes()) + "\n")
    print("B40-B synthetic HTTP seed frozen: 2 receipts, 7 operations, 5 requests; schema=" + EFFECTIVE_SHA)


def cutoff(start):
    return dt.datetime.combine(dt.date.fromisoformat(start), dt.time(), ZONE).astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000000Z")


def activation_sql(owner):
    owner.verify()
    start = owner.env["LIBRARY_FINANCE_RECEIPTS_START_DATE"]
    print("SET time_zone='+00:00'; START TRANSACTION; SET @b40b_now=UTC_TIMESTAMP(6);")
    print("INSERT INTO member_finance_activation(policy_key,activation_date,cutoff_at,financial_time_zone,created_at) "
          "SELECT 'B40_RECEIPT_V1','" + start + "',DATE_SUB(CAST('" + start + "' AS DATETIME),INTERVAL 8 HOUR),'Asia/Shanghai',@b40b_now "
          "WHERE DATE(DATE_ADD(@b40b_now,INTERVAL 8 HOUR))='" + start + "' AND NOT EXISTS(SELECT 1 FROM member_finance_activation);")
    print("SELECT JSON_OBJECT('policyKey',policy_key,'activationDate',CAST(activation_date AS CHAR),'cutoffAt',DATE_FORMAT(cutoff_at,'%Y-%m-%dT%H:%i:%s.%fZ'),"
          "'financialTimeZone',financial_time_zone,'createdAt',DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ')) FROM member_finance_activation; COMMIT;")


def read_state(path):
    require(path.is_file() and not path.is_symlink() and sha(path.read_bytes()) == path.with_suffix(".sha256").read_text().strip(), "Frozen receipt state differs")
    state = strict_json(path.read_bytes())
    require(state["schemaVersion"] == 1 and state["syntheticOnly"] is True and state["effectiveSchemaSha256"] == EFFECTIVE_SHA, "Receipt state binding differs")
    require(re.fullmatch(r"b4b_[0-9a-f]{6}", state["marker"]) and len(state["actors"]) == 6 and len(state["members"]) == 3)
    require(all(re.fullmatch(r"[1-9][0-9]{0,18}", value) and int(value) <= 2**63 - 1
                for value in [*state["actors"].values(), *state["members"].values()]), "Receipt state ID differs")
    for name, operation in state["operations"].items():
        require(operation["actorId"] in state["actors"].values() and operation["memberId"] in state["members"].values())
        validate_operation(operation, operation["actorId"])
    return state


def snapshot_sql(owner, state):
    owner.verify()
    actors = ",".join(state["actors"].values())
    members = ",".join(state["members"].values())
    facts = ",".join("UNHEX('" + state["operations"][name]["input"]["payload"]["factKey"].encode().hex() + "')" for name in ("fee", "deposit"))
    proof = ["'guardCount',(SELECT COUNT(*) FROM member_finance_activation)",
             "'operations',(SELECT COUNT(*) FROM member_finance_operation WHERE actor_id IN (" + actors + "))",
             "'requests',(SELECT COUNT(*) FROM member_finance_request WHERE actor_id IN (" + actors + "))",
             "'entries',(SELECT COUNT(*) FROM member_finance_entry WHERE root_fact_key IN (" + facts + "))",
             "'audits',(SELECT COUNT(*) FROM member_finance_audit WHERE operator_id IN (" + actors + "))",
             "'allocations',(SELECT COUNT(*) FROM member_finance_allocation a JOIN member_finance_entry e ON e.id=a.lot_entry_id WHERE e.root_fact_key IN (" + facts + "))",
             "'profiles',(SELECT COUNT(*) FROM member_profile WHERE user_id IN (" + members + "))",
             "'entitlements',(SELECT COUNT(*) FROM member_entitlement_snapshot WHERE user_id IN (" + members + "))"]
    print("SELECT 'b40b-proof',JSON_OBJECT(" + ",".join(proof) + ");")
    for tag, table, columns, where, order in (
        ("b40b-activation", "member_finance_activation", "policy_key activation_date cutoff_at financial_time_zone created_at", "1=1", "policy_key"),
        ("b40b-users", "sys_user", "id username password real_name phone email avatar role status token_version member_revision deleted create_time update_time", "id IN (" + actors + "," + members + ")", "id")):
        cells = ",".join("IF(`" + col + "` IS NULL,NULL,HEX(CAST(`" + col + "` AS BINARY)))" for col in columns.split())
        print("SELECT '" + tag + "',SHA2(CAST(JSON_ARRAY(" + cells + ") AS CHAR),256) FROM `" + table + "` WHERE " + where + " ORDER BY " + order + ";")


def verify_snapshot(path):
    rows = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines()]
    require(rows and len(rows[0]) == 2 and rows[0][0] == "b40b-proof", "Missing receipt SQL proof")
    require(strict_json(rows[0][1].encode()) == dict(guardCount=1, operations=7, requests=5, entries=2, audits=2, allocations=0, profiles=0, entitlements=0), "Receipt SQL counts differ")
    require(len(rows) == 11 and rows[1][0] == "b40b-activation"
            and all(len(row) == 2 and re.fullmatch("[0-9a-f]{64}", row[1]) and row[0] == "b40b-users" for row in rows[2:])
            and re.fullmatch("[0-9a-f]{64}", rows[1][1]), "Receipt byte snapshot differs")


def check(client, state):
    for name, expected in state["operations"].items():
        actor = expected["actorId"]
        actual = client.call(actor, endpoint(expected["operationKey"]), "OperationEnvelope", key=expected["operationKey"], member=expected["memberId"])
        require(stable_operation(actual) == stable_operation(expected), "Frozen operation changed: " + name)
        if expected["request"] is not None:
            request = client.call(actor, "/api/member-finance/requests/" + expected["request"]["requestId"], "RequestEnvelope")
            require(request == expected["request"], "Frozen request/input/receipt changed")
    for name, actor in state["actors"].items():
        require(stable_operation(client.call(actor, PREFIX + "/active", "ActiveEnvelope")) == stable_operation(state["active"][name]), "Active operation changed")
    for replay in state["replays"]:
        actual = client.call(replay["actor"], replay["path"], replay["definition"], replay["body"])
        expected = replay["expected"]
        require(stable_operation(actual) == stable_operation(expected) if replay["definition"] == "OperationEnvelope" else actual == expected, "Frozen command replay changed")
    actual = finance(client, state["actors"]["fee"], state["members"]["m1"])
    require(stable_finance(actual) == state["finance"], "Synthetic receipt balances changed")
    print("B40-B lifecycle check passed: frozen drafts, previews, terminal results, facts, cancellation and UNKNOWN balances; HTTP=" + str(client.calls))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--legacy-helper", required=True, type=Path)
    parser.add_argument("--mode", choices=("guard", "activation-sql", "seed", "snapshot-sql", "verify-snapshot", "check"), required=True)
    parser.add_argument("--activation", type=Path)
    parser.add_argument("--snapshot", type=Path)
    args = parser.parse_args()
    owner = Owner(args.base, args.env, args.root, args.release, args.mode)
    require(args.state == args.root / "receipts-state.json" and args.legacy_helper == args.root / "container_finance_acceptance.py", "Exact receipt state/helper paths required")
    contract = load_contract(args.legacy_helper)
    if args.mode == "guard": return
    if args.mode == "activation-sql": activation_sql(owner); return
    if args.mode == "verify-snapshot": verify_snapshot(args.snapshot); return
    if args.mode == "seed":
        require(args.activation == args.root / "receipts-activation.json")
        seed(Client(owner, contract), args.state, strict_json(args.activation.read_bytes()), contract)
        return
    state = read_state(args.state)
    if args.mode == "snapshot-sql": snapshot_sql(owner, state); return
    check(Client(owner, contract, state["replays"]), state)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        raise SystemExit("B40-B isolated receipt gate failed: " + str(error))
    except Exception:
        raise SystemExit("B40-B isolated receipt gate failed; no private input or credential is printed")
