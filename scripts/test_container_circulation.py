"""Synthetic, Docker-free tests of B50-A acceptance guard and frozen evidence."""
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error

sys.dont_write_bytecode = True
import container_circulation as gate


KEY = "123e4567-e89b-42d3-a456-426614174000"
ORIGINAL_SHELL_SHA = "1ef04765464120c09d3d7f287709fb5b51d1735a80c050b3bf490cfa077b520a"


def sample_request(operation="BORROW", committed=False, no_copy=False, name="Synthetic Reader"):
    returned = operation == "RETURN"
    entry = dict(ordinal=1, recordId=501 if returned else None, userId=101, userName=name,
        bookId=201, bookTitle="Synthetic book", copyId=None if no_copy else 301,
        barcode=None if no_copy else "000Aa ", beforeRecordStatus="OVERDUE" if returned else None,
        afterRecordStatus="RETURNED" if returned else "BORROWING",
        beforeCopyStatus=None if no_copy else "BORROWED" if returned else "AVAILABLE",
        afterCopyStatus=None if no_copy else "AVAILABLE" if returned else "BORROWED",
        copyVersionBefore=None if no_copy else 1 if returned else 0,
        copyVersionAfter=None if no_copy else 2 if returned else 1,
        borrowDate="2026-10-20", dueDate=None if no_copy else "2026-11-19",
        dueDateKind="UNLIMITED" if no_copy else "DATED", returnDate="2026-10-20" if returned else None,
        renewCount=0)
    impact = dict(bookId=201, stockTotal=2, stockAvailable=1 if returned else 2,
                  deltaAvailable=1 if returned else -1, afterAvailable=2 if returned else 1)
    preview = dict(schemaVersion=1, operation=operation, businessDate="2026-10-20",
        borrower=None if returned else dict(userId=101, userName=name, cardNo=None, status=1), items=[entry], books=[impact],
        activeCountBefore=None if returned else 0, activeCountAfter=None if returned else 1, warnings=[])
    receipt = dict(schemaVersion=1, operationKey=KEY, operation=operation, actorId=1,
        confirmedAt="2026-10-20T02:00:00Z", businessDate="2026-10-20", orderId=None if returned else 401,
        previewDigest="a" * 64, items=[{**entry, "recordId": 501, "stockFlowId": 601}], books=[impact])
    inp = gate.return_input([501]) if returned else dict(operation="BORROW", userId=101,
        items=[dict(bookId=201, copyId=301, borrowDays=30)], remark="")
    return dict(schemaVersion=1, operationKey=KEY, state="COMMITTED" if committed else "PREVIEWED", operation=operation,
        actorId=1, createdAt="2026-10-20T01:59:00Z", expiresAt="2026-10-20T02:14:00Z", businessDate="2026-10-20",
        input=inp, previewDigest="a" * 64, preview=preview, receipt=receipt if committed else None, cancelledAt=None)


def sample_order():
    return dict(orderId=401, userId=101, userName=None, cardNo=None, actorId=1,
        createdAt="2026-10-20T02:00:00Z", businessDate="2026-10-20", itemCount=1, activeCount=0, returnedCount=1,
        state="RETURNED", remark="", schemaVersion=1, items=[dict(ordinal=1, recordId=501, bookId=201,
        bookTitle="Synthetic book", copyId=301, barcode="000Aa ", status="RETURNED", borrowDate="2026-10-20",
        dueDate="2026-11-19", dueDateKind="DATED", returnDate="2026-10-20", renewCount=0)])


class GuardOwner:
    base = "http://localhost:18089"
    env = {"JWT_SECRET": "synthetic-test-only"}
    def __init__(self): self.calls = 0
    def verify(self): self.calls += 1


class Response(io.BytesIO):
    def __init__(self, payload, status=200): super().__init__(payload); self.status = status
    def __enter__(self): return self
    def __exit__(self, *args): self.close()


def envelope(data, status=200):
    return gate.encoded(dict(code=status, message="Synthetic response", data=data))


