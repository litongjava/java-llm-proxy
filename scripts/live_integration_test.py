"""Codex Live bootstrap and real socket sideband regression, using isolated fake credentials."""
import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from oauth_integration_test import free_port
from websocket_integration_test import frame, send_frame


def run():
    root = Path(__file__).resolve().parents[1]
    data = Path(tempfile.mkdtemp(prefix='live-', dir=root / 'target'))
    auth = data / 'auth'
    auth.mkdir()
    created, joined, messages, errors = {}, [], [], []
    reject = set()

    class Mock(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def do_POST(self):
            self.close_connection = True
            try:
                assert self.path == '/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas', self.path
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                assert body['sdp'] == 'v=0\r\n', body
                assert self.headers['OpenAI-Alpha'] == 'quicksilver=v2', dict(self.headers)
                assert self.headers.get('X-Api-Key') is None
                call = 'rtc_' + str(len(created) + 1)
                created[call] = (self.headers['Authorization'], self.headers['Chatgpt-Account-Id'], body)
                answer = b'v=0\r\na=answer\r\n'
                self.send_response(201)
                self.send_header('Location', 'https://api.openai.com/v1/realtime/calls/' + call)
                self.send_header('Content-Type', 'application/sdp')
                self.send_header('Content-Length', str(len(answer)))
                self.send_header('Connection', 'close')
                self.end_headers()
                self.wfile.write(answer)
            except Exception as error:
                errors.append(repr(error))
                self.close_connection = True

        def do_GET(self):
            self.close_connection = True
            try:
                call = self.path.split('call_id=')[-1] if '?' in self.path else self.path.rsplit('/', 1)[-1]
                expected = created[call]
                assert self.headers['Authorization'] == expected[0]
                assert self.headers['Chatgpt-Account-Id'] == expected[1]
                joined.append(self.path)
                if call in reject:
                    reject.remove(call)
                    self.send_response(404)
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                accept = base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
                self.send_response(101)
                self.send_header('Upgrade', 'websocket')
                self.send_header('Connection', 'Upgrade')
                self.send_header('Sec-WebSocket-Accept', accept)
                self.end_headers()
                send_frame(self.wfile, {'type': 'session.created'})
                while True:
                    opcode, payload = frame(self.rfile)
                    if opcode == 8:
                        break
                    messages.append((opcode, payload))
                    if opcode == 2:
                        self.wfile.write(bytes([130, len(payload)]) + payload)
                        self.wfile.flush()
                    else:
                        send_frame(self.wfile, json.loads(payload))
            except (EOFError, ConnectionError):
                pass
            except Exception as error:
                errors.append(repr(error))

    mock = ThreadingHTTPServer(('127.0.0.1', 0), Mock)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    for index in range(2):
        (auth / f'codex-{index}.json').write_text(json.dumps({'type': 'codex', 'access_token': f'fake-oauth-{index}',
            'account_id': f'account-{index}', 'expired': '2099-01-01T00:00:00Z',
            'base_url': f'http://127.0.0.1:{mock.server_port}/backend-api/codex'}))
    port = free_port()

    def http(path, body=None, token='live-client', media='application/json'):
        raw = json.dumps(body).encode() if isinstance(body, dict) else body
        request = urllib.request.Request(f'http://127.0.0.1:{port}' + path, data=raw,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': media, 'OpenAI-Alpha': 'quicksilver=v2'})
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    def connect(path, token='live-client'):
        connection = socket.create_connection(('127.0.0.1', port), timeout=10)
        stream = connection.makefile('rwb', buffering=0)
        key = base64.b64encode(os.urandom(16)).decode()
        stream.write((f'GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {key}\r\nAuthorization: Bearer {token}\r\n\r\n').encode())
        status = stream.readline().decode()
        while stream.readline() not in (b'\r\n', b''):
            pass
        return connection, stream, status

    def rejected(path, expected, token='live-client'):
        connection, stream, status = connect(path, token)
        stream.close()
        connection.close()
        assert f' {expected} ' in status, status

    args = ['java', '-jar', str(root / 'target/java-llm-proxy-1.0.0.jar'), f'--server.port={port}',
        f'--proxy.data-dir={data}', f'--proxy.config={data}/config.yaml', f'--proxy.auth-dir={auth}',
        '--proxy.management-token=live-admin', '--proxy.api-key=live-client',
        f'--proxy.live.sideband-base-url=http://127.0.0.1:{mock.server_port}/v1']
    with (data / 'server.log').open('wb') as log:
        process = subprocess.Popen(args, stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for _ in range(100):
                try:
                    if http('/api/status', token='live-admin')[0] == 200:
                        break
                except OSError:
                    time.sleep(.1)
            assert http('/v1/live', {'sdp': 'v=0\r\n'}, token='wrong')[0] == 401
            assert http('/v1/live', {'session': {}})[0] == 400
            rejected('/v1/realtime/calls/missing', 404)
            rejected('/v1/realtime?call_id=bad.id', 400)
            status, _, raw = http('/api/keys/create', {'name': 'other'}, token='live-admin')
            assert status == 200, raw
            key = json.loads(raw)['data']['key']
            for index, endpoint in enumerate(['/v1/live', '/v1/realtime/calls', '/v1/realtime', '/tools/codex/v1/live']):
                status, headers, raw = http(endpoint, {'sdp': 'v=0\r\n', 'session': {'model': 'gpt-realtime', 'voice': 'test'}, 'extension_test': {'enabled': True}})
                assert status == 201, (status, raw, errors)
                assert raw == b'v=0\r\na=answer\r\n'
                location = headers['Location']
                call = location.rsplit('/', 1)[-1]
                assert created[call][2]['extension_test'] == {'enabled': True}
                assert created[call][2]['session'] == {'model': 'gpt-live-1-codex', 'voice': 'test'}
                path = [location, '/v1/live/' + call, '/v1/realtime?call_id=' + call, location][index]
                rejected(path, 401, 'wrong')
                rejected(path, 403, key)
                if index == 0:
                    reject.add(call)
                    rejected(path, 404)
                connection, stream, status = connect(path)
                assert ' 101 ' in status, status
                assert json.loads(frame(stream)[1])['type'] == 'session.created'
                rejected(path, 409)
                event = {'type': 'response.create', 'response': {'instructions': 'unchanged'}}
                send_frame(stream, event, True)
                assert json.loads(frame(stream)[1]) == event
                payload, mask = b'\x00\x01audio', b'1234'
                stream.write(bytes([130, 128 | len(payload)]) + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(payload)))
                assert frame(stream) == (2, payload)
                stream.close()
                connection.close()
            multipart = (b'--live\r\nContent-Disposition: form-data; name="sdp"\r\n\r\nv=0\r\n\r\n'
                         b'--live\r\nContent-Disposition: form-data; name="session"\r\n\r\n{"model":"gpt-realtime"}\r\n--live--\r\n')
            status, _, raw = http('/v1/live', multipart, media='multipart/form-data; boundary=live')
            assert status == 201, (status, raw, errors)
            status, _, raw = http('/v1/live', b'v=0\r\n', media='application/sdp')
            assert status == 201, (status, raw, errors)
            assert not errors, errors
            assert len({entry[0] for entry in created.values()}) == 2, created
            assert any(path.startswith('/v1/realtime?intent=quicksilver&call_id=') for path in joined), joined
            assert any(path.startswith('/v1/live/') for path in joined), joined
            print('PASS: JSON/multipart/SDP bootstrap, 201 + Location, all three sideband paths, tool prefix, account pinning, owner isolation, duplicate rejection, upstream 404 + retry, server-first event, unchanged JSON and binary round trips')
            print('Test artifacts:', data)
        finally:
            process.terminate()
            process.wait(timeout=15)
            mock.shutdown()


if __name__ == '__main__':
    run()
