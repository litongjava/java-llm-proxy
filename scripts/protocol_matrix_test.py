"""Exercise all four protocol pairs over real HTTP with raw and delivered response capture."""
import json
import subprocess
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from oauth_integration_test import free_port

PROTOCOLS = ('openai', 'anthropic', 'gemini', 'responses')
def request_body(protocol, stream):
    payload = {'model': 'matrix-model', 'stream': stream}
    schema = {'type': 'object', 'properties': {'count': {'type': 'integer', 'maximum': 9007199254740991,
              'minimum': -9007199254740991, 'default': 9007199254740991},
              'label': {'type': 'string', 'enum': ['9007199254740991']}}}
    function = {'name': 'DesignSync', 'description': 'Numeric schema regression', 'parameters': schema}
    if protocol == 'anthropic':
        payload['tools'] = [{'name': 'DesignSync', 'input_schema': schema}]
    elif protocol == 'gemini':
        payload['tools'] = [{'functionDeclarations': [function]}]
    elif protocol == 'responses':
        payload['tools'] = [{'type': 'function', **function}]
    else:
        payload['tools'] = [{'type': 'function', 'function': function}]
    if protocol in ('openai', 'anthropic'):
        payload['messages'] = [{'role': 'user', 'content': 'matrix prompt'}]
        payload['max_tokens'] = 200
    elif protocol == 'gemini':
        payload['contents'] = [{'role': 'user', 'parts': [{'text': 'matrix prompt'}]}]
    else:
        payload['input'] = 'matrix prompt'
    return payload

def endpoint(protocol, stream):
    return {'openai': '/v1/chat/completions', 'anthropic': '/v1/messages', 'responses': '/v1/responses',
            'gemini': '/v1beta/models/matrix-model:' + ('streamGenerateContent' if stream else 'generateContent')}[protocol]

def upstream_body(protocol, stream):
    usage = {'input_tokens': 9, 'output_tokens': 4}
    if not stream:
        return json.dumps({
            'openai': {'choices': [{'message': {'role': 'assistant', 'content': 'matrix answer'}, 'finish_reason': 'stop'}], 'usage': {'prompt_tokens': 9, 'completion_tokens': 4}},
            'anthropic': {'id': 'msg_matrix', 'type': 'message', 'role': 'assistant', 'content': [{'type': 'text', 'text': 'matrix answer'}], 'stop_reason': 'end_turn', 'usage': usage},
            'gemini': {'candidates': [{'content': {'role': 'model', 'parts': [{'text': 'matrix answer'}]}, 'finishReason': 'STOP'}], 'usageMetadata': {'promptTokenCount': 9, 'candidatesTokenCount': 4}},
            'responses': {'id': 'resp_matrix', 'object': 'response', 'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant', 'content': [{'type': 'output_text', 'text': 'matrix answer'}]}], 'usage': usage}
        }[protocol]).encode()
    events = {
        'openai': [{'choices': [{'delta': {'content': 'matrix answer'}}]}, {'choices': [{'delta': {}, 'finish_reason': 'stop'}], 'usage': {'prompt_tokens': 9, 'completion_tokens': 4}}],
        'anthropic': [{'type': 'message_start', 'message': {'usage': {'input_tokens': 9}}}, {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'text_delta', 'text': 'matrix answer'}}, {'type': 'message_delta', 'usage': {'output_tokens': 4}}, {'type': 'message_stop'}],
        'gemini': [{'candidates': [{'content': {'parts': [{'text': 'matrix answer'}]}}]}, {'candidates': [{'finishReason': 'STOP'}], 'usageMetadata': {'promptTokenCount': 9, 'candidatesTokenCount': 4}}],
        'responses': [{'type': 'response.output_text.delta', 'delta': 'matrix answer'}, {'type': 'response.completed', 'response': {'usage': usage}}]
    }[protocol]
    text = ''.join('data: ' + json.dumps(event) + '\n\n' for event in events)
    return (text + ('data: [DONE]\n\n' if protocol == 'openai' else '')).encode()

