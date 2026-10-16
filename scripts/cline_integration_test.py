"""Exercise Cline OAuth and inference through an isolated Java HTTP server."""
import json
import os
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Upstream(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    calls = []

    def handle(self):
        try:
            super().handle()
        except ConnectionResetError:
            # The isolated JVM closes pooled sockets when the test terminates it.
            pass

    def log_message(self, *args):
        pass

    def reply(self, value, status=200, content_type='application/json'):
        data = (json.dumps(value) if not isinstance(value, str) else value).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.endswith('/recommended-models'):
            self.reply({'free': [{'id': 'cline-free/test-model'}]})
        else:
            self.reply({'data': [{'id': 'cline-free/test-model'}]})

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        body = json.loads(raw) if 'application/json' in self.headers.get('Content-Type', '') else dict(urllib.parse.parse_qsl(raw.decode()))
        self.calls.append((self.path, body))
        if self.path == '/device':
            self.reply({'device_code': 'test-device', 'user_code': 'CLINE-TEST',
                        'verification_uri': 'https://example.test/verify', 'interval': 1, 'expires_in': 300})
        elif self.path == '/token':
            self.reply({'access_token': 'workos-access', 'refresh_token': 'workos-refresh'})
        elif self.path.endswith('/auth/register'):
            assert body == {'accessToken': 'workos-access', 'refreshToken': 'workos-refresh'}
            self.reply({'success': True, 'data': {'accessToken': 'workos:cline-access',
                        'refreshToken': 'cline-refresh', 'expiresAt': '2099-01-01T00:00:00Z',
                        'userInfo': {'clineUserId': 'test-user', 'email': 'cline@example.test'}}})
        elif self.path.endswith('/auth/refresh'):
            assert body['grantType'] == 'refresh_token'
            self.reply({'success': True, 'data': {'accessToken': 'workos:rotated',
                        'refreshToken': 'rotated-refresh', 'expiresAt': '2099-01-01T00:00:00Z'}})
        elif self.path.endswith('/chat/completions'):
            assert self.headers['Authorization'].startswith('Bearer workos:')
            assert self.headers['X-CLIENT-TYPE'] == 'cline-sdk'
            assert body['stream'] is True
            events = [
                {'id': 'chatcmpl-test', 'model': body['model'], 'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': 'Cline works'}, 'finish_reason': None}]},
                {'id': 'chatcmpl-test', 'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}], 'usage': {'prompt_tokens': 3, 'completion_tokens': 2, 'total_tokens': 5}},
            ]
            self.reply(''.join('data: ' + json.dumps(event) + '\n\n' for event in events) + 'data: [DONE]\n\n', content_type='text/event-stream')
        else:
            self.reply({'error': 'unknown path'}, 404)


def run():
    root = Path(__file__).resolve().parents[1]
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    assert port != 8318
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    mock = f'http://127.0.0.1:{upstream.server_port}'
    base = f'http://127.0.0.1:{port}'
    directory = Path(tempfile.mkdtemp(prefix='cline-integration-', dir=root / 'target'))
    args = [os.environ.get('JAVA_HOME', 'D:/java/jdk-21.0.6') + '/bin/java.exe', '-jar', str(root / 'target/java-llm-proxy-1.0.0.jar'),
            f'--server.port={port}', f'--proxy.data-dir={directory}/data', f'--proxy.config={directory}/config.yaml',
            f'--proxy.auth-dir={directory}/auth', '--proxy.management-token=cline-admin', '--proxy.api-key=cline-client',
            f'--proxy.oauth.cline.device-url={mock}/device', f'--proxy.oauth.cline.token-url={mock}/token',
            f'--proxy.oauth.cline.api-base-url={mock}/api/v1']

    def request(path, body=None, token='cline-admin'):
        req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                data = response.read().decode()
                return response.status, json.loads(data) if data.startswith('{') else data
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def management(path, body=None):
        status, result = request('/v8/management/' + path, body)
        assert status == 200 and result.get('ok'), (path, status, result)
        return result['data']

    with (directory / 'server.log').open('wb') as log:
        process = subprocess.Popen(args, cwd=directory, stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for _ in range(100):
                try:
                    if request('/api/status')[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(.1)
            else:
                raise AssertionError('Java startup failed: ' + str(directory / 'server.log'))
            assert request('/v8/management/oauth/auth-url?provider=cline', token='cline-client')[0] == 401
            start = management('oauth/auth-url?provider=cline')
            assert start['flow'] == 'device' and start['user_code'] == 'CLINE-TEST'
            for _ in range(20):
                result = management('oauth/status?state=' + start['state'])
                if result['status'] == 'ok':
                    break
                assert result['status'] == 'wait', result
                time.sleep(.2)
            else:
                raise AssertionError('OAuth did not complete')
            files = management('credentials')['files']
            assert len(files) == 1 and files[0]['type'] == 'cline'
            assert 'cline-access' not in json.dumps(files)
            management('credentials/refresh', {'name': files[0]['name']})
            payload = {'model': 'cline-free/test-model', 'messages': [{'role': 'user', 'content': 'hello'}]}
            status, result = request('/v1/chat/completions', payload, 'cline-client')
            assert status == 200 and result['choices'][0]['message']['content'] == 'Cline works', (status, result)
            assert result['usage']['total_tokens'] == 5
            status, stream = request('/v1/chat/completions', dict(payload, stream=True), 'cline-client')
            assert status == 200 and 'Cline works' in stream and '[DONE]' in stream, (status, stream)
            assert any(path.endswith('/auth/refresh') for path, _ in Upstream.calls)
            print(f'PASS Cline HTTP OAuth, management auth, credential refresh, JSON and SSE inference; Java port={port}, mock port={upstream.server_port}')
        finally:
            process.terminate()
            process.wait(timeout=20)
            upstream.shutdown()


if __name__ == '__main__':
    run()
