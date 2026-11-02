import hashlib
import http.server
import os
from pathlib import Path
import re
import socketserver
import subprocess
import tempfile
import threading

root = Path(__file__).resolve().parent.parent
payload = bytes(range(256)) * (32768 + 4)
requests = []

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        header = self.headers.get("Range", "")
        requests.append(header)
        match = re.fullmatch(r"bytes=(\d+)-(\d*)", header)
        start = int(match.group(1)) if match else 0
        end = min(int(match.group(2)) if match and match.group(2) else len(payload) - 1, len(payload) - 1)
        if start >= len(payload):
            self.send_response(416)
            self.send_header("Content-Range", "bytes */" + str(len(payload)))
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(206 if match else 200)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if match:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, len(payload)))
        self.end_headers()
        self.wfile.write(payload[start:end + 1])

    def log_message(self, *args):
        pass

class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

server = Server(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
fd, name = tempfile.mkstemp(prefix="lowtube-range-")
os.close(fd)
try:
    command = [str(root / "tools" / "mpv" / "mpv.exe"), "--no-config", "--load-scripts=no", "--ytdl=no",
               "--stream-lavf-o=request_size=8388608,short_seek_size=8388608", "--stream-dump=" + name,
               "lavf://http://127.0.0.1:%d/file" % server.server_port]
    result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=25)
    with open(name, "rb") as output:
        digest = hashlib.sha256(output.read()).digest()
    assert result.returncode == 0 and digest == hashlib.sha256(payload).digest(), "mpv must preserve all bytes"
    assert len(requests) >= 2 and requests[0] == "bytes=0-8388607" and requests[1].startswith("bytes=8388608-"), "mpv must request bounded consecutive ranges"
    print("PASS: mpv reads consecutive bounded HTTP ranges without losing bytes")
finally:
    server.shutdown()
    server.server_close()
    os.remove(name)
