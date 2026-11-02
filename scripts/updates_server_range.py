"""Read-only Family Touch APK updater, single-range HTTP downloads for reliable resume."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import argparse
import re

ROOT = Path(r"C:\ProgramData\FamilyTouchUpdates")
ALLOWED = {
    "/manifests/win.fantest.familytouch.embedded.json": ("embedded.json", "application/json"),
    "/manifests/win.fantest.familytouch.admin.json": ("admin.json", "application/json"),
    "/apks/embedded.apk": ("embedded.apk", "application/vnd.android.package-archive"),
    "/apks/admin.apk": ("admin.apk", "application/vnd.android.package-archive"),
}
MAX_SIZE = 250_000_000
RANGE_RE = re.compile(r"bytes=(\d+)-(\d*)\Z")

class Handler(BaseHTTPRequestHandler):
    server_version = "FamilyUpdates/2"
    sys_version = ""
    def log_message(self, *args):
        pass
    def do_HEAD(self):
        self.respond(False)
    def do_GET(self):
        self.respond(True)
    def respond(self, send):
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            if send:
                self.wfile.write(b"OK")
            return
        item = ALLOWED.get(self.path)
        if item is None:
            self.send_error(404)
            return
        filename, content_type = item
        path = ROOT / filename
        try:
            length = path.stat().st_size
        except OSError:
            self.send_error(404)
            return
        if length <= 0 or length > MAX_SIZE:
            self.send_error(503)
            return
        start, end = 0, length - 1
        range_header = self.headers.get("Range")
        if range_header:
            matched = RANGE_RE.fullmatch(range_header.strip())
            if not matched:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{length}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            start = int(matched.group(1))
            explicit_end = matched.group(2)
            end = int(explicit_end) if explicit_end else length - 1
            if start >= length or end < start:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{length}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end, length - 1)
        self.send_response(206 if range_header else 200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        if range_header:
            self.send_header("Content-Range", f"bytes {start}-{end}/{length}")
        self.send_header("Cache-Control", "public, max-age=60, must-revalidate")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if not send:
            return
        try:
            with path.open("rb") as stream:
                stream.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    block = stream.read(min(65536, remaining))
                    if not block:
                        break
                    self.wfile.write(block)
                    remaining -= len(block)
        except (ConnectionAbortedError, ConnectionResetError, BrokenPipeError):
            pass

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18798)
    args = parser.parse_args()
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
