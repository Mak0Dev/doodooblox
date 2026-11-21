#!/usr/bin/env python3
"""Serves the web player for testing: python3 Linux/web/serve.py [build/web] [port]

Sends what the page needs and a plain file server does not: the cross-origin
isolation headers (without them the browser refuses the engine's threads) and
the precompressed .br/.gz files. Open http://localhost:8080/?file=place.rbxl -
localhost counts as a secure origin, so no certificate is needed here.
"""
import http.server
import os
import sys

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else 'build/web')
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8080

TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript',
    '.wasm': 'application/wasm',
    '.data': 'application/octet-stream',
    '.rbxl': 'application/octet-stream',
}


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
        self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
        self.send_header('Cache-Control', 'no-cache')
        super().end_headers()

    def do_GET(self):
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            path = os.path.join(path, 'index.html')
        ext = os.path.splitext(path)[1]
        accepted = self.headers.get('Accept-Encoding', '')

        for encoding, suffix in (('br', '.br'), ('gzip', '.gz')):
            if encoding in accepted and os.path.isfile(path + suffix) \
                    and os.path.getmtime(path + suffix) >= os.path.getmtime(path):
                with open(path + suffix, 'rb') as f:
                    body = f.read()
                self.send_response(200)
                self.send_header('Content-Type', TYPES.get(ext, 'application/octet-stream'))
                self.send_header('Content-Encoding', encoding)
                # No Content-Length: the page's progress bar would read it as
                # the size of the unpacked file and stop halfway.
                self.send_header('Vary', 'Accept-Encoding')
                self.end_headers()
                self.wfile.write(body)
                return

        super().do_GET()

    def guess_type(self, path):
        return TYPES.get(os.path.splitext(path)[1]) or super().guess_type(path)

    def log_message(self, fmt, *args):
        pass


print('Serving %s on http://localhost:%d/?file=place.rbxl' % (ROOT, PORT))
http.server.ThreadingHTTPServer(('', PORT), Handler).serve_forever()
