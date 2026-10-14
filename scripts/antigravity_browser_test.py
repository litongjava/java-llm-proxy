"""Visible browser harness for live Antigravity protocol checks (uses an isolated Java core).

Run only with credentials authorized for live testing. No tools are executed locally.
The browser receives sanitized results; credentials remain in the server process.
"""
import argparse
import json
import os
import secrets
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def schema_errors(schema, path='parameters'):
    if not isinstance(schema, dict):
        return []
    errors = [path + '.' + key for key in ('$schema', 'propertyNames', 'exclusiveMinimum', 'exclusiveMaximum', 'const') if key in schema]
    for key, child in schema.get('properties', {}).items():
        errors += schema_errors(child, path + '.properties.' + key)
    for key in ('items', 'additionalProperties'):
        errors += schema_errors(schema.get(key), path + '.' + key)
    for key in ('anyOf', 'oneOf', 'allOf'):
        for index, child in enumerate(schema.get(key, [])):
            errors += schema_errors(child, path + '.' + key + str(index))
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--database', required=True)
    parser.add_argument('--request-id', default='700021355011530752')
    parser.add_argument('--jar', default='target/java-llm-proxy-1.0.0.jar')
    parser.add_argument('--port', type=int, default=18770)
    parser.add_argument('--mock', action='store_true', help='Use a strict local upstream instead of consuming live quota')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    with sqlite3.connect(Path(args.database).resolve().as_uri() + '?mode=ro', uri=True) as connection:
        row = connection.execute('select body from requests where id=?', (args.request_id,)).fetchone()
    if not row:
        raise RuntimeError('Source request not found')
    source = json.loads(row[0])
    client = json.loads(source['requestBody'])
    run_dir = Path(tempfile.mkdtemp(prefix='antigravity-browser-', dir=root / 'target'))
    token = secrets.token_urlsafe(32)
    core_port = free_port()
    config = Path(args.config).resolve()
    mock_server = None
    if args.mock:
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                request = body.get('request', {})
                declarations = (request.get('tools') or [{}])[0].get('functionDeclarations', [])
                errors = sum((schema_errors(tool.get('parameters')) for tool in declarations), [])
                if 'metadata' in request:
                    errors.append('request.metadata')
                if len(declarations) != len(client['tools']):
                    errors.append('tool count changed')
                if body.get('model') != 'claude-sonnet-4-6':
                    errors.append('wrong upstream model')
                result = {'response': {'candidates': [{'content': {'role': 'model', 'parts': [{'text': 'OK'}]}, 'finishReason': 'STOP'}],
                           'usageMetadata': {'promptTokenCount': 10, 'candidatesTokenCount': 1}}}
                if errors:
                    payload = json.dumps({'error': {'code': 400, 'message': 'Invalid JSON payload: ' + ', '.join(errors)}}).encode()
                    status, content_type = 400, 'application/json'
                elif ':streamGenerateContent' in self.path:
                    payload = ('data: ' + json.dumps(result) + '\n\n').encode()
                    status, content_type = 200, 'text/event-stream'
                else:
                    payload = json.dumps(result).encode()
                    status, content_type = 200, 'application/json'
                self.send_response(status)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        mock_server = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        threading.Thread(target=mock_server.serve_forever, daemon=True).start()
        auth = run_dir / 'oauth'
        auth.mkdir()
        (auth / 'antigravity-test.json').write_text(json.dumps({'type': 'antigravity', 'access_token': 'local-test-token',
            'expired': '2099-01-01T00:00:00Z', 'project_id': 'local-test',
            'base_url': f'http://127.0.0.1:{mock_server.server_port}/v1internal'}), encoding='utf-8')
        config = run_dir / 'config.yaml'
        config.write_text(json.dumps({'oauth': {'auth-dir': './oauth'}, 'plugins': {'enabled': False},
            'tool-model-aliases': {'claude-desktop': {'oauth-model-alias': {'antigravity': [
                {'alias': client['model'], 'name': 'claude-sonnet-4-6'}]}}}}), encoding='utf-8')
    env = {key: value for key, value in os.environ.items() if key.lower() not in ('http_proxy', 'https_proxy', 'all_proxy')}
    env['proxy.management-token'] = token
    env['proxy.api-key'] = token
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None):
        request = urllib.request.Request(f'http://127.0.0.1:{core_port}' + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'User-Agent': 'protocol-schema-browser-test'})
        try:
            response = opener.open(request, timeout=90)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read().decode('utf-8')

    state = {'status': 'READY', 'sourceRequest': args.request_id, 'toolCount': len(client['tools']),
             'corePort': core_port, 'results': [], 'scope': 'Strict local mock upstream' if args.mock else 'Live Antigravity upstream'}
    lock = threading.Lock()

    def run_case(stream):
        try:
            body = {'model': client['model'], 'messages': [{'role': 'user', 'content': 'Reply exactly OK. Do not call any tools.'}],
                    'metadata': {'user_id': 'protocol-schema-smoke-test'}, 'tools': client['tools'], 'max_tokens': 64, 'stream': stream}
            status, response = call('/tools/claude-desktop/v1/messages', body)
            record = None
            for _ in range(30):
                with sqlite3.connect((run_dir / 'data/proxy.db').as_uri() + '?mode=ro', uri=True) as connection:
                    rows = connection.execute('select body from requests order by timestamp desc limit 1').fetchall()
                if rows:
                    candidate = json.loads(rows[0][0])
                    if candidate['id'] not in [result['requestId'] for result in state['results']]:
                        record = candidate
                        break
                time.sleep(0.1)
            if record is None:
                raise RuntimeError('Response returned without a recorded request')
            upstream = json.loads(record.get('upstreamRequestBody') or '{}').get('request', {})
            declarations = (upstream.get('tools') or [{}])[0].get('functionDeclarations', [])
            errors = sum((schema_errors(tool.get('parameters')) for tool in declarations), [])
            checks = {'HTTP 200': status == 200, 'Antigravity selected': record['provider'].startswith('antigravity-'),
                      'All tools retained': len(declarations) == len(client['tools']), 'No request metadata': 'metadata' not in upstream,
                      'No rejected schema keywords': not errors, 'OK response': 'OK' in response}
            if stream:
                checks['Anthropic SSE completion'] = '"message_stop"' in response
            result = {'case': 'stream' if stream else 'non-stream', 'status': 'PASS' if all(checks.values()) else 'FAIL',
                      'httpStatus': status, 'requestId': record['id'], 'provider': record['provider'], 'requestedModel': record.get('requestedModel'),
                      'upstreamModel': record['model'], 'tools': len(declarations), 'checks': checks,
                      'response': response[:1600], 'schemaErrors': errors[:10], 'observedAt': time.strftime('%Y-%m-%d %H:%M:%S %z')}
            state['results'].append(result)
            state['status'] = result['status']
        except Exception as error:
            state['status'] = 'ERROR'
            state['error'] = str(error)
        finally:
            (run_dir / 'results.json').write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def send(self, body, content_type='application/json'):
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == '/':
                self.send((root / 'scripts/fixtures/antigravity-protocol.html').read_bytes(), 'text/html; charset=utf-8')
            elif self.path == '/status':
                self.send(json.dumps(state, ensure_ascii=False).encode())
            else:
                self.send_error(404)

        def do_POST(self):
            if self.headers.get('Origin') != f'http://127.0.0.1:{args.port}':
                self.send_error(403)
                return
            if self.path not in ('/run/non-stream', '/run/stream'):
                self.send_error(404)
                return
            with lock:
                if state['status'] == 'RUNNING':
                    self.send_error(409)
                    return
                state.pop('error', None)
                state['status'] = 'RUNNING'
                threading.Thread(target=run_case, args=(self.path.endswith('/stream'),), daemon=True).start()
            self.send(b'{"started":true}')

    with (run_dir / 'core.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen(['java', '-jar', str(Path(args.jar).resolve()), '--proxy.config=' + str(config),
            '--server.address=127.0.0.1', '--server.port=' + str(core_port), '--proxy.data-dir=' + str(run_dir / 'data')],
            cwd=config.parent, env=env, stdout=log, stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            for _ in range(50):
                try:
                    if call('/api/status')[0] == 200:
                        break
                except OSError:
                    time.sleep(0.2)
            else:
                raise RuntimeError('Test core failed to start; see ' + str(run_dir / 'core.log'))
            print(json.dumps({'url': f'http://127.0.0.1:{args.port}', 'results': str(run_dir / 'results.json'), 'corePid': process.pid}), flush=True)
            ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
        finally:
            process.terminate()
            process.wait(timeout=15)
            if mock_server:
                mock_server.shutdown()


if __name__ == '__main__':
    main()
