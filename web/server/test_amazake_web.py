"""Tests for amazake_web.py. Run: python3 -m unittest discover -s web/server -v"""

import gzip
import http.client
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import time
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import amazake_web as aw  # noqa: E402

if not os.environ.get("AMAZAKE_WEB_TEST_LOG"):
    aw.log = lambda line: None  # keep test output readable; set AMAZAKE_WEB_TEST_LOG=1 to see it

MAX_BYTES = 1024 * 1024
INDEX_HTML = b"<!doctype html><title>viewer</title>"


def sample_report(**overrides):
    report = {
        "format": "amazake-profile",
        "version": 1,
        "createdAt": "2026-11-11T16:30:00Z",
        "durationMs": 30000,
        "intervalMs": 1000,
        "server": {"brand": "Amazake", "version": "1.21.11-7-b3a4d61"},
        "system": {"logicalCores": 4, "perCore": True, "perThreadSource": "proc"},
        "samples": [{"t": 1000, "systemCpu": 0.5, "processCpu": 0.25, "tps": 20.0,
                     "msptAvg": 12.5, "msptMax": 30.0, "heapUsedBytes": 123,
                     "gcCount": 0, "gcTimeMs": 0, "players": 1}],
        "threads": [{"name": "Server thread", "group": "main", "cpuMs": 900, "avgCores": 0.9}],
        "groups": [{"group": "main", "threads": 1, "cpuMs": 900, "avgCores": 0.9,
                    "peakCores": 0.95, "series": [0.9]}],
        "lagSpikes": [],
        "note": "Привет",
    }
    report.update(overrides)
    return report


class ServerHarness:
    """Runs AmazakeServer on a free port in a background thread."""

    def __init__(self, **config):
        self.tmp = tempfile.mkdtemp(prefix="amazake-web-test-")
        self.static = os.path.join(self.tmp, "static")
        os.makedirs(os.path.join(self.static, "sub"))
        with open(os.path.join(self.static, "index.html"), "wb") as fh:
            fh.write(INDEX_HTML)
        with open(os.path.join(self.static, "app.js"), "wb") as fh:
            fh.write(b"console.log(1);")
        with open(os.path.join(self.static, "sub", "style.css"), "wb") as fh:
            fh.write(b"body{}")
        with open(os.path.join(self.static, "notes.txt"), "wb") as fh:
            fh.write(b"not served")
        # A secret outside the static dir, with an allowed extension.
        self.secret = os.path.join(self.tmp, "secret.css")
        with open(self.secret, "wb") as fh:
            fh.write(b"SECRET")
        os.symlink(self.secret, os.path.join(self.static, "link.css"))
        os.symlink(self.tmp, os.path.join(self.static, "linkdir"))
        cfg = dict(host="127.0.0.1", port=0, data_dir=os.path.join(self.tmp, "data"),
                   static_dir=self.static, max_bytes=MAX_BYTES, rate_limit=1000,
                   min_free_mb=0)
        cfg.update(config)
        self.server = aw.AmazakeServer(aw.Config(**cfg))
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            resp = conn.getresponse()
            data = resp.read()
            return resp.status, {k.lower(): v for k, v in resp.getheaders()}, data
        finally:
            conn.close()

    def raw(self, data):
        """Send raw bytes, return the status code of the answer."""
        with socket.create_connection(("127.0.0.1", self.port), timeout=10) as sock:
            sock.sendall(data)
            buf = b""
            while b"\r\n" not in buf:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                buf += chunk
        return int(buf.split(b" ", 2)[1])


_ip_counter = [0]


def fresh_ip():
    """A distinct client address per request so the rate limit does not interfere."""
    _ip_counter[0] += 1
    n = _ip_counter[0]
    return "10.%d.%d.%d" % ((n >> 16) & 255, (n >> 8) & 255, n & 255)


