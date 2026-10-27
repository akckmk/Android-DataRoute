"""Stand-in NVR for the emulator tests: answers every HTTP GET with "NVR-OK".

CI starts it on the host; the Android emulator reaches the host at 10.0.2.2 over its Wi-Fi network.
"""
import http.server
import sys


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"NVR-OK"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 7441
    http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
