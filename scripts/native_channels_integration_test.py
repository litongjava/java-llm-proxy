"""Local-only native channel contract tests against an isolated packaged JVM.

Run after packaging: python scripts/native_channels_integration_test.py
No browser, real provider, SMS or existing server is used. Failure logs remain in target.
"""
import base64
import hashlib
import hmac
import json
import os
import socket
import subprocess
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def free_port():
    while True:
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        if port != 8318:
            return port


class Upstream(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    calls = []
    failures = []

    def handle(self):
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            pass

    def log_message(self, *args):
        pass

    def reply(self, value, status=200, content_type='application/json'):
        data = (value if isinstance(value, str) else json.dumps(value)).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def dispatch(self):
        try:
            self.route()
        except (ConnectionResetError, BrokenPipeError):
            pass
        except Exception as error:
            self.failures.append((self.path, traceback.format_exc()))
            self.reply({'error': 'mock contract assertion failed'}, 500)

    def route(self):
        path = urllib.parse.urlsplit(self.path).path
        raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        body = (json.loads(raw) if 'application/json' in self.headers.get('Content-Type', '')
                else dict(urllib.parse.parse_qsl(raw.decode()))) if raw else {}
        self.calls.append((self.command, path, body))
        provider, endpoint = path.strip('/').split('/', 1)
        endpoint = '/' + endpoint
        model = {'minimax': 'MiniMax-M3.1-test', 'zcode': 'glm-5.2'}.get(provider, provider + '-test')
        if provider == 'codearts':
            if endpoint == '/token':
                dpop = self.headers['DPoP'].split('.')
                proof = json.loads(base64.urlsafe_b64decode(dpop[1] + '=' * (-len(dpop[1]) % 4)))
                assert proof['htm'] == 'POST' and proof['htu'].endswith('/codearts/token') and proof['jti']
                assert body['code_verifier'] and body['client_id'] == 'codearts-agent'
                return self.reply({'credentials': {'access_key_id': 'local-ak', 'secret_access_key': 'local-sk',
                                   'security_token': 'local-st', 'expiration': '2099-01-01T00:00:00Z'}, 'refresh_token': 'codearts-refresh'})
            self.verify_huawei(raw)
            if endpoint in ('/gateway-models', '/builtin-models'):
                rows = [{'model_id': model, 'model_name': model}]
                return self.reply({'result': {'models': rows}} if endpoint == '/gateway-models' else {'builtinModels': rows})
        if provider == 'raccoon':
            if endpoint in ('/poll', '/refresh'):
                if endpoint == '/poll':
                    assert len(body['qrcode_code']) == 32
                else:
                    assert body['refresh_token'] == 'raccoon-refresh'
                return self.reply({'code': 0, 'data': {'status': 'success', 'access_token': 'raccoon-access',
                                   'refresh_token': 'raccoon-refresh', 'office_identity': 'local-org'}})
            if endpoint == '/models':
                assert self.headers['Authorization'] == 'Bearer raccoon-access'
                assert self.headers['X-Org-Code'] == 'local-org'
                return self.reply({'code': 0, 'data': {'categories': [{'type': 'chat', 'models': [{'name': model, 'visible': True}]}]}})
        if provider == 'zcode':
            if endpoint.endswith('/cli/init'):
                assert body['provider'] == 'bigmodel' and len(self.headers['Authorization']) > 60
                return self.reply({'code': 0, 'data': {'flow_id': 'local-zcode-flow', 'authorize_url': 'https://example.test/authorize', 'poll_interval_sec': 1}})
            if '/cli/poll/' in endpoint:
                return self.reply({'code': 0, 'data': {'status': 'ready', 'token': 'zcode-access',
                                   'user': {'id': 'zcode-user'}, 'bigmodel': {'access_token': 'third-party-token'}}})
            if endpoint == '/configs':
                return self.reply({'code': 0, 'data': {'builtinModels': [{'modelId': model, 'name': model}]}})
            if endpoint == '/messages' and any('challenge-test' in json.dumps(message) for message in body.get('messages', [])):
                return self.reply({'code': 3007, 'message': 'User verification required'}, 403)
        if provider in ('minimax', 'zcode'):
            if endpoint == '/device':
                assert body['code_challenge_method'] == 'S256' and body['audience'] == 'agent-backend'
                return self.reply({'device_code': 'minimax-device', 'user_code': 'MINI-TEST',
                                   'verification_uri': 'https://example.test/verify', 'interval': 1, 'expires_in': 300})
            if endpoint == '/token':
                if body['grant_type'] != 'refresh_token':
                    assert body['device_code'] == 'minimax-device' and body['code_verifier']
                return self.reply({'access_token': 'minimax-access', 'refresh_token': 'minimax-refresh',
                                   'token_type': 'Bearer', 'scope': 'agent.default', 'expires_in': 3600})
            if endpoint == '/models':
                assert self.headers['Authorization'] == 'Bearer minimax-access'
                return self.reply({'providers': [{'providerId': 'minimax', 'config': {'models': {model: {'name': model}}}}]})
            if endpoint == '/messages':
                assert body['stream'] is True
                if provider == 'minimax':
                    assert body['thinking']['type'] == 'adaptive'
                assert self.headers['Authorization'] == 'Bearer ' + provider + '-access'
                events = [
                    ('message_start', {'type': 'message_start', 'message': {'id': 'msg-test', 'type': 'message', 'role': 'assistant', 'model': model, 'content': [], 'usage': {'input_tokens': 3, 'output_tokens': 0}}}),
                    ('content_block_start', {'type': 'content_block_start', 'index': 0, 'content_block': {'type': 'text', 'text': ''}}),
                    ('content_block_delta', {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'text_delta', 'text': provider + ' works'}}),
                    ('content_block_stop', {'type': 'content_block_stop', 'index': 0}),
                    ('message_delta', {'type': 'message_delta', 'delta': {'stop_reason': 'end_turn'}, 'usage': {'output_tokens': 2}}),
                    ('message_stop', {'type': 'message_stop'}),
                ]
                return self.reply(''.join('event: ' + name + '\ndata: ' + json.dumps(value) + '\n\n' for name, value in events), content_type='text/event-stream')
        if provider in ('buddy', 'workbuddy'):
            assert self.headers['X-Product-Code'] == ('codebuddy' if provider == 'buddy' else 'workbuddy')
            if endpoint == '/v2/plugin/auth/state':
                assert self.command == 'POST'
                return self.reply({'code': 0, 'data': {'state': provider + '-state', 'authUrl': 'https://example.test/authorize'}})
            if endpoint in ('/v2/plugin/auth/token', '/v2/plugin/auth/token/refresh'):
                if endpoint.endswith('/refresh'):
                    assert self.headers['X-Refresh-Token'] == provider + '-refresh'
                return self.reply({'code': 0, 'data': {'accessToken': provider + '-access', 'refreshToken': provider + '-refresh', 'expiresIn': 3600}})
            if endpoint == '/v2/plugin/login/account':
                assert self.headers['Authorization'] == 'Bearer ' + provider + '-access'
                return self.reply({'code': 0, 'data': {'uid': provider + '-user', 'nickname': provider, 'type': 'personal'}})
            if endpoint in ('/console/enterprises/personal/models', '/v3/config'):
                return self.reply({'code': 0, 'data': {'models': [{'id': model, 'name': model}]}})
        if provider == 'lobsterai':
            if endpoint in ('/api/auth/exchange', '/api/auth/refresh'):
                assert self.headers.get('Authorization') is None
                assert body['uuid'] and body['firstKeyfrom'] and body['latestKeyfrom'] and body['version'] == '2026.9.4'
                if endpoint.endswith('/exchange'):
                    assert body['authCode'] == 'local-auth-code'
                return self.reply({'code': 0, 'data': {'accessToken': 'lobsterai-access', 'refreshToken': 'lobsterai-refresh',
                                   'expiresIn': 3600, 'user': {'id': 'lobster-user', 'yid': 'lobster-yid'}}})
            if endpoint == '/api/models/available':
                assert 'thinking-level-control-v1' in self.headers['X-LobsterAI-Client-Capabilities']
                return self.reply({'code': 0, 'data': [{'modelId': model, 'modelName': model}]})
        if provider == 'trae':
            if endpoint.endswith('/ExchangeToken'):
                assert body['RefreshToken'] == 'trae-refresh' and body['ClientSecret'] == '-' and body['UserID'] == ''
                return self.reply({'Result': {'Token': 'trae-access', 'RefreshToken': 'trae-refresh', 'TokenExpireDuration': 3600}})
            if endpoint.endswith('/GetUserInfo'):
                assert self.headers['X-Cloudide-Token'] == 'trae-access'
                return self.reply({'Result': {'UserID': 'trae-user', 'ScreenName': 'Local TRAE'}})
            if endpoint.endswith('/batch_get_detail_param'):
                assert 'solo_agent' in body['functions'] and 'chat' in body['functions']
                return self.reply({'function_configs': [{'function': 'solo_agent', 'config_info_list': [
                    {'config_name': model, 'config_switch': True, 'usage': 'chat_completion', 'display_config': {'display_name': model}}]}]})
            if endpoint.endswith('/llm_utils_chat'):
                assert self.headers['Authorization'] == 'Cloud-IDE-JWT trae-access'
                assert self.headers['X-Uid'] == 'trae-user' and self.headers['X-Machine-Id']
                assert body['function'] == 'solo_agent' and body['config_name'] == model and body['stream'] is True
                assert body['messages'][0]['content'][0]['type'] == 'text'
                return self.reply('event: output\ndata: {"response":"trae works"}\n\nevent: token_usage\ndata: {"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}\n\nevent: done\ndata: {"finish_reason":"stop"}\n\n', content_type='text/event-stream')
        if provider == 'loomy':
            if endpoint.startswith('/login/phone/'):
                canonical_path = endpoint
                md5 = base64.b64encode(hashlib.md5(raw).digest()).decode()
                canonical = '\n'.join(['POST', canonical_path, '', md5, 'application/json', self.headers['Date'], self.headers['Nonce'], '', ''])
                signature = base64.b64encode(hmac.new(b'local-secret', canonical.encode(), hashlib.sha1).digest()).decode()
                assert self.headers['Authorization'] == 'account local-id:' + signature
                assert self.headers['Content-MD5'] == md5
                assert body['param']['phone'] == '13800000000'
                if endpoint.endswith('/sendMsgCode'):
                    return self.reply({'code': '000000', 'data': {'msgid': 'local-message'}})
                assert body['param']['mcode'] == '123456' and body['param']['msgid'] == 'local-message'
                return self.reply({'code': '000000', 'data': {'session': 'loomy-access', 'userid': 'loomy-user'}})
            if endpoint == '/models':
                assert self.headers['token'] == 'loomy-access'
                return self.reply({'code': '000000', 'data': [{'id': model, 'type': 'chat', 'name': model}]})
        if endpoint.endswith('/chat/completions'):
            if provider != 'codearts':
                assert self.headers['Authorization'] == 'Bearer ' + provider + '-access'
            else:
                assert body['tool_stream'] is True and self.headers['maas_type'] == 'benefit'
            if provider == 'loomy':
                assert self.headers['token'] == 'loomy-access'
            else:
                assert body['stream'] is True
            if body.get('stream'):
                chunks = [{'id': 'chat-test', 'model': model, 'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': provider + ' works'}}]},
                          {'id': 'chat-test', 'model': model, 'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}], 'usage': {'prompt_tokens': 3, 'completion_tokens': 2, 'total_tokens': 5}}]
                return self.reply(''.join('data: ' + json.dumps(chunk) + '\n\n' for chunk in chunks) + 'data: [DONE]\n\n', content_type='text/event-stream')
            return self.reply({'id': 'chat-test', 'object': 'chat.completion', 'model': model,
                               'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': provider + ' works'}, 'finish_reason': 'stop'}]})
        raise AssertionError('Unexpected local upstream endpoint: ' + path)

    def verify_huawei(self, raw):
        authorization = self.headers['Authorization']
        assert authorization.startswith('SDK-HMAC-SHA256 Access=local-ak,')
        fields = dict(item.split('=', 1) for item in authorization.removeprefix('SDK-HMAC-SHA256 ').split(','))
        names = fields['SignedHeaders'].split(';')
        payload_hash = hashlib.sha256(raw).hexdigest()
        assert self.headers['x-sdk-content-sha256'] == payload_hash
        assert self.headers['x-security-token'] == 'local-st'
        url = urllib.parse.urlsplit(self.path)
        path = url.path if url.path.endswith('/') else url.path + '/'
        query = '&'.join(urllib.parse.quote(key, safe='~') + '=' + urllib.parse.quote(value, safe='~')
                         for key, value in sorted(urllib.parse.parse_qsl(url.query)))
        header_lines = ''.join(name + ':' + self.headers[name].strip() + '\n' for name in names)
        canonical = '\n'.join([self.command, path, query, header_lines, ';'.join(names), payload_hash])
        to_sign = '\n'.join(['SDK-HMAC-SHA256', self.headers['x-sdk-date'], hashlib.sha256(canonical.encode()).hexdigest()])
        expected = hmac.new(b'local-sk', to_sign.encode(), hashlib.sha256).hexdigest()
        assert hmac.compare_digest(expected, fields['Signature']), ('Huawei canonical headers differ from transmitted headers', header_lines)