def upload(harness, report=None, raw=None, gz=False, headers=None, ip=None):
    body = raw if raw is not None else json.dumps(report or sample_report()).encode("utf-8")
    hdrs = {"Content-Type": "application/json", "X-Real-IP": ip or fresh_ip()}
    if gz:
        body = gzip.compress(body)
        hdrs["Content-Encoding"] = "gzip"
    hdrs.update(headers or {})
    return harness.request("POST", "/api/reports", body=body, headers=hdrs)


class ApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = ServerHarness()

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def assert_common_headers(self, headers):
        self.assertEqual(headers.get("x-content-type-options"), "nosniff")
        self.assertEqual(headers.get("referrer-policy"), "no-referrer")
        self.assertEqual(headers.get("x-frame-options"), "DENY")

    def test_healthz(self):
        status, headers, body = self.h.request("GET", "/healthz")
        self.assertEqual((status, body), (200, b"ok"))
        self.assert_common_headers(headers)

    def test_upload_plain_and_get_back(self):
        report = sample_report()
        status, headers, body = upload(self.h, report)
        self.assertEqual(status, 201, body)
        self.assert_common_headers(headers)
        result = json.loads(body)
        self.assertRegex(result["id"], r"^[A-Za-z0-9]{10}$")
        self.assertEqual(result["url"], "http://127.0.0.1:%d/r/%s" % (self.h.port, result["id"]))

        path = os.path.join(self.h.server.store.dir, result["id"] + ".json.gz")
        self.assertTrue(os.path.isfile(path))
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o640)

        status, headers, body = self.h.request("GET", "/api/reports/" + result["id"])
        self.assertEqual(status, 200)
        self.assertNotIn("content-encoding", headers)
        self.assertEqual(headers["cache-control"], "public, max-age=86400, immutable")
        self.assertTrue(headers["content-type"].startswith("application/json"))
        self.assertEqual(json.loads(body.decode("utf-8")), report)
        self.assert_common_headers(headers)

    def test_upload_gzip_and_get_gzip(self):
        report = sample_report(durationMs=1500.5)
        status, _, body = upload(self.h, report, gz=True)
        self.assertEqual(status, 201, body)
        report_id = json.loads(body)["id"]
        status, headers, body = self.h.request("GET", "/api/reports/" + report_id,
                                               headers={"Accept-Encoding": "gzip, deflate"})
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("content-encoding"), "gzip")
        self.assertEqual(json.loads(gzip.decompress(body)), report)

        status, headers, body = self.h.request("GET", "/api/reports/" + report_id,
                                               headers={"Accept-Encoding": "gzip;q=0"})
        self.assertEqual(status, 200)
        self.assertNotIn("content-encoding", headers)
        self.assertEqual(json.loads(body), report)

    def test_head_report(self):
        status, _, body = upload(self.h)
        report_id = json.loads(body)["id"]
        status, headers, body = self.h.request("HEAD", "/api/reports/" + report_id)
        self.assertEqual(status, 200)
        self.assertEqual(body, b"")

    def test_public_url_from_forwarded_host(self):
        status, _, body = upload(self.h, headers={"Host": "prof.example.com",
                                                  "X-Forwarded-Proto": "https"})
        self.assertEqual(status, 201)
        result = json.loads(body)
        self.assertEqual(result["url"], "https://prof.example.com/r/" + result["id"])

    def test_bad_json(self):
        for raw in (b"{not json", b"", b"\xff\xfe", b"[1,2]", b'"str"', b"[" * 100000,
                    b'{"format":"amazake-profile","version":1,"durationMs":NaN}'):
            status, headers, body = upload(self.h, raw=raw)
            self.assertEqual(status, 400, raw[:20])
            self.assertIn("error", json.loads(body))
            self.assertTrue(headers["content-type"].startswith("application/json"))

    def test_bad_gzip(self):
        status, _, _ = upload(self.h, raw=b"definitely not gzip",
                              headers={"Content-Encoding": "gzip"})
        self.assertEqual(status, 400)
        truncated = gzip.compress(json.dumps(sample_report()).encode())[:-10]
        status, _, _ = upload(self.h, raw=truncated, headers={"Content-Encoding": "gzip"})
        self.assertEqual(status, 400)

    def test_wrong_format_or_version(self):
        cases = [
            sample_report(format="spark"),
            sample_report(version=2),
            sample_report(version="1"),
            sample_report(version=True),
            sample_report(samples={}),
            sample_report(threads=None),
            sample_report(groups="x"),
            sample_report(durationMs=0),
            sample_report(intervalMs=-5),
            sample_report(intervalMs="1000"),
            sample_report(durationMs=True),
        ]
        no_groups = sample_report()
        del no_groups["groups"]
        cases.append(no_groups)
        for report in cases:
            status, _, body = upload(self.h, report)
            self.assertEqual(status, 400, report)

    def test_wrong_content_type(self):
        status, _, _ = upload(self.h, headers={"Content-Type": "text/plain"})
        self.assertEqual(status, 400)

    def test_missing_content_length(self):
        status = self.h.raw(b"POST /api/reports HTTP/1.1\r\nHost: x\r\n"
                            b"Content-Type: application/json\r\nConnection: close\r\n\r\n")
        self.assertEqual(status, 411)

    def test_too_large_declared(self):
        # Only the headers are sent: the server must answer before reading anything.
        status = self.h.raw(b"POST /api/reports HTTP/1.1\r\nHost: x\r\n"
                            b"Content-Type: application/json\r\nContent-Length: %d\r\n\r\n"
                            % (MAX_BYTES + 1))
        self.assertEqual(status, 413)

    def test_too_large_uncompressed_plain(self):
        report = sample_report(note="x" * (MAX_BYTES + 10))
        body = json.dumps(report).encode()
        status = self.h.raw(b"POST /api/reports HTTP/1.1\r\nHost: x\r\n"
                            b"Content-Type: application/json\r\nContent-Length: %d\r\n\r\n"
                            % len(body))
        self.assertEqual(status, 413)

    def test_gzip_bomb_rejected_quickly(self):
        comp = zlib.compressobj(9, zlib.DEFLATED, 16 + zlib.MAX_WBITS)
        zeros = b"\0" * (1024 * 1024)
        parts = [comp.compress(b'{"format":"amazake-profile","pad":"')]
        for _ in range(512):  # 512 MiB once decompressed
            parts.append(comp.compress(zeros))
        parts.append(comp.compress(b'"}'))
        parts.append(comp.flush())
        bomb = b"".join(parts)
        self.assertLess(len(bomb), MAX_BYTES)
        started = time.monotonic()
        status, _, body = upload(self.h, raw=bomb, headers={"Content-Encoding": "gzip"})
        elapsed = time.monotonic() - started
        self.assertEqual(status, 413, body)
        self.assertLess(elapsed, 3.0)

    def test_gzip_just_over_limit(self):
        report = sample_report(note="y" * MAX_BYTES)
        status, _, _ = upload(self.h, report, gz=True)
        self.assertEqual(status, 413)

    def test_unknown_id(self):
        for path in ("/api/reports/AAAAAAAAAA", "/api/reports/short", "/api/reports/../../etc",
                     "/api/reports/AAAAAAAAAA/", "/api/nothing"):
            status, headers, _ = self.h.request("GET", path)
            self.assertEqual(status, 404, path)

    def test_viewer_routes(self):
        for path in ("/", "/r/aB3xY9kQ2m", "/r/aB3xY9kQ2m?x=1", "/index.html"):
            status, headers, body = self.h.request("GET", path)
            self.assertEqual(status, 200, path)
            self.assertEqual(body, INDEX_HTML)
            self.assertTrue(headers["content-type"].startswith("text/html"))
            self.assertEqual(headers["cache-control"], "no-cache")
            self.assertIn("frame-ancestors 'none'", headers["content-security-policy"])
            self.assertEqual(headers["content-security-policy"], aw.CSP)
            self.assert_common_headers(headers)
        # A malformed id still gets the viewer page (it shows «Неверная ссылка»), never the API's JSON.
        for path in ("/r/bad", "/r/aB3xY9kQ2", "/r/", "/r/%3Cscript%3E"):
            status, headers, body = self.h.request("GET", path)
            self.assertEqual((status, body), (200, INDEX_HTML), path)
            self.assertEqual(headers["content-security-policy"], aw.CSP)
        # Deeper paths are not the viewer.
        status, _, _ = self.h.request("GET", "/r/aB3xY9kQ2m/../../etc/passwd")
        self.assertEqual(status, 404)

    def test_static_files(self):
        status, headers, body = self.h.request("GET", "/app.js")
        self.assertEqual((status, body), (200, b"console.log(1);"))
        self.assertEqual(headers["content-type"], "text/javascript; charset=utf-8")
        self.assertNotIn("content-security-policy", headers)
        status, headers, _ = self.h.request("GET", "/sub/style.css")
        self.assertEqual(status, 200)
        self.assertEqual(headers["content-type"], "text/css; charset=utf-8")
        for path in ("/notes.txt", "/sub", "/sub/", "/missing.js", "/.hidden.js"):
            status, _, _ = self.h.request("GET", path)
            self.assertEqual(status, 404, path)

    def test_path_traversal(self):
        paths = [
            "/../secret.css",
            "/sub/../../secret.css",
            "/%2e%2e/secret.css",
            "/%2E%2E%2Fsecret.css",
            "/sub/%2e%2e%2f%2e%2e%2fsecret.css",
            "/..%5csecret.css",
            "//" + self.h.secret.lstrip("/"),
            self.h.secret,
            "/%2F" + self.h.secret.lstrip("/").replace("/", "%2F"),
            "/link.css",
            "/linkdir/secret.css",
            "/app.js%00.css",
            "http://evil/secret.css",
        ]
        for path in paths:
            status, _, body = self.h.request("GET", path)
            self.assertEqual(status, 404, path)
            self.assertNotIn(b"SECRET", body)

    def test_method_not_allowed(self):
        for method, path in (("PUT", "/api/reports"), ("DELETE", "/api/reports/AAAAAAAAAA"),
                             ("PATCH", "/"), ("OPTIONS", "/"), ("GET", "/api/reports"),
                             ("POST", "/r/aB3xY9kQ2m")):
            status, headers, _ = self.h.request(method, path)
            self.assertEqual(status, 405, (method, path))
            self.assert_common_headers(headers)

    def test_huge_request_line_and_headers(self):
        status, _, _ = self.h.request("GET", "/" + "a" * 20000)
        self.assertEqual(status, 414)
        headers = {"X-Big-%d" % i: "v" * 1000 for i in range(50)}
        status, _, _ = self.h.request("GET", "/healthz", headers=headers)
        self.assertEqual(status, 431)


class TokenTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.token_file = tempfile.NamedTemporaryFile("w", delete=False, suffix=".tokens")
        cls.token_file.write("# comment\n\nfirst-token-123\nsecond-token-456\n")
        cls.token_file.close()
        cls.h = ServerHarness(token_file=cls.token_file.name)

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()
        os.unlink(cls.token_file.name)

    def test_token_missing(self):
        status, _, body = upload(self.h)
        self.assertEqual(status, 401)
        self.assertIn("error", json.loads(body))

    def test_token_wrong(self):
        for token in ("wrong", "first-token-12", "first-token-1234", "# comment"):
            status, _, _ = upload(self.h, headers={"X-Amazake-Token": token})
            self.assertEqual(status, 401, token)

    def test_token_right(self):
        for token in ("first-token-123", "second-token-456"):
            status, _, body = upload(self.h, headers={"X-Amazake-Token": token})
            self.assertEqual(status, 201, body)
            report_id = json.loads(body)["id"]
            # Reading does not need a token.
            status, _, _ = self.h.request("GET", "/api/reports/" + report_id)
            self.assertEqual(status, 200)

    def test_empty_token_file_refused(self):
        with tempfile.NamedTemporaryFile("w", suffix=".tokens") as fh:
            fh.write("\n# nothing\n")
            fh.flush()
            with self.assertRaises(ValueError):
                aw.load_tokens(fh.name)


class RateLimitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = ServerHarness(rate_limit=3)

    @classmethod
    def tearDownClass(cls):
        cls.h.stop()

    def test_rate_limit_per_ip(self):
        for _ in range(3):
            status, _, _ = upload(self.h, ip="203.0.113.7")
            self.assertEqual(status, 201)
        status, _, body = upload(self.h, ip="203.0.113.7")
        self.assertEqual(status, 429)
        self.assertIn("error", json.loads(body))
        status, _, _ = upload(self.h, ip="203.0.113.8")
        self.assertEqual(status, 201)

    def test_sliding_window(self):
        limiter = aw.RateLimiter(2, window=100)
        self.assertTrue(limiter.allow("a", now=0))
        self.assertTrue(limiter.allow("a", now=10))
        self.assertFalse(limiter.allow("a", now=50))
        self.assertTrue(limiter.allow("a", now=101))
        self.assertFalse(limiter.allow("a", now=105))
        self.assertTrue(limiter.allow("a", now=211))
        limiter.prune(now=1000)
        self.assertEqual(limiter.events, {})
        self.assertTrue(aw.RateLimiter(0).allow("x"))


class AbuseTests(unittest.TestCase):
    """Limits that keep the site (and its VPS neighbours) safe from hostile uploads."""

    def setUp(self):
        self.h = ServerHarness(max_bytes=8 * 1024 * 1024)

    def tearDown(self):
        self.h.stop()

    def test_parse_bomb_is_refused_before_parsing(self):
        n = (8 * 1024 * 1024 - 200) // 3
        bomb = ('{"format":"amazake-profile","version":1,"durationMs":1,"intervalMs":1,'
                '"threads":[],"groups":[],"samples":[' + ",".join(["{}"] * n) + "]}").encode()
        self.assertLessEqual(len(bomb), 8 * 1024 * 1024)
        for gz in (False, True):
            status, _, body = upload(self.h, raw=bomb, gz=gz)
            self.assertEqual(status, 413, body)
        self.assertEqual(os.listdir(self.h.server.store.dir), [])

    def test_big_numeric_report_is_accepted_and_streamed_back(self):
        samples = [{"t": i * 1000, "tps": 19.987, "msptAvg": 12.345, "cores": [0.123] * 32}
                   for i in range(29000)]
        report = sample_report(samples=samples)
        raw = json.dumps(report).encode()
        self.assertGreater(len(raw), 7 * 1024 * 1024)
        self.assertLess(len(raw), 8 * 1024 * 1024)
        status, _, body = upload(self.h, raw=raw, gz=True)
        self.assertEqual(status, 201, body)
        report_id = json.loads(body)["id"]
        status, headers, body = self.h.request("GET", "/api/reports/" + report_id)
        self.assertEqual(status, 200)
        self.assertEqual(int(headers["content-length"]), len(raw))
        self.assertEqual(body, raw)  # stored as uploaded, not re-serialized
        status, headers, body = self.h.request("HEAD", "/api/reports/" + report_id)
        self.assertEqual((status, body, int(headers["content-length"])), (200, b"", len(raw)))

    def test_overflowing_number_is_400(self):
        raw = json.dumps(sample_report(samples=[{"tps": 1}])).replace('"tps": 1', '"tps": 1e400')
        status, _, body = upload(self.h, raw=raw.encode())
        self.assertEqual(status, 400, body)

    def test_damaged_report_cuts_connection(self):
        status, _, body = upload(self.h)
        report_id = json.loads(body)["id"]
        path = self.h.server.store.path_for(report_id)
        with open(path, "r+b") as fh:
            data = bytearray(fh.read())
            data[15:25] = b"\xff" * 10
            fh.seek(0)
            fh.write(data)
        with self.assertRaises((http.client.IncompleteRead, ConnectionError)):
            self.h.request("GET", "/api/reports/" + report_id)
        with open(path, "wb") as fh:
            fh.write(b"xx")
        status, _, _ = self.h.request("GET", "/api/reports/" + report_id)
        self.assertEqual(status, 500)

    def test_ipv6_clients_share_a_limit_per_64(self):
        self.h.server.limiter.limit = 2
        statuses = [upload(self.h, ip="2001:db8:1:2::%x" % i)[0] for i in range(4)]
        self.assertEqual(statuses, [201, 201, 429, 429])
        self.assertEqual(upload(self.h, ip="2001:db8:1:3::1")[0], 201)

    def test_disk_quota(self):
        store = self.h.server.store
        status, _, _ = upload(self.h)
        self.assertEqual(status, 201)
        store.max_total_bytes = store.total_bytes + 10
        status, _, body = upload(self.h)
        self.assertEqual(status, 507, body)
        store.max_total_bytes = 0
        store.min_free_bytes = 1 << 62
        status, _, body = upload(self.h)
        self.assertEqual(status, 507, body)
        store.min_free_bytes = 0
        self.assertEqual(upload(self.h)[0], 201)
        self.assertEqual(len(os.listdir(store.dir)), 2)


