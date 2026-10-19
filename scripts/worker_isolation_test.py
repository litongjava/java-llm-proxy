"""Keep HTTP responsive while more upstream calls block than there are network workers."""
import concurrent.futures
import json
import subprocess
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from oauth_integration_test import free_port


def run():
    root = Path(__file__).resolve().parents[1]
    release = threading.Event()
    arrived = threading.Condition()
    count = 0

    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            nonlocal count
            self.rfile.read(int(self.headers['Content-Length']))
            with arrived:
                count += 1
                arrived.notify_all()
            release.wait(15)
            body = b'{"choices":[{"message":{"role":"assistant","content":"ok"}}]}'
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    mock = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    data = Path(tempfile.mkdtemp(prefix='worker-isolation-', dir=root / 'target'))
    port = free_port()

    def call(path, body=None, token='test-admin', timeout=10):
        request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read()

    with (data / 'server.log').open('wb') as log:
        process = subprocess.Popen(['java', '-jar', str(root / 'target/java-llm-proxy-1.0.0.jar'),
            f'--server.port={port}', '--server.worker.threads=2', f'--proxy.data-dir={data}',
            f'--proxy.config={data}/config.yaml', '--proxy.management-token=test-admin',
            '--proxy.api-key=test-key'], stdout=log, stderr=log,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for _ in range(100):
                try:
                    call('/api/status', timeout=1)
                    break
                except OSError:
                    time.sleep(.1)
            call('/api/settings', {'providers': [{'id': 'slow', 'name': 'slow', 'protocol': 'openai',
                'baseUrl': f'http://127.0.0.1:{mock.server_port}/v1', 'apiKey': 'test', 'models': ['test']}]})
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                futures = [pool.submit(call, '/v1/chat/completions',
                    {'model': 'test', 'messages': [{'role': 'user', 'content': 'hi'}]}, 'test-key') for _ in range(8)]
                try:
                    with arrived:
                        assert arrived.wait_for(lambda: count == 8, timeout=5), f'Only {count}/8 calls reached upstream'
                    start = time.monotonic()
                    call('/api/status', timeout=2)
                    latency = time.monotonic() - start
                finally:
                    release.set()
                for future in futures:
                    assert b'ok' in future.result(timeout=5)
            print(f'PASS: 8 blocked upstream requests with 2 network workers; status latency {latency:.3f}s')
        finally:
            release.set()
            process.terminate()
            process.wait(timeout=15)
            mock.shutdown()


if __name__ == '__main__':
    run()