def run():
    root = Path(__file__).resolve().parents[1]
    jar = root / 'target/java-llm-proxy-1.0.0.jar'
    assert jar.exists(), 'Package the Java application first'
    port, lobster_port, trae_port = free_port(), free_port(), free_port()
    assert len({port, lobster_port, trae_port}) == 3
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    mock = f'http://127.0.0.1:{upstream.server_port}'
    base = f'http://127.0.0.1:{port}'
    directory = Path(tempfile.mkdtemp(prefix='native-channels-integration-', dir=root / 'target'))
    config = {
        'server.port': port, 'proxy.data-dir': str(directory / 'data'), 'proxy.config': str(directory / 'config.yaml'),
        'proxy.auth-dir': str(directory / 'auth'), 'proxy.management-token': 'native-admin', 'proxy.api-key': 'native-client',
        'proxy.oauth.minimax.device-url': mock + '/minimax/device', 'proxy.oauth.minimax.token-url': mock + '/minimax/token',
        'proxy.oauth.minimax.models-url': mock + '/minimax/models', 'proxy.oauth.minimax.api-base-url': mock + '/minimax',
        'proxy.oauth.buddy.origin': mock + '/buddy', 'proxy.oauth.workbuddy.origin': mock + '/workbuddy',
        'proxy.oauth.lobsterai.api-base-url': mock + '/lobsterai', 'proxy.oauth.lobsterai.portal-base-url': mock + '/lobsterai',
        'proxy.oauth.lobsterai.client-version': '2026.9.4', 'proxy.oauth.lobsterai.redirect-uri': f'http://127.0.0.1:{lobster_port}/auth/callback',
        'proxy.oauth.trae.api-base-url': mock + '/trae', 'proxy.oauth.trae.oauth-base-url': mock + '/trae',
        'proxy.oauth.trae.console-base-url': mock + '/trae', 'proxy.oauth.trae.redirect-uri': f'http://127.0.0.1:{trae_port}/authorize',
        'proxy.oauth.loomy.account-base-url': mock + '/loomy', 'proxy.oauth.loomy.api-base-url': mock + '/loomy',
        'proxy.oauth.loomy.access-key-id': 'local-id', 'proxy.oauth.loomy.access-key-secret': 'local-secret',
        'proxy.oauth.codearts.authorize-url': mock + '/codearts/authorize', 'proxy.oauth.codearts.token-url': mock + '/codearts/token',
        'proxy.oauth.codearts.api-base-url': mock + '/codearts', 'proxy.oauth.codearts.gateway-models-url': mock + '/codearts/gateway-models',
        'proxy.oauth.codearts.builtin-models-url': mock + '/codearts/builtin-models',
        'proxy.oauth.raccoon.site-url': mock + '/raccoon', 'proxy.oauth.raccoon.api-base-url': mock + '/raccoon',
        'proxy.oauth.raccoon.poll-url': mock + '/raccoon/poll', 'proxy.oauth.raccoon.refresh-url': mock + '/raccoon/refresh',
        'proxy.oauth.raccoon.models-url': mock + '/raccoon/models',
        'proxy.oauth.zcode.origin': mock + '/zcode', 'proxy.oauth.zcode.api-base-url': mock + '/zcode',
        'proxy.oauth.zcode.config-url': mock + '/zcode/configs',
    }
    java = Path(os.environ.get('JAVA_HOME', 'D:/java/jdk-21.0.6')) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    args = [str(java), '-jar', str(jar)] + [f'--{key}={value}' for key, value in config.items()]

    def request(path, body=None, token='native-admin'):
        url = path if path.startswith('http://127.0.0.1:') else base + path
        req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                data = response.read().decode()
                return response.status, json.loads(data) if data.startswith('{') else data
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    def management(path, body=None):
        status, value = request('/v8/management/' + path, body)
        assert status == 200 and isinstance(value, dict) and value.get('ok'), (path, status, value, Upstream.failures)
        return value['data']

    def wait_login(state):
        for _ in range(60):
            result = management('oauth/status?state=' + urllib.parse.quote(state))
            if result['status'] == 'ok':
                return
            assert result['status'] in ('wait', 'exchanging'), (result, Upstream.failures)
            time.sleep(.15)
        raise AssertionError('Local authorization did not complete')

    with (directory / 'server.log').open('wb') as log:
        process = subprocess.Popen(args, cwd=directory, stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for _ in range(150):
                if process.poll() is not None:
                    raise AssertionError('Java exited; inspect ' + str(directory / 'server.log'))
                try:
                    if request('/api/status')[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(.1)
            else:
                raise AssertionError('Java did not start; inspect ' + str(directory / 'server.log'))
            for provider in ('minimax', 'buddy', 'workbuddy', 'lobsterai', 'trae', 'loomy', 'raccoon', 'zcode', 'codearts'):
                calls_before = len(Upstream.calls)
                assert request('/v8/management/credentials', token='native-client')[0] == 401
                assert request('/v8/management/oauth/auth-url?provider=' + provider, token='invalid')[0] == 401
                assert len(Upstream.calls) == calls_before, 'Unauthorized management requests contacted an upstream'
                if provider == 'loomy':
                    assert request('/v8/management/oauth/loomy/sms', {'phone': '13800000000'}, 'native-client')[0] == 401
                    assert len(Upstream.calls) == calls_before, 'Unauthorized request sent an SMS'
                    sms = management('oauth/loomy/sms', {'phone': '13800000000'})
                    assert isinstance(sms, dict), ('Loomy SMS response must be a JSON object', sms)
                    management('oauth/loomy/login', {'phone': '13800000000', 'msgid': sms['msgid'], 'code': '123456'})
                else:
                    start = management('oauth/auth-url?provider=' + provider)
                    if provider == 'codearts':
                        params = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(start['url']).query))
                        assert params['code_challenge_method'] == 'SHA-256' and params['ticket_id']
                        callback = 'http://127.0.0.1:' + params['port'] + '/oauth/callback'
                        assert request(callback + '?invalid=true')[0] == 400
                        status, result = request(callback + '?code=local-codearts-code')
                        assert status == 200, (status, result, Upstream.failures)
                    elif provider == 'lobsterai':
                        callback = f'http://127.0.0.1:{lobster_port}/auth/callback'
                        assert request(callback + '?code=bad&state=invalid')[0] == 400
                        status, result = request(callback + '?' + urllib.parse.urlencode({'code': 'local-auth-code', 'state': start['state']}))
                        assert status == 200, (status, result)
                    elif provider == 'trae':
                        params = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(start['url']).query))
                        callback = params['auth_callback_url']
                        assert request(callback + '?login_trace_id=invalid&refreshToken=bad')[0] == 400
                        query = {'login_trace_id': params['login_trace_id'], 'refreshToken': 'trae-refresh',
                                 'userInfo': json.dumps({'UserID': 'trae-user', 'ScreenName': 'Local TRAE'})}
                        status, result = request(callback + '?' + urllib.parse.urlencode(query))
                        assert status == 200, (status, result)
                    wait_login(start['state'])
                files = management('credentials')['files']
                account = next(item for item in files if item['type'] == provider)
                assert provider + '-access' not in json.dumps(files)
                if provider not in ('loomy', 'zcode'):
                    refreshed = management('credentials/refresh', {'name': account['name']})
                    assert refreshed['success'] == 1 and refreshed['failed'] == 0, (provider, refreshed, Upstream.failures)
                model = {'minimax': 'MiniMax-M3.1-test', 'zcode': 'glm-5.2'}.get(provider, provider + '-test')
                payload = {'model': model, 'messages': [{'role': 'user', 'content': 'hello'}], 'max_tokens': 64}
                status, answer = request('/v1/chat/completions', payload, 'native-client')
                assert status == 200 and answer['choices'][0]['message']['content'] == provider + ' works', (provider, status, answer, Upstream.failures)
                status, stream = request('/v1/chat/completions', dict(payload, stream=True), 'native-client')
                assert status == 200 and provider + ' works' in stream and '[DONE]' in stream, (provider, status, stream, Upstream.failures)
                if provider == 'zcode':
                    before = len([call for call in Upstream.calls if call[1] == '/zcode/messages'])
                    status, error = request('/v1/chat/completions', dict(payload, messages=[{'role': 'user', 'content': 'challenge-test'}]), 'native-client')
                    after = len([call for call in Upstream.calls if call[1] == '/zcode/messages'])
                    assert status == 403 and 'user-completed captcha' in str(error), (status, error)
                    assert after - before == 1, 'ZCode captcha challenge triggered an automatic retry'
                assert not Upstream.failures, Upstream.failures
                print('PASS', provider, 'authorization, management auth, models, JSON/SSE inference', flush=True)
            print(f'PASS native channel integration; Java port={port}, mock port={upstream.server_port}; logs={directory}')
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            upstream.shutdown()
            upstream.server_close()


if __name__ == '__main__':
    run()
