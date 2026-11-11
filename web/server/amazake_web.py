#!/usr/bin/env python3
"""Amazake profiler site backend.

Stores profiler reports uploaded by Amazake servers and serves them together with
the static viewer. Standard library only (Python 3.8+), see docs/profiler-format.md
for the report format and the upload API.

Run: python3 amazake_web.py --data-dir /var/lib/amazake-web --port 8787
"""

import argparse
import gzip
import hmac
import ipaddress
import json
import math
import os
import re
import secrets
import signal
import socket
import stat
import struct
import sys
import tempfile
import threading
import time
import zlib
from collections import OrderedDict, deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FORMAT_NAME = "amazake-profile"
FORMAT_VERSION = 1

ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
ID_LENGTH = 10
ID_RE = re.compile(r"^[A-Za-z0-9]{%d}$" % ID_LENGTH)
REPORT_PATH_RE = re.compile(r"^/api/reports/([A-Za-z0-9]{%d})$" % ID_LENGTH)
VIEWER_PATH_RE = re.compile(r"^/r/([A-Za-z0-9]{%d})$" % ID_LENGTH)
REPORT_FILE_RE = re.compile(r"^[A-Za-z0-9]{%d}\.json\.gz$" % ID_LENGTH)
HOST_RE = re.compile(r"^[A-Za-z0-9.\-]+(:[0-9]{1,5})?$|^\[[0-9A-Fa-f:.]+\](:[0-9]{1,5})?$")

MIME_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
}

CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
       "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")

TRUSTED_PROXIES = ("127.0.0.1", "::1", "::ffff:127.0.0.1")

SOCKET_TIMEOUT = 30            # seconds per socket operation
MAX_REQUEST_LINE = 8192        # bytes
MAX_HEADER_BYTES = 32 * 1024   # sum of all header names and values
MAX_CONNECTIONS = 128          # simultaneous connections, extra ones are closed
MAX_PARALLEL_UPLOADS = 1       # uploads parsed at once (bounds memory use)
# Upper bound for the memory json.loads may use on one upload, estimated before parsing
# (see estimate_parse_cost). A real 8 MiB report of numbers needs about 70 MB here.
MAX_PARSE_COST = 96 * 1024 * 1024
MAX_STATIC_BYTES = 16 * 1024 * 1024
READ_CHUNK = 64 * 1024
CLEANUP_INTERVAL = 3600        # seconds
RATE_WINDOW = 3600             # seconds
RATE_MAX_KEYS = 10000          # clients remembered by the rate limiter (bounds memory use)
RATE_PRUNE_EVERY = 60          # seconds between full prunes when the limiter is full

_log_lock = threading.Lock()


def log(line):
    """One line to stdout (journald collects it)."""
    stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    clean = "".join(ch if ch.isprintable() else "\\x%02x" % ord(ch) for ch in line)
    with _log_lock:
        sys.stdout.write("%s %s\n" % (stamp, clean))
        sys.stdout.flush()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

class UploadError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def new_report_id():
    return "".join(secrets.choice(ID_ALPHABET) for _ in range(ID_LENGTH))


def _valid_ip(value):
    try:
        return str(ipaddress.ip_address(value.strip()))
    except ValueError:
        return None


def pick_client_ip(peer, headers):
    """Client address for logging and rate limiting.

    Proxy headers are believed only when the TCP peer is the local nginx
    (127.0.0.1 / ::1); anyone else could forge them. X-Real-IP wins, else the
    last X-Forwarded-For entry (the one nginx appended itself).
    """
    if peer not in TRUSTED_PROXIES:
        return peer
    real = headers.get("X-Real-IP")
    if real:
        ip = _valid_ip(real)
        if ip:
            return ip
    forwarded = headers.get("X-Forwarded-For")
    if forwarded:
        ip = _valid_ip(forwarded.split(",")[-1])
        if ip:
            return ip
    return peer


