"""Loopback-only Family Touch admin HTTPS-tunnel gateway for the Windows primary.
No external listener, no stored plaintext passwords, no credentials in logs.
The upstream broker itself rejects public forwarding and enforces independent CSRF.
"""
import base64
import device_auth
import collections
import hashlib
import hmac
import http.client
import json
import pathlib
import time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from threading import Lock

ROOT = pathlib.Path(r"C:\ProgramData\FamilyTouchBroker")
AUTH = json.loads((ROOT / "master-admin-auth.json").read_text(encoding="utf-8"))
HOSTNAME = "family-admin.fantest.win"
UPSTREAM_PORT = 18797
PORT = 8799
lock = Lock()
attempts = collections.defaultdict(collections.deque)
ALLOWED = {"/admin", "/admin/device", "/admin/setup", "/admin/api/v1/devices", "/admin/api/v1/device"}

class Handler(BaseHTTPRequestHandler):
    server_version = "FamilyTouchAdmin/0.3"
    sys_version = ""
    def log_message(self, fmt, *args):
        # Never log authentication headers, credential-bearing requests or device IDs.
        pass
    def _send(self, status, data=b"", headers=None):
        self.send_response(status)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Pragma", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Strict-Transport-Security", "max-age=31536000")
        for k, v in (headers or {}).items():
            if k.lower() in {"content-type", "location", "content-security-policy"}:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
    def _authorized(self):
        with lock:
            times = attempts[self.client_address[0]]
            now = time.monotonic()
            while times and now - times[0] > 60:
                times.popleft()
            if len(times) > 32:
                return False
        h = self.headers.get("Authorization", "")
        if len(h) > 512 or not h.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(h[6:], validate=True).decode("utf-8")
            name, pw = decoded.split(":", 1)
            salt = bytes.fromhex(AUTH["salt"])
            check = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, int(AUTH["iterations"]))
            good = hmac.compare_digest(name, AUTH["username"]) and hmac.compare_digest(check.hex(), AUTH["hash"])
        except Exception:
            good = False
        if not good:
            with lock:
                attempts[self.client_address[0]].append(time.monotonic())
        return good
    def _handle(self):
        if self.path not in ALLOWED or len(self.path) > 40:
            self._send(404, b"Not found")
            return
        if self.headers.get("Host", "").split(":")[0].lower() not in {HOSTNAME, "127.0.0.1", "localhost"}:
            self._send(403, b"Invalid host")
            return
        if not self._authorized():
            self._send(401, b"Administrator login required", {
                "Content-Type": "text/plain; charset=utf-8",
                # WWW-Authenticate handled separately in _auth_failure below.
            })
            return
        data = b""
        if self.command == "POST":
            if self.path == "/admin" or self.headers.get("Origin") != "https://" + HOSTNAME:
                self._send(403, b"Origin rejected")
                return
            if "application/x-www-form-urlencoded" not in self.headers.get("Content-Type", ""):
                self._send(415, b"Form encoding required")
                return
            try:
                n = int(self.headers.get("Content-Length", "0"))
                if n <= 0 or n > 8192:
                    self._send(413, b"Invalid form size")
                    return
                data = self.rfile.read(n)
                if len(data) != n:
                    self._send(400, b"Incomplete form")
                    return
            except (ValueError, OSError):
                self._send(400, b"Invalid request")
                return
        try:
            c = http.client.HTTPConnection("127.0.0.1", UPSTREAM_PORT, timeout=12)
            h = {"Host": f"127.0.0.1:{UPSTREAM_PORT}",
                 "Origin": f"http://127.0.0.1:{UPSTREAM_PORT}",
                 "Content-Type": "application/x-www-form-urlencoded"}
            c.request(self.command if self.command != "HEAD" else "GET", self.path, body=data, headers=h)
            response = c.getresponse()
            payload = response.read(65537)
            if len(payload) > 65536:
                self._send(502, b"Response exceeded limit")
                return
            headers = {k: v for k, v in response.getheaders()
                       if k.lower() in {"content-type", "location", "content-security-policy"}}
            self._send(response.status, payload, headers)
            c.close()
        except Exception:
            self._send(503, b"Family Touch primary broker is temporarily unavailable")
    def _auth_or_handle(self):
        native = self.path.startswith("/admin/api/v1/")
        if native and self.headers.get("X-Requested-With") == "FamilyTouchNative" and (
                self.path == "/admin/api/v1/device-enroll" or self.headers.get("X-FT-KeyId")):
            if self.path == "/admin/api/v1/device-enroll":
                if self.command != "POST":
                    self._send(405, b"Method not allowed")
                    return
            elif self.path not in ("/admin/api/v1/devices", "/admin/api/v1/device"):
                self._send(404, b"Not found")
                return
            if self.headers.get("Origin"):
                self._send(403, b"Browser origins not allowed")
                return
            if self.command == "POST":
                if not self.headers.get("Content-Type", "").startswith("application/json"):
                    self._send(415, b"JSON required")
                    return
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if size <= 0 or size > 8192:
                        self._send(413, b"Invalid body size")
                        return
                    signed_body = self.rfile.read(size)
                    if len(signed_body) != size:
                        self._send(400, b"Incomplete body")
                        return
                except (ValueError, OSError):
                    self._send(400, b"Invalid body")
                    return
            else:
                signed_body = b""
            if self.path == "/admin/api/v1/device-enroll":
                try:
                    result = device_auth.enroll(signed_body, self.headers)
                    self._send(200, json.dumps(result).encode("utf-8"),
                               {"Content-Type": "application/json"})
                except (ValueError, KeyError, TypeError):
                    self._send(400, b"Invalid enrollment")
                except PermissionError:
                    self._send(403, b"Enrollment not accepted")
                return
            if not device_auth.authenticate(self.command, self.path, signed_body, self.headers):
                self._send(401, b"Device key not authorized")
                return
            self._forward_verified(signed_body)
            return
        if not self._authorized():
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="Family Touch Admin", charset="UTF-8"')
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Length", "28")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(b"Administrator login required")
            return
        self._handle_authenticated()
    def _handle_authenticated(self):
        # Avoid a second PBKDF2 authentication pass after the challenge has passed.
        self._handle_verified()
    def _handle_verified(self):
        # Reuse the forwarding path with an already verified request.
        # The Basic authorization is never included in upstream headers.
        self._forward_verified()
    def do_GET(self):
        self._auth_or_handle()
    def do_POST(self):
        self._auth_or_handle()
    def do_HEAD(self):
        self._auth_or_handle()
    def _forward_verified(self, signed_body=None):
        if self.path not in ALLOWED:
            self._send(404, b"Not found")
            return
        if self.headers.get("Host", "").split(":")[0].lower() not in {HOSTNAME, "127.0.0.1", "localhost"}:
            self._send(403, b"Invalid host")
            return
        data = b""
        native = self.path.startswith("/admin/api/v1/")
        if native:
            if self.path.endswith("/devices") and self.command not in ("GET", "HEAD"):
                self._send(405, b"Method not allowed")
                return
            if self.path.endswith("/device") and self.command != "POST":
                self._send(405, b"Method not allowed")
                return
            if self.headers.get("Origin") or self.headers.get("X-Requested-With") != "FamilyTouchNative":
                self._send(403, b"Native request required")
                return
        if self.command == "POST":
            if self.path == "/admin":
                self._send(403, b"Origin rejected")
                return
            if native:
                if not self.headers.get("Content-Type", "").startswith("application/json"):
                    self._send(415, b"JSON required")
                    return
            elif self.headers.get("Origin") != "https://" + HOSTNAME or "application/x-www-form-urlencoded" not in self.headers.get("Content-Type", ""):
                self._send(403, b"Origin or form encoding rejected")
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size <= 0 or size > 8192:
                    self._send(413, b"Invalid form size")
                    return
                if signed_body is not None:
                    if size != len(signed_body):
                        self._send(400, b"Invalid signed body")
                        return
                    data = signed_body
                else:
                    data = self.rfile.read(size)
                    if len(data) != size:
                        self._send(400, b"Invalid form")
                        return
            except (ValueError, OSError):
                self._send(400, b"Invalid request")
                return
        try:
            connection = http.client.HTTPConnection("127.0.0.1", UPSTREAM_PORT, timeout=12)
            upstream_headers = {"Host": f"127.0.0.1:{UPSTREAM_PORT}",
                                "Origin": f"http://127.0.0.1:{UPSTREAM_PORT}",
                                "Content-Type": "application/json" if native else "application/x-www-form-urlencoded"}
            if native:
                upstream_headers["X-FT-Native-Client"] = "1"
            connection.request("GET" if self.command == "HEAD" else self.command,
                               self.path, body=data, headers=upstream_headers)
            resp = connection.getresponse()
            payload = resp.read(65537)
            if len(payload) > 65536:
                self._send(502, b"Upstream too large")
                return
            allowed = {"content-type", "location", "content-security-policy"}
            safe_headers = {k: v for k, v in resp.getheaders() if k.lower() in allowed}
            self._send(resp.status, payload, safe_headers)
            connection.close()
        except Exception:
            self._send(503, b"Family Touch broker unavailable")

if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    print(f"Family Touch Admin gateway listening on loopback:{PORT}", flush=True)
    server.serve_forever()
