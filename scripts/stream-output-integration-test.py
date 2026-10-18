"""Packaged proxy: medium effort and first generated output after SSE heartbeats."""
import json
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main():
    received = []

    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            received.append(body)
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            self.wfile.write(b': ping\n\ndata: {"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}\n\n')
            self.wfile.flush()
            time.sleep(0.45)
            self.wfile.write(b'data: {"choices":[{"index":0,"delta":{"content":"OK"}}]}\n\n'
                             b'data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
            self.wfile.flush()

    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    jar = Path('target/java-llm-proxy-1.0.0.jar').resolve()
    with tempfile.TemporaryDirectory(prefix='proxy-output-') as directory:
        work = Path(directory)
        config = {'access': {'api-keys': ['test-client']}, 'plugins': {'enabled': False},
                  'requests': {'proxy-url': 'direct'},
                  'api-keys': {'openai-compatibility': [{'name': 'fixture',
                    'base-url': f'http://127.0.0.1:{upstream.server_port}/v1',
                    'models': [{'name': 'test-model'}], 'keys': [{'api-key': 'test'}]}]}}
        (work / 'config.json').write_text(json.dumps(config), encoding='utf8')

        def request(path, payload=None, admin=False):
            req = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
                data=None if payload is None else json.dumps(payload).encode(),
                headers={'Authorization': 'Bearer ' + ('test-admin' if admin else 'test-client'),
                         'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=15) as response:
                return response.headers, response.read().decode()

        with (work / 'server.log').open('w') as log:
            process = subprocess.Popen(['java', f'-Dserver.port={port}', f'-Dproxy.data-dir={work / "data"}',
                f'-Dproxy.config={work / "config.json"}', '-Dproxy.management-token=test-admin', '-jar', str(jar)],
                cwd=work, stdout=log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            try:
                deadline = time.monotonic() + 25
                while True:
                    try:
                        request('/api/status', admin=True)
                        break
                    except OSError:
                        if process.poll() is not None or time.monotonic() > deadline:
                            raise AssertionError((work / 'server.log').read_text()[-2000:])
                        time.sleep(.1)
                for protocol in ['responses', 'chat/completions']:
                    payload = {'model': 'test-model', 'stream': True}
                    if protocol == 'responses':
                        payload.update(input='hello', reasoning={'effort': 'medium'})
                    else:
                        payload.update(messages=[{'role': 'user', 'content': 'hello'}], reasoning_effort='medium')
                    headers, text = request('/tools/dsc/v1/' + protocol, payload)
                    assert 'OK' in text, text
                    assert received[-1]['reasoning_effort'] == 'medium', received[-1]
                    rid = headers['x-request-id']
                    detail = json.loads(request('/api/usage/detail?id=' + rid, admin=True)[1])
                    detail = detail.get('data', detail)
                    assert int(detail['firstByteMs']) >= 400, detail
                    assert detail['reasoningEffort'] == 'medium', detail
                    print(f'PASS {protocol}: medium preserved, first generated output {detail["firstByteMs"]}ms (heartbeat excluded)')
            finally:
                process.terminate()
                process.wait(timeout=10)
                upstream.shutdown()


if __name__ == '__main__':
    main()