def accepts_gzip(header_value):
    if not header_value:
        return False
    for part in header_value.split(","):
        pieces = [p.strip() for p in part.split(";")]
        coding = pieces[0].lower()
        if coding not in ("gzip", "x-gzip", "*"):
            continue
        q = 1.0
        for param in pieces[1:]:
            if param.lower().startswith("q="):
                try:
                    q = float(param[2:])
                except ValueError:
                    q = 0.0
        if q > 0:
            return True
    return False


def gunzip_capped(data, limit):
    """Decompress a gzip body, never producing more than `limit` bytes."""
    decomp = zlib.decompressobj(16 + zlib.MAX_WBITS)
    out = []
    total = 0
    pos = 0
    try:
        while pos < len(data) or decomp.unconsumed_tail:
            if decomp.unconsumed_tail:
                chunk = decomp.unconsumed_tail
            else:
                chunk = data[pos:pos + READ_CHUNK]
                pos += len(chunk)
            piece = decomp.decompress(chunk, limit - total + 1)
            total += len(piece)
            if total > limit:
                raise UploadError(413, "report too large")
            out.append(piece)
            if decomp.eof:
                break
        if not decomp.eof:
            raise UploadError(400, "truncated gzip body")
        if decomp.unused_data or pos < len(data):
            raise UploadError(400, "unexpected data after gzip body")
    except zlib.error:
        raise UploadError(400, "invalid gzip body")
    return b"".join(out)


def _reject_constant(name):
    raise ValueError("non-finite number")


def _finite_float(text):
    value = float(text)
    if not math.isfinite(value):  # e.g. 1e400 overflows to inf
        raise ValueError("non-finite number")
    return value


def estimate_parse_cost(raw):
    """Rough upper bound, in bytes, of the Python objects json.loads would build from `raw`.

    The byte size of a body says little about the memory it needs once parsed: 8 MiB of
    `{},{},...` becomes about 200 MB of dicts. This counts containers, values and string
    values with bytes.count only, so it needs no extra memory. Characters inside strings
    are counted too, which can only overestimate. Object keys (`"...":`) are not counted
    as strings, since json.loads shares equal keys.
    """
    containers = raw.count(b"{") + raw.count(b"[")
    values = raw.count(b",") + 1
    strings = (raw.count(b'"') - raw.count(b'\\"')) // 2
    string_values = max(0, strings - raw.count(b'":'))
    return containers * 200 + values * 32 + string_values * 80


def _positive_number(value):
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and value > 0)


def parse_report(raw):
    """Parse and minimally validate a version-1 report. Returns the parsed object."""
    if estimate_parse_cost(raw) > MAX_PARSE_COST:
        raise UploadError(413, "report too large (too many values)")
    try:
        text = raw.decode("utf-8")
        report = json.loads(text, parse_constant=_reject_constant, parse_float=_finite_float)
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise UploadError(400, "body is not valid JSON")
    if not isinstance(report, dict):
        raise UploadError(400, "report must be a JSON object")
    if report.get("format") != FORMAT_NAME:
        raise UploadError(400, "format must be \"%s\"" % FORMAT_NAME)
    version = report.get("version")
    if isinstance(version, bool) or version != FORMAT_VERSION:
        raise UploadError(400, "unsupported version, expected %d" % FORMAT_VERSION)
    for key in ("samples", "threads", "groups"):
        if not isinstance(report.get(key), list):
            raise UploadError(400, "%s must be a list" % key)
    for key in ("durationMs", "intervalMs"):
        if not _positive_number(report.get(key)):
            raise UploadError(400, "%s must be a positive number" % key)
    return report


# ---------------------------------------------------------------------------
# Storage and rate limiting
# ---------------------------------------------------------------------------

class StorageFull(UploadError):
    def __init__(self, message):
        super().__init__(507, message)