def run():
    root = Path(__file__).resolve().parents[1]
    selected = ['openai']
    captured = []
    authorizations = []
    rejected_key = [None]
    class Upstream(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def handle(self):
            try: super().handle()
            except ConnectionResetError: pass
        def log_message(self, *args): pass
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            captured.append(request)
            authorizations.append(self.headers.get('Authorization'))
            stream = request.get('stream', False) or ':streamGenerateContent' in self.path
            body = upstream_body(selected[0], stream)
            self.send_response(429 if rejected_key[0] is not None and self.headers.get('Authorization') == rejected_key[0] else 200)
            self.send_header('Content-Type', 'text/event-stream' if stream else 'application/json')
            self.send_header('X-Matrix-Upstream', selected[0])
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    mock = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    data = Path(tempfile.mkdtemp(prefix='matrix-', dir=root/'target'))
    port = free_port()
    def call(path, body=None, client=False, tool=None):
        headers = {'Authorization': 'Bearer '+('matrix-client' if client else 'matrix-admin'), 'Content-Type': 'application/json'}
        if tool:
            headers['x-llm-proxy-tool'] = tool
        request = urllib.request.Request(f'http://127.0.0.1:{port}'+path,
            data=None if body is None else json.dumps(body).encode(),
            headers=headers)
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.read().decode(), response.headers
    with (data/'server.log').open('wb') as log:
        process = subprocess.Popen(['java', '-jar', str(root/'target/java-llm-proxy-1.0.0.jar'), f'--server.port={port}',
            f'--proxy.data-dir={data}', f'--proxy.config={data}/config.yaml', '--proxy.management-token=matrix-admin', '--proxy.api-key=matrix-client'],
            stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            for _ in range(100):
                try: call('/api/status'); break
                except OSError: time.sleep(.1)
            for target in PROTOCOLS:
                selected[0] = target
                base = f'http://127.0.0.1:{mock.server_port}' + ('/v1beta/models' if target == 'gemini' else '/v1')
                call('/api/settings', {'providers': [{'id': 'matrix', 'name': 'Matrix', 'protocol': target, 'baseUrl': base, 'apiKey': 'secret', 'models': ['matrix-model']}]})
                for source in PROTOCOLS:
                    for stream in (False, True):
                        response, headers = call(endpoint(source, stream), request_body(source, stream), True)
                        assert 'matrix answer' in response, (source, target, stream, response)
                        assert 'matrix prompt' in json.dumps(captured[-1]), captured[-1]
                        tool = captured[-1]['tools'][0]
                        schema = (tool['input_schema'] if target == 'anthropic' else
                                  tool['functionDeclarations'][0].get('parametersJsonSchema', tool['functionDeclarations'][0].get('parameters')) if target == 'gemini' else
                                  tool['parameters'] if target == 'responses' else tool['function']['parameters'])
                        count = schema['properties']['count']
                        assert type(count['maximum']) is int and count['maximum'] == 9007199254740991, count
                        assert type(count['minimum']) is int and count['minimum'] == -9007199254740991, count
                        assert type(count['default']) is int and count['default'] == 9007199254740991, count
                        assert schema['properties']['label']['enum'] == ['9007199254740991']
                        request_id = headers['x-request-id']
                        for _ in range(30):
                            try: detail = json.loads(call('/api/usage/detail?id='+request_id)[0])['data']; break
                            except OSError: time.sleep(.1)
                        assert int(detail['inputTokens']) == 9 and int(detail['outputTokens']) == 4, (source, target, stream, detail)
                        assert detail['upstreamResponseBody'] == upstream_body(target, stream).decode(), (source, target, stream)
                        assert detail['responseBody'] == response, (source, target, stream)
            selected[0] = 'openai'
            call('/v8/management/config/routing', {'operation':'PUT', 'value':{'strategy':'round-robin','force-model-prefix':True,'session-affinity':True}})
            call('/v8/management/config/api-keys/openai-compatibility', {'operation':'PUT', 'value':[
                {'name':'Pool','prefix':'tenant','base-url':f'http://127.0.0.1:{mock.server_port}/v1',
                 'models':[{'name':'matrix-model','alias':'alias-model'}], 'keys':[{'api-key':'key-one'}, {'api-key':'key-two'}]}]})
            try:
                call('/v1/chat/completions', {'model':'tenant/alias-model','messages':[]}, True)
                raise AssertionError('Legacy aliases must not be exposed to unidentified clients')
            except urllib.error.HTTPError as error: assert error.code == 503
            for _ in range(2):
                request = request_body('openai', False)
                request.update(model='tenant/alias-model', metadata={'session_id':'matrix-session'})
                call('/v1/chat/completions', request, True, tool='claude-desktop')
                assert captured[-1]['model']=='matrix-model'
            assert authorizations[-1] == authorizations[-2], authorizations[-2:]
            names = [model['id'] for model in json.loads(call('/v1/models', client=True, tool='claude-desktop')[0])['data']]
            assert 'tenant/alias-model' in names, names
            try:
                call('/v1/chat/completions', {'model':'alias-model','messages':[]}, True, tool='claude-desktop')
                raise AssertionError('Unprefixed model must be rejected when force-model-prefix is enabled')
            except urllib.error.HTTPError as error: assert error.code==503
            rejected_key[0] = authorizations[-1]
            before = len(authorizations)
            call('/v1/chat/completions', request, True, tool='claude-desktop')
            assert len(authorizations) == before + 2, authorizations[before:]
            assert authorizations[-1] != rejected_key[0]
            call('/v8/management/config/routing/retry/max-retry-credentials', {'operation':'PUT', 'value':1})
            rejected_key[0] = authorizations[-1]
            before = len(authorizations)
            try:
                call('/v1/chat/completions', request, True, tool='claude-desktop')
                raise AssertionError('Credential retry limit must preserve last upstream failure')
            except urllib.error.HTTPError as error: assert error.code==429
            assert len(authorizations) == before + 1
            print('PASS: 32 protocol HTTP cases, exact response capture, grouped aliases, prefixes, session affinity, credential failover and retry limit')
        finally:
            process.terminate(); process.wait(timeout=15); mock.shutdown()

if __name__ == '__main__': run()