class ProtocolTests(unittest.TestCase):
    def test_frozen_borrow_and_return_examples(self):
        for operation, committed, no_copy, name in (("BORROW", False, False, None),
                ("BORROW", True, False, "Synthetic Reader"), ("RETURN", True, False, None), ("RETURN", True, True, None)):
            with self.subTest(operation=operation, committed=committed, no_copy=no_copy):
                gate.view(sample_request(operation, committed, no_copy, name), 1, KEY)

    def test_historical_null_name_and_due_date_kinds(self):
        for kind, due in (("DATED", "2020-01-31"), ("UNLIMITED", None), ("UNKNOWN", None)):
            value = sample_request("RETURN", True, True, None)
            for entry in (value["preview"]["items"][0], value["receipt"]["items"][0]):
                entry.update(dueDateKind=kind, dueDate=due)
            gate.view(value)

    def test_unknown_cancellation_keeps_explicit_nulls(self):
        value = sample_request()
        value.update(state="CANCELLED", cancelledAt="2026-10-20T02:00:00Z")
        for key in ("operation", "expiresAt", "businessDate", "input", "previewDigest", "preview", "receipt"): value[key] = None
        gate.view(value)
        del value["input"]
        with self.assertRaises(RuntimeError): gate.view(value)

    def test_rejects_shape_identity_and_impact_drift(self):
        mutations = [lambda v: v.update(extra=True), lambda v: v.pop("receipt"), lambda v: v.update(actorId=True),
            lambda v: v.update(expiresAt="2026-10-20T02:14:01Z"), lambda v: v.update(previewDigest="A" * 64),
            lambda v: v["preview"].update(activeCountAfter=11),
            lambda v: v["preview"]["items"][0].update(bookId="201"),
            lambda v: v["preview"]["items"][0].update(copyVersionAfter=2),
            lambda v: v["preview"]["books"][0].update(deltaAvailable=0),
            lambda v: v["preview"]["books"][0].update(afterAvailable=2),
            lambda v: v["receipt"]["items"][0].update(barcode="Aa"),
            lambda v: v["receipt"]["items"][0].update(stockFlowId=None),
            lambda v: v["receipt"].update(previewDigest="b" * 64),
            lambda v: v["receipt"].update(orderId=None),
            lambda v: v["input"]["items"][0].update(copyId=999),
            lambda v: v["input"]["items"][0].update(borrowDays=31)]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                value = sample_request(committed=True)
                mutate(value)
                with self.assertRaises(RuntimeError): gate.view(value, 1, KEY)

    def test_rejects_invented_historical_copy_and_due_date(self):
        for mutate in (lambda i: i.update(barcode="invented"), lambda i: i.update(copyVersionBefore=0),
                       lambda i: i.update(dueDate="2026-11-19"), lambda i: i.update(userName=42)):
            value = sample_request("RETURN", True, True, None)
            mutate(value["preview"]["items"][0])
            with self.assertRaises(RuntimeError): gate.view(value)

    def test_safe_integer_types(self):
        for value in (True, 0, -1, 1.0, "1", gate.MAX_ID + 1):
            with self.subTest(value=value), self.assertRaises(RuntimeError): gate.integer(value)
        gate.integer(None, nullable=True)
        gate.integer(0, zero=True)
        gate.integer(gate.MAX_ID)

    def test_strict_json_rejects_duplicates_nonfinite_and_bad_unicode(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":"\\ud800"}', b'{} {}', b'"\xff"'):
            with self.subTest(raw=raw), self.assertRaises((RuntimeError, ValueError, UnicodeError)): gate.strict_json(raw)

    def test_order_partial_and_returned_state(self):
        value = sample_order()
        gate.order(value, True)
        value.update(state="BORROWING")
        with self.assertRaises(RuntimeError): gate.order(value, True)

    def test_order_requires_original_explicit_links(self):
        original = sample_request(committed=True)["receipt"]
        current = sample_order()
        gate.order_links(current, original)
        for field, value in (("recordId", 502), ("copyId", 302), ("bookId", 202), ("barcode", "Aa"), ("bookTitle", "Changed")):
            changed = copy.deepcopy(current)
            changed["items"][0][field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError): gate.order_links(changed, original)

    def test_return_preview_cannot_replace_explicit_record(self):
        value = sample_request("RETURN", True)
        value["input"]["recordIds"] = [999]
        with self.assertRaises(RuntimeError): gate.view(value)


class HttpTests(unittest.TestCase):
    def client(self, allowed=None):
        owner = GuardOwner()
        client = gate.Client(owner, allowed)
        client.opener = mock.Mock()
        return client, owner

    def test_exact_wire_capture_without_authorization(self):
        client, owner = self.client()
        raw = envelope(sample_request(committed=True))
        client.opener.open.return_value = Response(raw)
        body = dict(previewDigest="a" * 64)
        result = client.call(1, "ADMIN", gate.endpoint(KEY, "confirm"), body)
        self.assertEqual(result["state"], "COMMITTED")
        self.assertEqual(owner.calls, 1)
        captured = client.transcript[0]
        self.assertEqual(captured["responseBody"].encode(), raw)
        self.assertEqual(captured["requestBody"].encode(), gate.packed(body).encode())
        self.assertNotIn("Authorization", gate.packed(captured))
        self.assertNotIn(owner.env["JWT_SECRET"], gate.packed(captured))
        request = client.opener.open.call_args.args[0]
        self.assertEqual(request.get_method(), "POST")
        self.assertTrue(request.headers["Authorization"].startswith("Bearer "))

    def test_check_cannot_invent_or_change_command(self):
        replay = dict(actor=1, role="ADMIN", path=gate.endpoint(KEY, "confirm"), requestBody='{"previewDigest":"' + "a" * 64 + '"}')
        for actor, role, path, body in ((2, "ADMIN", replay["path"], dict(previewDigest="a" * 64)),
                (1, "LIBRARIAN", replay["path"], dict(previewDigest="a" * 64)),
                (1, "ADMIN", gate.endpoint(KEY, "preview"), {}),
                (1, "ADMIN", replay["path"], dict(previewDigest="b" * 64))):
            with self.subTest(actor=actor, role=role, path=path):
                client, _ = self.client([replay])
                with self.assertRaises(RuntimeError): client.call(actor, role, path, body)
                client.opener.open.assert_not_called()

    def test_check_replays_exact_terminal_bytes(self):
        body = '{"previewDigest":"' + "a" * 64 + '"}'
        replay = dict(actor=1, role="ADMIN", path=gate.endpoint(KEY, "confirm"), requestBody=body)
        client, _ = self.client([replay])
        expected = sample_request(committed=True)["receipt"]
        client.opener.open.return_value = Response(envelope(expected))
        self.assertEqual(client.call(1, "ADMIN", replay["path"], raw=body.encode()), expected)

    def test_owner_failure_prevents_http(self):
        client, owner = self.client()
        owner.verify = mock.Mock(side_effect=RuntimeError("ownership differs"))
        with self.assertRaises(RuntimeError): client.call(1, "ADMIN", gate.endpoint(KEY))
        client.opener.open.assert_not_called()

    def test_refusal_status_reason_and_strict_envelope(self):
        data = dict(schemaVersion=1, reasonCode="REQUEST_CANCELLED")
        client, _ = self.client()
        raw = envelope(data, 409)
        client.opener.open.side_effect = urllib.error.HTTPError("synthetic", 409, "conflict", {}, io.BytesIO(raw))
        self.assertEqual(client.call(1, "ADMIN", gate.endpoint(KEY, "confirm"), {}, 409, "REQUEST_CANCELLED"), data)
        client, _ = self.client()
        client.opener.open.return_value = Response(envelope(data, 409), 409)
        with self.assertRaises(RuntimeError): client.call(1, "ADMIN", gate.endpoint(KEY), status=409, reason="PREVIEW_STALE")

    def test_no_redirect_proxy_or_oversized_response(self):
        self.assertIsNone(gate.NoRedirect().redirect_request(None, None, 302, "move", {}, "https://example.invalid"))
        client, _ = self.client()
        client.opener.open.return_value = Response(b" " * 2097153)
        with self.assertRaises(RuntimeError): client.call(1, "ADMIN", gate.endpoint(KEY))

    def test_non_circulation_identity_creation_does_not_capture_password(self):
        client, _ = self.client()
        client.opener.open.return_value = Response(envelope(dict(id=2)))
        client.call(1, "ADMIN", "/api/users", dict(password="synthetic-only"))
        self.assertEqual(client.transcript, [])


class DockerGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tmp.")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / "install/releases/test").mkdir(parents=True)
        (self.root / "install/current").mkdir()
        (self.root / "recovery").mkdir()
        self.release = self.root / "install/releases/test"
        (self.release / "compose.yaml").write_text("synthetic")
        self.env = dict(FRONTEND_PORT="18089", APP_NETWORK_SUBNET="172.30.89.0/24", FRONTEND_INTERNAL_IP="172.30.89.10",
            ADMIN_USERNAME="acceptance_admin", JWT_SECRET="synthetic-only", MYSQL_MIGRATION_PASSWORD="synthetic-only")
        self.env_file = self.root / "install/.env"
        self.write_env()
        original_resolve = Path.resolve
        current = self.root / "install/current"
        release = self.release
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(Path, "resolve", lambda p, *a, **k: release if p == current else original_resolve(p, *a, **k)).start()
        mock.patch.dict(os.environ, {"LIBRARY_ACCEPTANCE": "1", "CI": ""}).start()
        self.containers, self.network = self.inventory()
        self.inspector = mock.patch.object(gate, "inspect_json", side_effect=lambda args: copy.deepcopy(self.network if args[0] == "network" else self.containers)).start()

    def write_env(self):
        self.env_file.write_text("\n".join(k + "=" + v for k, v in self.env.items()), encoding="utf-8")

    def inventory(self):
        project = "library"
        names = [project + "-" + service + "-1" for service in ("mysql", "backend", "frontend")]
        result = []
        for service, name in zip(("mysql", "backend", "frontend"), names):
            volumes = {"/var/lib/mysql": project + "_mysql-data"} if service == "mysql" else {
                "/app/uploads": project + "_uploads", "/app/logs": project + "_logs"} if service == "backend" else {}
            mounts = [dict(Type="volume", Destination=k, Name=v) for k, v in volumes.items()]
            if service == "mysql": mounts.append(dict(Type="bind", Source=str(self.release / "backend/docker/10-accounts.sh"), Destination="/docker-entrypoint-initdb.d/10-accounts.sh", RW=False))
            result.append(dict(Name="/" + name, State=dict(Running=True), Config=dict(Labels={
                "com.docker.compose.project": project, "com.docker.compose.service": service,
                "com.docker.compose.project.working_dir": str(self.release),
                "com.docker.compose.project.config_files": str(self.release / "compose.yaml")},
                Env=["JWT_SECRET=synthetic-only", "MYSQL_DATABASE=library_db", "MYSQL_MIGRATION_PASSWORD=synthetic-only"]),
                Mounts=mounts, HostConfig=dict(PortBindings={} if service != "frontend" else {"80/tcp": []}),
                NetworkSettings=dict(Networks={project + "_app": dict(IPAddress="172.30.89.10" if service == "frontend" else "172.30.89.2")},
                    Ports={"80/tcp": [dict(HostIp="127.0.0.1", HostPort="18089")]} if service == "frontend" else {})))
        network = [dict(Name=project + "_app", Labels={"com.docker.compose.project": project, "com.docker.compose.network": "app"},
            IPAM=dict(Config=[dict(Subnet="172.30.89.0/24")]), Containers={str(i): dict(Name=name) for i, name in enumerate(names)})]
        return result, network

    def owner(self, base="http://localhost:18089", mode="seed"):
        return gate.Owner(base, self.env_file, self.root, self.release, mode)

    def test_exact_owned_private_deployment(self):
        self.assertEqual(self.owner().project, "library")
        self.assertEqual(self.inspector.call_count, 2)

    def test_flag_and_url_guard_before_docker(self):
        with mock.patch.dict(os.environ, {"LIBRARY_ACCEPTANCE": "", "CI": ""}):
            with self.assertRaises(RuntimeError): self.owner()
        for base in ("https://localhost:18089", "http://example.invalid:18089", "http://localhost:80", "http://a@localhost:18089", "http://localhost:18089/api", "http://localhost:18089?x=1", "http://localhost:18089#x"):
            with self.subTest(base=base), self.assertRaises(RuntimeError): self.owner(base)
        self.inspector.assert_not_called()

    def test_ci_flag_is_supported(self):
        with mock.patch.dict(os.environ, {"LIBRARY_ACCEPTANCE": "", "CI": "true"}): self.owner()

    def test_recovered_deployment_cannot_seed_or_capture(self):
        self.env_file = self.root / "recovery/.env"
        self.env.update(FRONTEND_PORT="18090", APP_NETWORK_SUBNET="172.30.90.0/24", FRONTEND_INTERNAL_IP="172.30.90.10")
        self.write_env()
        for mode in ("seed", "capture"):
            with self.subTest(mode=mode), self.assertRaises(RuntimeError): self.owner("http://localhost:18090", mode)
        self.inspector.assert_not_called()

    def test_runtime_ownership_mount_binding_and_network_tamper(self):
        mutations = [lambda c, n: c[0]["Config"]["Labels"].update({"com.docker.compose.project": "production"}),
            lambda c, n: c[1]["Config"]["Labels"].update({"com.docker.compose.project.working_dir": "/srv/library"}),
            lambda c, n: c[0]["Mounts"][0].update(Name="production_mysql"),
            lambda c, n: c[0]["Mounts"][1].update(Source="/srv/library/init.sh"),
            lambda c, n: c[1]["Mounts"].append(dict(Type="bind", Destination="/data", Source="/srv")),
            lambda c, n: c[1]["HostConfig"].update(PortBindings={"8080/tcp": [dict(HostIp="0.0.0.0", HostPort="8080")]}),
            lambda c, n: c[2]["NetworkSettings"]["Ports"]["80/tcp"][0].update(HostIp="0.0.0.0"),
            lambda c, n: c[1]["Config"].update(Env=["JWT_SECRET=other"]),
            lambda c, n: c[0]["Config"].update(Env=["MYSQL_DATABASE=production"]),
            lambda c, n: n[0]["Containers"].update(foreign=dict(Name="other")),
            lambda c, n: n[0]["IPAM"]["Config"][0].update(Subnet="172.31.0.0/16"),
            lambda c, n: c[0]["State"].update(Running=False)]
        for index, mutate in enumerate(mutations):
            self.containers, self.network = self.inventory()
            mutate(self.containers, self.network)
            with self.subTest(mutation=index), self.assertRaises(RuntimeError): self.owner()

    def test_changed_environment_and_private_paths_refused(self):
        self.env["APP_NETWORK_SUBNET"] = "172.31.0.0/16"
        self.write_env()
        with self.assertRaises(RuntimeError): self.owner()
        self.inspector.assert_not_called()
        with mock.patch.object(Path, "is_symlink", return_value=True):
            with self.assertRaises(RuntimeError): self.owner()

    def test_sql_mutation_requires_seed_and_rechecks_owner(self):
        owner = self.owner(mode="check")
        with mock.patch.object(gate.subprocess, "run") as run:
            with self.assertRaises(RuntimeError): owner.sql("INSERT INTO book VALUES (1)", write=True)
            run.assert_not_called()
        owner = self.owner()
        with mock.patch.object(gate.subprocess, "run", return_value=mock.Mock(stdout=b"1\n")) as run:
            self.assertEqual(owner.sql("SELECT 1;"), "1\n")
            self.assertEqual(run.call_args.args[0][:4], ["docker", "exec", "-i", "library-mysql-1"])
            self.assertEqual(run.call_args.kwargs["input"], b"SELECT 1;")
            self.assertNotIn("synthetic-only", " ".join(run.call_args.args[0]))


class EvidenceTests(unittest.TestCase):
    def snapshot_state(self):
        return dict(inventory=[dict(bookId=201 + i) for i in range(11)], legacy=[dict(bookId=301 + i) for i in range(3)],
                    columns={table: ["operation_key", "request_json"] if table == "circulation_request" else ["id", "book_id"] for table in gate.TABLES})

    def test_snapshot_scopes_every_original_cell_and_never_uses_group_concat(self):
        owner = GuardOwner()
        sql = gate.snapshot_sql(owner, self.snapshot_state())
        self.assertEqual(owner.calls, 1)
        self.assertEqual(sql.count("SHA2(CAST(JSON_ARRAY("), 7)
        self.assertIn("HEX(CAST(`request_json` AS BINARY))", sql)
        self.assertIn("FROM `circulation_request` WHERE 1=1 ORDER BY operation_key", sql)
        for table in ("book", "book_copy", "borrow_record", "stock_flow"):
            self.assertRegex(sql, "FROM `" + table + r"` WHERE (?:id|book_id) IN \(201,202,203,204,205,206,207,208,209,210,211,301,302,303\)")
        for forbidden in ("GROUP_CONCAT", "sys_user", "member_finance", "UPDATE ", "INSERT ", "DELETE "):
            self.assertNotIn(forbidden, sql)

    def test_sql_snapshot_detects_missing_extra_or_duplicate_replay_rows(self):
        state = self.snapshot_state()
        counts = [8, 2, 10, 14, 12, 13, 34]
        rows = []
        for table, count in zip(gate.TABLES, counts):
            rows += [["count:" + table, str(count)], ["columns:" + table, "2"]] + [[table, "a" * 64] for _ in range(count)]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "snapshot.tsv"
            def write(value): path.write_text("\n".join("\t".join(r) for r in value) + "\n", encoding="utf-8")
            write(rows)
            gate.verify_snapshot(path, state)
            for changed in (rows[:-1], rows + [["stock_flow", "a" * 64]], [["count:circulation_request", "9"]] + rows[1:],
                            rows[:2] + [["circulation_request", "bad"]] + rows[3:]):
                write(changed)
                with self.assertRaises(RuntimeError): gate.verify_snapshot(path, state)

    def test_historical_fixture_only_inserts_own_new_book_and_loan(self):
        owner = mock.Mock()
        owner.sql.return_value = "301\t501\tDATED\n302\t502\tUNLIMITED\n303\t503\tUNKNOWN\n"
        result = gate.legacy_fixtures(owner, "b50_1234abcd", 101)
        self.assertEqual([r["dueDateKind"] for r in result], ["DATED", "UNLIMITED", "UNKNOWN"])
        sql = owner.sql.call_args.args[0]
        self.assertTrue(owner.sql.call_args.kwargs["write"])
        self.assertEqual(sql.count("INSERT INTO book("), 3)
        self.assertEqual(sql.count("INSERT INTO borrow_record("), 3)
        self.assertIn("'OVERDUE'", sql)
        self.assertIn("NULL,'UNLIMITED'", sql)
        for forbidden in ("UPDATE ", "DELETE ", "DROP ", "TRUNCATE ", "member_finance", "member_profile"):
            self.assertNotIn(forbidden, sql)

    def test_terminal_check_detects_receipt_stock_or_request_drift(self):
        value = sample_request(committed=True)
        request = dict(key=KEY, actor=1, role="ADMIN", view=value)
        replay = dict(actor=1, role="ADMIN", path=gate.endpoint(KEY, "confirm"), requestBody='{"previewDigest":"' + "a" * 64 + '"}', responseBody=envelope(value["receipt"]).decode())
        state = dict(requests=[request], replays=[replay], orders={"401": sample_order()}, actors=dict(libr=2),
                     members=dict(main=101), orderPage={"synthetic": True}, inventory=[], physical={"synthetic": True})
        page_path = gate.PREFIX + "/orders?userId=101&state=RETURNED&page=1&size=100"
        responses = {gate.endpoint(KEY): value, replay["path"]: value["receipt"], gate.PREFIX + "/orders/401": sample_order(), page_path: state["orderPage"]}
        client = mock.Mock()
        client.call.side_effect = lambda actor, role, path, *a, **k: copy.deepcopy(responses[path])
        with mock.patch.object(gate, "physical", return_value=state["physical"]), contextlib.redirect_stdout(io.StringIO()): gate.check(client, state)
        for path in (gate.endpoint(KEY), replay["path"], gate.PREFIX + "/orders/401", page_path):
            original = copy.deepcopy(responses[path])
            responses[path]["unexpected"] = True
            with self.subTest(path=path), mock.patch.object(gate, "physical", return_value=state["physical"]), self.assertRaises(RuntimeError): gate.check(client, state)
            responses[path] = original
        with mock.patch.object(gate, "physical", return_value={"changed": True}), self.assertRaises(RuntimeError): gate.check(client, state)

    def test_capture_preserves_original_raw_input_and_cannot_replace(self):
        value = sample_request(committed=True)
        state = dict(requests=[dict(key=KEY, actor=1, role="ADMIN", input=value["input"])], orderIds=[401, 402],
                     actors=dict(libr=2), members=dict(main=101), replays=[], transcript=[dict(requestBody="original raw input")])
        one, two = sample_order(), sample_order()
        two["orderId"] = 402
        page = dict(schemaVersion=1, records=[{k: v for k, v in o.items() if k not in ("items", "schemaVersion")} for o in (one, two)], total=2, page=1, size=100, pages=1)
        client = mock.Mock(transcript=[dict(responseBody="original response")])
        client.call.side_effect = lambda a, r, p, *body: copy.deepcopy(value if p.startswith(gate.endpoint(KEY)) else page)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.json"
            with mock.patch.object(gate, "get_order", side_effect=[one, two]), contextlib.redirect_stdout(io.StringIO()): gate.capture(client, state, path)
            frozen = gate.strict_json(path.read_bytes())
            self.assertEqual(frozen["transcript"], state["transcript"] + client.transcript)
            self.assertEqual(path.with_suffix(".sha256").read_text().strip(), gate.sha(path.read_bytes()))
            with self.assertRaises(RuntimeError): gate.capture(client, state, path)

    def test_shell_original_bytes_and_all_five_hooks_remain(self):
        raw = Path(__file__).with_name("container-acceptance.sh").read_bytes()
        pattern = rb"(?m)^# BEGIN B50-A CIRCULATION ([A-Z_]+)\n[\s\S]*?^# END B50-A CIRCULATION \1\n"
        original, blocks = re.subn(pattern, b"", raw)
        self.assertEqual(blocks, 6)
        self.assertEqual(hashlib.sha256(original).hexdigest(), ORIGINAL_SHELL_SHA)
        self.assertLess(raw.index(b"# BEGIN B50-A CIRCULATION SEED"), raw.index(b"# BEGIN B34 SETS SEED"))
        for stage in ("INSTALL", "RECREATE", "UPDATE", "FAILED_UPDATE", "RESTORE"):
            segment = re.search(rb"# BEGIN B50-A CIRCULATION " + stage.encode() + rb"\n(.*?)# END B50-A CIRCULATION", raw, re.S).group(1)
            self.assertIn(b" check\n", segment)
            self.assertIn((" " + stage.lower() + "\n").encode(), segment)
            self.assertEqual(segment.count(b"library_circulation_acceptance"), 1)


if __name__ == "__main__": unittest.main(verbosity=2)