class ReportStore:
    def __init__(self, data_dir, retention_days, max_total_bytes=0, min_free_bytes=0):
        self.dir = os.path.join(os.path.abspath(data_dir), "reports")
        self.retention_days = retention_days
        self.max_total_bytes = max_total_bytes  # 0 = no quota
        self.min_free_bytes = min_free_bytes    # 0 = no free-space check
        self.lock = threading.Lock()
        os.makedirs(self.dir, mode=0o750, exist_ok=True)
        self.total_bytes = 0
        self.cleanup(remove=False)

    def free_bytes(self):
        try:
            st = os.statvfs(self.dir)
        except OSError:
            return None
        return st.f_bavail * st.f_frsize

    def path_for(self, report_id):
        if not ID_RE.match(report_id):
            raise ValueError("bad id")
        return os.path.join(self.dir, report_id + ".json.gz")

    def save(self, report):
        """Store a report (validated raw JSON bytes, or an object to serialize). Returns its id."""
        if isinstance(report, (bytes, bytearray)):
            payload = bytes(report)
        else:
            payload = json.dumps(report, ensure_ascii=False, separators=(",", ":"),
                                 allow_nan=False).encode("utf-8")
        compressed = gzip.compress(payload, compresslevel=6, mtime=0)
        del payload
        with self.lock:
            return self._write_locked(compressed)

    def _write_locked(self, compressed):
        size = len(compressed)
        if self.max_total_bytes > 0 and self.total_bytes + size > self.max_total_bytes:
            raise StorageFull("report storage is full, try again later")
        if self.min_free_bytes > 0:
            free = self.free_bytes()
            if free is not None and free - size < self.min_free_bytes:
                raise StorageFull("not enough free disk space on the site")
        for _ in range(10):
            report_id = new_report_id()
            final = self.path_for(report_id)
            if not os.path.exists(final):
                break
        else:
            raise RuntimeError("could not pick a free report id")
        fd, tmp = tempfile.mkstemp(prefix=".tmp-", suffix=".part", dir=self.dir)
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(compressed)
                fh.flush()
                os.fsync(fh.fileno())
            os.chmod(tmp, 0o640)
            os.replace(tmp, final)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        self.total_bytes += size
        return report_id

    def open_report(self, report_id):
        """(open binary file, size) of a stored report, or None if it does not exist / has expired."""
        try:
            path = self.path_for(report_id)
        except ValueError:
            return None
        try:
            fh = open(path, "rb")
        except OSError:
            return None
        try:
            st = os.fstat(fh.fileno())
            if not stat.S_ISREG(st.st_mode):
                fh.close()
                return None
            if self.retention_days > 0 and st.st_mtime < time.time() - self.retention_days * 86400:
                fh.close()
                return None
            return fh, st.st_size
        except OSError:
            fh.close()
            return None

    def load_gzip(self, report_id):
        """Compressed bytes of a report, or None if it does not exist / has expired."""
        opened = self.open_report(report_id)
        if opened is None:
            return None
        fh, _ = opened
        try:
            with fh:
                return fh.read()
        except OSError:
            return None

    def cleanup(self, now=None, remove=True):
        """Delete expired reports and abandoned temp files, recount the stored size.

        Returns the number of files removed."""
        with self.lock:
            return self._cleanup_locked(time.time() if now is None else now, remove)

    def _cleanup_locked(self, now, remove):
        removed = 0
        total = 0
        try:
            names = os.listdir(self.dir)
        except OSError:
            return 0
        for name in names:
            path = os.path.join(self.dir, name)
            try:
                st = os.lstat(path)
            except OSError:
                continue
            if not stat.S_ISREG(st.st_mode):
                continue
            expired = False
            if REPORT_FILE_RE.match(name):
                expired = self.retention_days > 0 and st.st_mtime < now - self.retention_days * 86400
            elif name.startswith(".tmp-"):
                expired = st.st_mtime < now - 3600
            if expired and remove:
                try:
                    os.unlink(path)
                    removed += 1
                    continue
                except OSError:
                    pass
            total += st.st_size
        self.total_bytes = total
        return removed


def rate_key(ip):
    """Rate-limit key for a client address: IPv4 as is, IPv6 by its /64 network.

    One IPv6 host usually owns a whole /64, so keying by the full address would let it
    rotate addresses to get around the limit."""
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return ip
    if addr.version == 6:
        if addr.ipv4_mapped is not None:
            return str(addr.ipv4_mapped)
        return str(ipaddress.ip_network("%s/64" % addr, strict=False))
    return str(addr)