class HelperTests(unittest.TestCase):
    def test_rate_key(self):
        self.assertEqual(aw.rate_key("198.51.100.1"), "198.51.100.1")
        self.assertEqual(aw.rate_key("2001:db8:1:2:aaaa::1"), "2001:db8:1:2::/64")
        self.assertEqual(aw.rate_key("::ffff:198.51.100.1"), "198.51.100.1")

    def test_rate_limiter_is_bounded(self):
        limiter = aw.RateLimiter(1, window=100, max_keys=50)
        for i in range(1000):
            self.assertTrue(limiter.allow("k%d" % i, now=i * 0.001))
        self.assertLessEqual(len(limiter.events), 50)
        self.assertFalse(limiter.allow("k999", now=2))  # recent keys are kept

    def test_parse_cost_estimate(self):
        self.assertLess(aw.estimate_parse_cost(b'{"a":"text, more","b":[1,2]}'), 1000)
        self.assertGreater(aw.estimate_parse_cost(b"[" + b"{}," * 1000 + b"{}]"), 200000)

    def test_client_ip_from_local_proxy(self):
        self.assertEqual(aw.pick_client_ip("127.0.0.1", {"X-Real-IP": "198.51.100.1"}),
                         "198.51.100.1")
        self.assertEqual(aw.pick_client_ip("::1", {"X-Forwarded-For": "1.1.1.1, 198.51.100.2"}),
                         "198.51.100.2")
        self.assertEqual(aw.pick_client_ip("127.0.0.1", {"X-Real-IP": "garbage"}), "127.0.0.1")
        self.assertEqual(aw.pick_client_ip("127.0.0.1", {}), "127.0.0.1")

    def test_client_ip_ignores_headers_from_remote_peer(self):
        headers = {"X-Real-IP": "198.51.100.1", "X-Forwarded-For": "198.51.100.2"}
        self.assertEqual(aw.pick_client_ip("203.0.113.5", headers), "203.0.113.5")
        self.assertEqual(aw.pick_client_ip("2001:db8::1", headers), "2001:db8::1")

    def test_accepts_gzip(self):
        self.assertTrue(aw.accepts_gzip("gzip"))
        self.assertTrue(aw.accepts_gzip("br, gzip;q=0.5"))
        self.assertTrue(aw.accepts_gzip("*"))
        self.assertFalse(aw.accepts_gzip("gzip;q=0"))
        self.assertFalse(aw.accepts_gzip("identity"))
        self.assertFalse(aw.accepts_gzip(None))

    def test_report_ids(self):
        ids = {aw.new_report_id() for _ in range(1000)}
        self.assertEqual(len(ids), 1000)
        for report_id in ids:
            self.assertRegex(report_id, r"^[A-Za-z0-9]{10}$")

    def test_gunzip_capped(self):
        data = b"a" * 5000
        self.assertEqual(aw.gunzip_capped(gzip.compress(data), 5000), data)
        with self.assertRaises(aw.UploadError) as ctx:
            aw.gunzip_capped(gzip.compress(data), 4999)
        self.assertEqual(ctx.exception.status, 413)
        with self.assertRaises(aw.UploadError) as ctx:
            aw.gunzip_capped(gzip.compress(data) + b"junk", 10000)
        self.assertEqual(ctx.exception.status, 400)


class RetentionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="amazake-web-retention-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_cleanup_removes_old_reports(self):
        store = aw.ReportStore(self.tmp, retention_days=30)
        old_id = store.save(sample_report())
        new_id = store.save(sample_report())
        old_path = store.path_for(old_id)
        day = 86400
        os.utime(old_path, (time.time() - 31 * day, time.time() - 31 * day))
        stale_tmp = os.path.join(store.dir, ".tmp-abc.part")
        with open(stale_tmp, "wb") as fh:
            fh.write(b"x")
        os.utime(stale_tmp, (time.time() - 7200, time.time() - 7200))
        unrelated = os.path.join(store.dir, "README")
        with open(unrelated, "wb") as fh:
            fh.write(b"keep")
        os.utime(unrelated, (time.time() - 99 * day, time.time() - 99 * day))

        self.assertIsNone(store.load_gzip(old_id))  # expired reports are hidden right away
        self.assertEqual(store.cleanup(), 2)
        self.assertFalse(os.path.exists(old_path))
        self.assertFalse(os.path.exists(stale_tmp))
        self.assertTrue(os.path.exists(store.path_for(new_id)))
        self.assertTrue(os.path.exists(unrelated))
        self.assertIsNotNone(store.load_gzip(new_id))

    def test_total_size_is_counted(self):
        store = aw.ReportStore(self.tmp, retention_days=30)
        store.save(sample_report())
        store.save(b'{"x":1}')
        size = sum(os.path.getsize(os.path.join(store.dir, n)) for n in os.listdir(store.dir))
        self.assertEqual(store.total_bytes, size)
        self.assertEqual(aw.ReportStore(self.tmp, retention_days=30).total_bytes, size)

    def test_retention_zero_keeps_forever(self):
        store = aw.ReportStore(self.tmp, retention_days=0)
        report_id = store.save(sample_report())
        path = store.path_for(report_id)
        os.utime(path, (0, 0))
        self.assertEqual(store.cleanup(), 0)
        self.assertIsNotNone(store.load_gzip(report_id))


class CliTests(unittest.TestCase):
    def test_defaults_and_env(self):
        cfg = aw.parse_args(["--data-dir", "/tmp/x"])
        self.assertEqual((cfg.host, cfg.port, cfg.max_bytes, cfg.retention_days, cfg.rate_limit),
                         ("127.0.0.1", 8787, 8 * 1024 * 1024, 30, 30))
        self.assertEqual((cfg.max_total_mb, cfg.min_free_mb), (1024, 1024))
        old = dict(os.environ)
        try:
            os.environ["AMAZAKE_WEB_PORT"] = "9999"
            os.environ["AMAZAKE_WEB_PUBLIC_URL"] = "https://example.com/"
            cfg = aw.parse_args([])
            self.assertEqual(cfg.port, 9999)
            self.assertEqual(cfg.public_url, "https://example.com")
            self.assertEqual(aw.parse_args(["--port", "1234"]).port, 1234)
        finally:
            os.environ.clear()
            os.environ.update(old)


if __name__ == "__main__":
    unittest.main()
