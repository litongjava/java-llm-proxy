"""Verify headerless Codex SSE over local HTTP, including tool calls and stored status."""
import json
import subprocess
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from oauth_integration_test import free_port


def run():
    root = Path(__file__).resolve().parents[1]
    call_item = dict(type='function_call', call_id='call_test', name='weather', arguments='{}', status='completed')
    completed = dict(id='resp_test', object='response', status='completed', output=[call_item],
                     usage=dict(input_tokens=6029, output_tokens=31))
    events = [
        dict(type='response.created', response=dict(id='resp_test', status='in_progress')),
        dict(type='response.output_item.added', output_index=0, item={**call_item, 'arguments': ''}),
        dict(type='response.function_call_arguments.delta', output_index=0, delta='{}'),
        dict(type='response.completed', response=completed),
    ]
    wire = ''.join(f"event: {event['type']}\ndata: {json.dumps(event)}\n\n" for event in events).encode()

    class Upstream(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def handle(self):
            try:
                super().handle()
            except ConnectionResetError:
                pass  # The isolated backend closes keep-alive sockets on exit.

        def log_message(self, *args):
            pass

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            assert payload['stream'] is True  # Codex always streams upstream.
            self.send_response(200)
            # Deliberately omit Content-Type, matching the failing installation.
            self.send_header('Content-Length', str(len(wire)))
            self.end_headers()
            self.wfile.write(wire)

    mock = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    data = root / 'target' / ('missing-sse-type-' + uuid.uuid4().hex)
    data.mkdir()
    auth = data / 'oauth'
    auth.mkdir()
    (auth / 'codex-test.json').write_text(json.dumps(dict(type='codex', access_token='fixture-token',
        base_url=f'http://127.0.0.1:{mock.server_port}', models=['header-test'])), encoding='utf-8')
    port = free_port()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, payload=None):
        req = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={'Authorization': 'Bearer ' + ('test-client' if path.startswith('/v1/') else 'test-admin'),
                     'Content-Type': 'application/json'})
        with client.open(req, timeout=15) as response:
            return response.status, response.headers, response.read().decode()

    try:
        with (data / 'server.log').open('wb') as log:
            process = subprocess.Popen(['java', '-jar', str(root / 'target/java-llm-proxy-1.0.0.jar'),
                f'--server.port={port}', f'--proxy.data-dir={data}', f'--proxy.config={data}/config.yaml',
                f'--proxy.auth-dir={auth}', f'--proxy.logs-dir={data}/logs',
                '--proxy.management-token=test-admin', '--proxy.api-key=test-client'],
                stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            try:
                for _ in range(100):
                    try:
                        request('/api/status')
                        break
                    except OSError:
                        if process.poll() is not None:
                            raise AssertionError(f'Backend exited; see {data}/server.log')
                        time.sleep(.1)
                for stream in (True, False):
                    status, headers, body = request('/v1/messages', dict(model='header-test', stream=stream,
                        max_tokens=100, messages=[dict(role='user', content='Weather?')]))
                    assert status == 200, (status, body)
                    assert 'weather' in body and 'call_test' in body and 'tool_use' in body, body
                    if stream:
                        assert 'text/event-stream' in headers['Content-Type'], headers
                        assert 'message_stop' in body, body
                    else:
                        assert json.loads(body)['stop_reason'] == 'tool_use', body
                    detail = None
                    for _ in range(50):
                        try:
                            detail = json.loads(request('/api/usage/detail?id=' + headers['x-request-id'])[2])['data']
                            break
                        except OSError:
                            time.sleep(.1)
                    assert detail and int(detail['status']) == 200, detail
                    assert int(detail['inputTokens']) == 6029 and int(detail['outputTokens']) == 31, detail
                    assert detail['upstreamResponseBody'] == wire.decode()
                    assert detail['responseBody'] == body
                print('PASS: headerless Codex SSE -> Anthropic streaming/non-streaming tool calls; stored HTTP 200 and token usage.')
            finally:
                process.terminate()
                process.wait(timeout=15)
    finally:
        mock.shutdown()
        mock.server_close()


if __name__ == '__main__':
    run()