class RateLimiter:
    """Sliding-window limit of events per key, in memory, for at most `max_keys` keys.

    When the table is full, expired entries are dropped first, then the least recently
    seen keys, so memory stays bounded whatever addresses clients come from."""

    def __init__(self, limit, window=RATE_WINDOW, max_keys=RATE_MAX_KEYS):
        self.limit = limit
        self.window = window
        self.max_keys = max(1, max_keys)
        self.events = OrderedDict()
        self.lock = threading.Lock()
        self._last_full_prune = None

    def allow(self, key, now=None):
        if self.limit <= 0:
            return True
        now = time.monotonic() if now is None else now
        with self.lock:
            q = self.events.get(key)
            if q is None:
                if len(self.events) >= self.max_keys:
                    if self._last_full_prune is None or now - self._last_full_prune >= RATE_PRUNE_EVERY:
                        self._last_full_prune = now
                        self._prune_locked(now)
                    while len(self.events) >= self.max_keys:
                        self.events.popitem(last=False)
                q = self.events[key] = deque(maxlen=self.limit)
            else:
                self.events.move_to_end(key)
            while q and q[0] <= now - self.window:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(now)
            return True

    def prune(self, now=None):
        now = time.monotonic() if now is None else now
        with self.lock:
            self._prune_locked(now)

    def _prune_locked(self, now):
        for key in list(self.events):
            q = self.events[key]
            while q and q[0] <= now - self.window:
                q.popleft()
            if not q:
                del self.events[key]


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

class Config:
    def __init__(self, host="127.0.0.1", port=8787, data_dir="./data", static_dir=None,
                 public_url="", token_file="", max_bytes=8 * 1024 * 1024,
                 retention_days=30, rate_limit=30, max_total_mb=1024, min_free_mb=1024):
        self.host = host
        self.port = port
        self.data_dir = data_dir
        self.static_dir = static_dir or os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "..", "static")
        self.public_url = (public_url or "").rstrip("/")
        self.token_file = token_file or ""
        self.max_bytes = max_bytes
        self.retention_days = retention_days
        self.rate_limit = rate_limit
        self.max_total_mb = max_total_mb
        self.min_free_mb = min_free_mb


def load_tokens(path):
    tokens = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                tokens.append(line.encode("utf-8"))
    if not tokens:
        raise ValueError("token file %s has no tokens" % path)
    return tokens


class AmazakeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, config):
        self.config = config
        self.store = ReportStore(config.data_dir, config.retention_days,
                                 max_total_bytes=config.max_total_mb * 1024 * 1024,
                                 min_free_bytes=config.min_free_mb * 1024 * 1024)
        self.limiter = RateLimiter(config.rate_limit)
        self.tokens = load_tokens(config.token_file) if config.token_file else None
        self.static_root = os.path.realpath(config.static_dir)
        self.upload_slots = threading.BoundedSemaphore(MAX_PARALLEL_UPLOADS)
        self.conn_slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
        self.stop_event = threading.Event()
        if ":" in config.host:
            self.address_family = socket.AF_INET6
        super().__init__((config.host, config.port), Handler)

    def process_request(self, request, client_address):
        if not self.conn_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.conn_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.conn_slots.release()

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        log("error handling request from %s: %s: %s" % (client_address[0], type(exc).__name__, exc))

    def start_cleanup_thread(self):
        def loop():
            while not self.stop_event.is_set():
                try:
                    removed = self.store.cleanup()
                    self.limiter.prune()
                    if removed:
                        log("cleanup: removed %d expired file(s)" % removed)
                except Exception as exc:  # keep the thread alive
                    log("cleanup failed: %s" % exc)
                self.stop_event.wait(CLEANUP_INTERVAL)
        thread = threading.Thread(target=loop, name="amazake-cleanup", daemon=True)
        thread.start()
        return thread


