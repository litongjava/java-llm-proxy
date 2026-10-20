import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from oauth_integration_test import free_port
from protocol_matrix_test import PROTOCOLS, request_body, endpoint, upstream_body


def run():
    root = Path(__file__).resolve().parents[1]
    captured = []

    class Upstream(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def handle(self):
            try:
                super().handle()
            except ConnectionResetError:
                pass
        def log_message(self, *args):
            pass

        def do_POST(self):
            protocol = self.path.split('/')[1]
            payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            captured.append((protocol, self.path, payload, dict(self.headers)))
            stream = payload.get('stream', False) or ':streamGenerateContent' in self.path
            result = upstream_body(protocol, stream)
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream' if stream else 'application/json')
            self.send_header('Content-Length', str(len(result)))
            self.end_headers()
            self.wfile.write(result)

    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    port = free_port()

    def call(path, body=None):
        request = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
            data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization': 'Bearer test', 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.read().decode()
        except urllib.error.HTTPError as error:
            raise AssertionError((path, error.code, error.read().decode())) from None

    with tempfile.TemporaryDirectory(prefix='multi-protocol-') as directory:
        with open(Path(directory) / 'server.log', 'wb') as log:
            process = None

            def start():
                nonlocal process
                process = subprocess.Popen(['java', '-jar', str(root / 'target/java-llm-proxy-1.0.0.jar'),
                    f'--server.port={port}', f'--proxy.data-dir={directory}', f'--proxy.config={directory}/config.yaml',
                    '--proxy.management-token=test', '--proxy.api-key=test'], stdout=log, stderr=log,
                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                for attempt in range(100):
                    try:
                        call('/api/status')
                        return
                    except OSError:
                        time.sleep(.1)
                raise AssertionError('Backend did not start')

            try:
                start()
                endpoints = {protocol: f'http://127.0.0.1:{upstream.server_port}/{protocol}'
                    + ('/v1beta/models' if protocol == 'gemini' else '/v1') for protocol in PROTOCOLS}
                group = {'name': 'multi-test', 'default-protocol': 'responses', 'protocol-endpoints': endpoints,
                    'models': [{'name': 'matrix-model'}], 'keys': [{'api-key': 'upstream-test'}]}
                config_path = '/v8/management/config/api-keys/multi-protocol'
                call(config_path, {'operation': 'PUT', 'value': [group]})
                for source in PROTOCOLS:
                    for stream in (False, True):
                        body = request_body(source, stream)
                        body['native_extension'] = {'preserved': True}
                        result = call(endpoint(source, stream), body)
                        assert 'matrix answer' in result
                        target, path, payload, headers = captured[-1]
                        assert target == source, (source, target)
                        assert payload['native_extension'] == body['native_extension']
                        assert 'input' not in payload if source == 'anthropic' else True
                        expected_header = 'x-api-key' if source == 'anthropic' else 'x-goog-api-key' if source == 'gemini' else 'authorization'
                        normalized = {key.lower(): value for key, value in headers.items()}
                        assert 'upstream-test' in normalized[expected_header]
                process.terminate()
                process.wait(timeout=20)
                start()
                call('/v1/messages', request_body('anthropic', False))
                assert captured[-1][0] == 'anthropic'
                group['protocol-endpoints'] = {'responses': endpoints['responses']}
                call(config_path, {'operation': 'PUT', 'value': [group]})
                for stream in (False, True):
                    assert 'matrix answer' in call('/v1/messages', request_body('anthropic', stream))
                    assert captured[-1][0] == 'responses'
                    assert 'input' in captured[-1][2] and 'messages' not in captured[-1][2]
                print('PASS: 8 native HTTP cases, authentication, restart persistence, and 2 conversion fallbacks')
            finally:
                if process is not None:
                    process.terminate()
                    process.wait(timeout=20)
                upstream.shutdown()


if __name__ == '__main__':
    run()