class Handler(BaseHTTPRequestHandler):
    server_version = "AmazakeWeb/1"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = SOCKET_TIMEOUT

    def version_string(self):
        return self.server_version

    # --- plumbing -------------------------------------------------------

    def setup(self):
        super().setup()
        self._started = time.monotonic()
        self._client_ip = self.client_address[0]
        self._is_html = False

    def __getattr__(self, name):
        # Any method without a handler (PUT, DELETE, OPTIONS, ...) gets 405.
        if name.startswith("do_"):
            return self._method_not_allowed
        raise AttributeError(name)

    def parse_request(self):
        self._is_html = False
        self._started = time.monotonic()
        if len(self.raw_requestline) > MAX_REQUEST_LINE:
            self.requestline = self.raw_requestline[:80].decode("latin-1", "replace").rstrip()
            self.request_version = "HTTP/1.1"
            self.close_connection = True
            self.send_error(414)
            return False
        if not super().parse_request():
            return False
        size = sum(len(k) + len(v) for k, v in self.headers.items())
        if size > MAX_HEADER_BYTES:
            self.close_connection = True
            self.send_error(431)
            return False
        self._client_ip = pick_client_ip(self.client_address[0], self.headers)
        return True

    def end_headers(self):
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        if self._is_html:
            self.send_header("Content-Security-Policy", CSP)
        super().end_headers()

    def log_request(self, code="-", size="-"):
        if isinstance(code, HTTPStatus):
            code = code.value
        elapsed = (time.monotonic() - getattr(self, "_started", time.monotonic())) * 1000
        line = getattr(self, "requestline", "")
        if len(line) > 300:
            line = line[:300] + "..."
        log('%s "%s" %s %s %.0fms' % (getattr(self, "_client_ip", "-"), line, code, size, elapsed))

    def log_message(self, fmt, *args):
        log("%s %s" % (getattr(self, "_client_ip", "-"), fmt % args))

    def log_error(self, fmt, *args):
        pass  # the request line is logged by log_request already

    def send_error(self, code, message=None, explain=None):
        try:
            short = HTTPStatus(code).phrase
        except ValueError:
            short = "Error"
        self._send_json(code, {"error": message or short})

    def _start_response(self, status, content_type, length, headers=None):
        """Send the status line and headers. Returns True if a body should follow."""
        self._is_html = content_type.startswith("text/html")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self._is_html = False
        return getattr(self, "command", None) != "HEAD"

    def _send(self, status, content_type, body, headers=None, head_only=False):
        if self._start_response(status, content_type, len(body), headers) and not head_only:
            self.wfile.write(body)

    def _send_json(self, status, obj, headers=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        all_headers = {"Cache-Control": "no-store"}
        all_headers.update(headers or {})
        self._send(status, "application/json; charset=utf-8", body, all_headers)

    def _method_not_allowed(self):
        self.close_connection = True
        self._send_json(405, {"error": "method not allowed"}, {"Allow": "GET, HEAD, POST"})

    def _request_path(self):
        """Path part of the request target, without query or fragment (not decoded)."""
        target = self.path
        for sep in ("?", "#"):
            target = target.split(sep, 1)[0]
        return target

    # --- routes ---------------------------------------------------------

    def do_GET(self):
        path = self._request_path()
        if path == "/healthz":
            self._send(200, "text/plain; charset=utf-8", b"ok", {"Cache-Control": "no-store"})
            return
        if path in ("/", "/index.html") or VIEWER_PATH_RE.match(path):
            self._serve_static("index.html")
            return
        match = REPORT_PATH_RE.match(path)
        if match:
            self._serve_report(match.group(1))
            return
        if path.startswith("/api/"):
            if path == "/api/reports":
                self._method_not_allowed()
            else:
                self.send_error(404)
            return
        self._serve_static_path(path)

    def do_HEAD(self):
        self.do_GET()

    def do_POST(self):
        path = self._request_path()
        if path != "/api/reports":
            if REPORT_PATH_RE.match(path) or path in ("/", "/healthz") or not path.startswith("/api/"):
                self._method_not_allowed()
            else:
                self.send_error(404)
            return
        self._unread_body = 0
        try:
            report_id = self._handle_upload()
        except UploadError as err:
            if isinstance(err, StorageFull):
                log("%s upload refused: %s" % (self._client_ip, err.message))
            self._drain_body()
            self._send_json(err.status, {"error": err.message})
            return
        except Exception as exc:  # never drop the connection without an answer
            log("%s upload failed: %s: %s" % (self._client_ip, type(exc).__name__, exc))
            self.close_connection = True
            self._send_json(500, {"error": "internal error, report not stored"})
            return
        url = "%s/r/%s" % (self._base_url(), report_id)
        log("%s stored report %s" % (self._client_ip, report_id))
        self._send_json(201, {"id": report_id, "url": url}, {"Location": "/r/" + report_id})

    # --- upload ---------------------------------------------------------

    def _handle_upload(self):
        server = self.server
        cfg = server.config
        if self.headers.get("Transfer-Encoding"):
            self.close_connection = True
            raise UploadError(411, "chunked bodies are not accepted, send Content-Length")
        length_header = self.headers.get("Content-Length")
        if length_header is None:
            self.close_connection = True
            raise UploadError(411, "Content-Length required")
        try:
            length = int(length_header.strip())
            if length < 0 or not length_header.strip().isdigit():
                raise ValueError
        except ValueError:
            self.close_connection = True
            raise UploadError(400, "bad Content-Length")
        if length > cfg.max_bytes:
            self.close_connection = True
            raise UploadError(413, "report too large (limit %d bytes)" % cfg.max_bytes)
        self._unread_body = length
        if not server.limiter.allow(rate_key(self._client_ip)):
            self.close_connection = True
            raise UploadError(429, "too many uploads, try again later")
        if server.tokens is not None:
            given = self.headers.get("X-Amazake-Token", "").strip().encode("utf-8", "replace")
            ok = False
            for token in server.tokens:
                if hmac.compare_digest(given, token):
                    ok = True
            if not given or not ok:
                self.close_connection = True
                raise UploadError(401, "token missing or wrong")
        content_type = self.headers.get("Content-Type", "").split(";")[0].strip().lower()
        if content_type != "application/json":
            self.close_connection = True
            raise UploadError(400, "Content-Type must be application/json")
        encoding = self.headers.get("Content-Encoding", "identity").strip().lower()
        if encoding not in ("identity", "gzip", "x-gzip", ""):
            self.close_connection = True
            raise UploadError(400, "unsupported Content-Encoding")

        if not server.upload_slots.acquire(timeout=10):
            self.close_connection = True
            raise UploadError(503, "server busy, try again later")
        try:
            body = self._read_body(length)
            if encoding in ("gzip", "x-gzip"):
                raw = gunzip_capped(body, cfg.max_bytes)
            else:
                raw = body
            del body
            parse_report(raw)  # validation only; the object tree is dropped right away
            return server.store.save(raw)
        finally:
            server.upload_slots.release()

    def _drain_body(self):
        """Read and drop a body we refused, so the client sees our answer instead of a reset."""
        remaining = getattr(self, "_unread_body", 0)
        self._unread_body = 0
        try:
            while remaining > 0:
                chunk = self.rfile.read(min(READ_CHUNK, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
        except OSError:
            self.close_connection = True

    def _read_body(self, length):
        self._unread_body = 0
        parts = []
        remaining = length
        while remaining > 0:
            chunk = self.rfile.read(min(READ_CHUNK, remaining))
            if not chunk:
                self.close_connection = True
                raise UploadError(400, "incomplete body")
            parts.append(chunk)
            remaining -= len(chunk)
        return b"".join(parts)

    def _base_url(self):
        cfg = self.server.config
        if cfg.public_url:
            return cfg.public_url
        host = (self.headers.get("Host") or "").strip()
        if not host or not HOST_RE.match(host):
            host = "%s:%d" % (cfg.host if ":" not in cfg.host else "[%s]" % cfg.host, cfg.port)
        scheme = "http"
        if self.client_address[0] in TRUSTED_PROXIES:
            proto = (self.headers.get("X-Forwarded-Proto") or "").strip().lower()
            if proto in ("http", "https"):
                scheme = proto
        return "%s://%s" % (scheme, host)

    # --- reports --------------------------------------------------------

    def _serve_report(self, report_id):
        """Stream a stored report from disk in small chunks (memory stays bounded however
        many requests run at once), as stored gzip or decompressed on the fly."""
        opened = self.server.store.open_report(report_id)
        if opened is None:
            self.send_error(404, "report not found")
            return
        fh, size = opened
        with fh:
            headers = {"Cache-Control": "public, max-age=86400, immutable", "Vary": "Accept-Encoding"}
            ctype = "application/json; charset=utf-8"
            if accepts_gzip(self.headers.get("Accept-Encoding")):
                headers["Content-Encoding"] = "gzip"
                if self._start_response(200, ctype, size, headers):
                    self._copy_file(fh, size)
                return
            # The gzip trailer holds the uncompressed size (mod 2**32; reports are far smaller).
            try:
                if size < 18:
                    raise ValueError("too short")
                fh.seek(size - 4)
                (plain_size,) = struct.unpack("<I", fh.read(4))
                fh.seek(0)
            except (OSError, ValueError, struct.error):
                self.send_error(500, "stored report is damaged")
                return
            if self._start_response(200, ctype, plain_size, headers):
                self._copy_gunzipped(fh, plain_size, report_id)

    def _copy_file(self, fh, size):
        remaining = size
        while remaining > 0:
            chunk = fh.read(min(READ_CHUNK, remaining))
            if not chunk:
                break
            self.wfile.write(chunk)
            remaining -= len(chunk)
        if remaining:
            self.close_connection = True  # file shrank under us; the client sees a short body

    def _copy_gunzipped(self, fh, plain_size, report_id):
        decomp = zlib.decompressobj(16 + zlib.MAX_WBITS)
        sent = 0
        ok = True
        try:
            while not decomp.eof:
                chunk = decomp.unconsumed_tail or fh.read(READ_CHUNK)
                if chunk:
                    piece = decomp.decompress(chunk, READ_CHUNK)
                else:
                    piece = decomp.flush()  # end of file: whatever zlib still holds (< 64 KiB)
                if sent + len(piece) > plain_size:
                    ok = False
                    break
                if piece:
                    self.wfile.write(piece)
                    sent += len(piece)
                if not chunk:
                    break
        except (OSError, zlib.error):
            ok = False
        if not ok or sent != plain_size or not decomp.eof:
            log("stored report %s is damaged" % report_id)
            self.close_connection = True  # Content-Length is already sent, cut the connection

    # --- static ---------------------------------------------------------

    def _serve_static_path(self, raw_path):
        from urllib.parse import unquote
        try:
            decoded = unquote(raw_path, encoding="utf-8", errors="strict")
        except UnicodeDecodeError:
            self.send_error(404)
            return
        if not decoded.startswith("/") or "\0" in decoded or "\\" in decoded:
            self.send_error(404)
            return
        parts = decoded.split("/")[1:]
        for part in parts:
            if part in ("", ".", "..") or part.startswith("."):
                self.send_error(404)
                return
        self._serve_static("/".join(parts))

    def _serve_static(self, rel):
        root = self.server.static_root
        ext = os.path.splitext(rel)[1].lower()
        mime = MIME_TYPES.get(ext)
        if mime is None:
            self.send_error(404)
            return
        full = os.path.realpath(os.path.join(root, rel))
        try:
            if os.path.commonpath([root, full]) != root:
                self.send_error(404)
                return
        except ValueError:
            self.send_error(404)
            return
        try:
            fd = os.open(full, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        except OSError:
            self.send_error(404)
            return
        try:
            st = os.fstat(fd)
            if not stat.S_ISREG(st.st_mode) or st.st_size > MAX_STATIC_BYTES:
                self.send_error(404)
                return
            with os.fdopen(fd, "rb") as fh:
                fd = None
                body = fh.read(MAX_STATIC_BYTES + 1)
        finally:
            if fd is not None:
                os.close(fd)
        cache = "no-cache" if ext == ".html" else "public, max-age=300"
        self._send(200, mime, body, {"Cache-Control": cache})


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _env(name, default):
    return os.environ.get("AMAZAKE_WEB_" + name, default)


def _env_int(name, default):
    value = os.environ.get("AMAZAKE_WEB_" + name)
    if value is None or value.strip() == "":
        return default
    try:
        return int(value)
    except ValueError:
        raise SystemExit("AMAZAKE_WEB_%s must be an integer" % name)


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="Amazake profiler site backend (standard library only).")
    p.add_argument("--host", default=_env("HOST", "127.0.0.1"),
                   help="address to listen on (default 127.0.0.1, put nginx in front)")
    p.add_argument("--port", type=int, default=_env_int("PORT", 8787), help="port (default 8787)")
    p.add_argument("--data-dir", default=_env("DATA_DIR", "./data"),
                   help="where reports are stored (default ./data)")
    p.add_argument("--static-dir", default=_env("STATIC_DIR", None),
                   help="viewer files (default ../static next to this file)")
    p.add_argument("--public-url", default=_env("PUBLIC_URL", ""),
                   help="public site address, e.g. https://example.com (default: from Host header)")
    p.add_argument("--token-file", default=_env("TOKEN_FILE", ""),
                   help="file with upload tokens, one per line (default: uploads are open)")
    p.add_argument("--max-bytes", type=int, default=_env_int("MAX_BYTES", 8 * 1024 * 1024),
                   help="max report size, uncompressed (default 8 MiB)")
    p.add_argument("--retention-days", type=int, default=_env_int("RETENTION_DAYS", 30),
                   help="delete reports older than this, 0 = keep forever (default 30)")
    p.add_argument("--rate-limit", type=int, default=_env_int("RATE_LIMIT", 30),
                   help="uploads per IP (IPv6: per /64) per hour, 0 = unlimited (default 30)")
    p.add_argument("--max-total-mb", type=int, default=_env_int("MAX_TOTAL_MB", 1024),
                   help="max total size of stored reports on disk, MiB, 0 = no limit (default 1024)")
    p.add_argument("--min-free-mb", type=int, default=_env_int("MIN_FREE_MB", 1024),
                   help="refuse uploads when the disk would have less free space, MiB, "
                        "0 = no check (default 1024)")
    args = p.parse_args(argv)
    if not (0 < args.port < 65536):
        p.error("--port must be 1..65535")
    if args.max_bytes <= 0:
        p.error("--max-bytes must be positive")
    if (args.retention_days < 0 or args.rate_limit < 0 or args.max_total_mb < 0
            or args.min_free_mb < 0):
        p.error("--retention-days, --rate-limit, --max-total-mb and --min-free-mb "
                "must not be negative")
    return Config(host=args.host, port=args.port, data_dir=args.data_dir,
                  static_dir=args.static_dir, public_url=args.public_url,
                  token_file=args.token_file, max_bytes=args.max_bytes,
                  retention_days=args.retention_days, rate_limit=args.rate_limit,
                  max_total_mb=args.max_total_mb, min_free_mb=args.min_free_mb)


def main(argv=None):
    config = parse_args(argv)
    try:
        server = AmazakeServer(config)
    except (OSError, ValueError) as exc:
        log("cannot start: %s" % exc)
        return 1

    def stop(signum, frame):
        log("signal %d received, shutting down" % signum)
        server.stop_event.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    server.start_cleanup_thread()
    if not os.path.isfile(os.path.join(server.static_root, "index.html")):
        log("warning: %s/index.html not found, the viewer will answer 404" % server.static_root)
    log("listening on %s:%d, data in %s, uploads %s" % (
        config.host, config.port, server.store.dir,
        "need a token" if server.tokens is not None else "open"))
    free = server.store.free_bytes()
    log("stored reports: %d MiB (limit %s), free disk: %s (uploads stop below %d MiB)" % (
        server.store.total_bytes // (1024 * 1024),
        "%d MiB" % config.max_total_mb if config.max_total_mb else "none",
        "%d MiB" % (free // (1024 * 1024)) if free is not None else "unknown",
        config.min_free_mb))
    if config.min_free_mb and free is not None and free < config.min_free_mb * 1024 * 1024:
        log("warning: less free disk space than --min-free-mb, uploads will be refused (507)")
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()
        log("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
