"""Exercise the real Java HTTP server against a deterministic local upstream."""
import json
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = 'http://127.0.0.1:18317'
ADMIN = 'local-review-token'
KEY = 'local-test-key'
seen = []

class Upstream(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_json({'object': 'list', 'data': [{'id': 'review-model'}]})

    def send_json(self, body, status=200):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Connection', 'close')
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('x-upstream-test', 'preserved')
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        seen.append({'path': self.path, 'body': body, 'authorization': self.headers.get('Authorization')})
        if body.get('model') == 'error-model' or '/models/error-model:' in self.path:
            self.send_json({'error': {'message': 'rate limited', 'type': 'rate_limit'}}, 429)
            return
        if body.get('stream'):
            self.send_response(200)
            self.send_header('Connection', 'close')
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('x-upstream-test', 'preserved')
            self.end_headers()
            for delta in ['你好，', 'stream response']:
                data = json.dumps({'choices': [{'delta': {'content': delta}}]}, ensure_ascii=False)
                self.wfile.write(('data: ' + data + '\n\n').encode())
                self.wfile.flush()
            self.wfile.write(b'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7}}\n\ndata: [DONE]\n\n')
            return
        if self.path.endswith('/messages'):
            self.send_json({'id': 'msg_review', 'role': 'assistant', 'content': [{'type': 'text', 'text': 'Claude response'}], 'usage': {'input_tokens': 14, 'output_tokens': 8}})
        elif ':generateContent' in self.path:
            self.send_json({'candidates': [{'content': {'role': 'model', 'parts': [{'text': 'Gemini response'}]}}], 'usageMetadata': {'promptTokenCount': 15, 'candidatesTokenCount': 9}})
        elif self.path.endswith('/responses'):
            self.send_json({'id': 'resp_review', 'output': [{'type': 'message', 'role': 'assistant', 'content': [{'type': 'output_text', 'text': 'Responses response'}]}], 'usage': {'input_tokens': 13, 'output_tokens': 10}})
        else:
            self.send_json({'id': 'chat_review', 'object': 'chat.completion', 'model': 'review-model', 'choices': [{'message': {'role': 'assistant', 'content': '你好，proxy response'}}], 'usage': {'prompt_tokens': 11, 'completion_tokens': 6}})

def call(path, data=None, token=ADMIN, tool=None):
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if tool:
        headers['x-llm-proxy-tool'] = tool
    request = urllib.request.Request(BASE + path, data=None if data is None else json.dumps(data).encode(), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, response.read().decode(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(), dict(error.headers)

def api(path, data=None):
    status, text, _ = call('/api/' + path, data)
    value = json.loads(text)
    assert status == 200 and value['code'] == 1, (path, status, value)
    return value.get('data')

def run():
    server = ThreadingHTTPServer(('127.0.0.1', 18318), Upstream)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        assert call('/api/status', token='incorrect')[0] == 401
        api('status')
        settings = api('settings')
        for protocol, prefix in [('openai','/v1'),('anthropic','/v1'),('gemini','/v1beta/models'),('responses','/v1')]:
            existing = next((p for p in settings['providers'] if p['name']=='Review '+protocol), {})
            api('providers/save', {'id':existing.get('id'),'name':'Review '+protocol,'protocol':protocol,'baseUrl':'http://127.0.0.1:18318'+prefix,'apiKey':'upstream-secret','models':[],'inputPrice':1,'outputPrice':2})
        assert all(p['apiKey']=='********' for p in api('providers'))
        created = api('keys/create', {'name':'integration-key'})
        assert created['key'].startswith('sk-proxy-')
        status, body, headers = call('/v1/chat/completions', {'model':'review-model','messages':[{'role':'user','content':'测试提示词'}],'custom_field':{'keep':True}}, KEY)
        assert status == 200, body
        assert json.loads(body)['choices'][0]['message']['content']=='你好，proxy response'
        assert seen[-1]['body']['custom_field']=={'keep':True}
        assert seen[-1]['authorization']=='Bearer upstream-secret'
        request_id = headers.get('x-request-id') or headers.get('X-Request-Id')
        detail = api('usage/detail?id='+request_id)
        assert detail['requestHeaders']['authorization']=='[REDACTED]'
        assert int(detail['inputTokens'])==11 and int(detail['outputTokens'])==6
        assert 'upstream-secret' not in json.dumps(detail)
        formatted = api('usage/format?id='+request_id)
        assert '测试提示词' in formatted['prompt'] and 'proxy response' in formatted['response']
        status, body, headers = call('/v1/chat/completions', {'model':'review-model','stream':True,'messages':[{'role':'user','content':'stream prompt'}]}, KEY)
        assert status==200 and '[DONE]' in body and 'stream response' in body, (status,body)
        for path, payload, expected in [('/v1/messages', {'model':'review-model','system':'系统提示','messages':[{'role':'user','content':'Claude prompt'}]},'Claude response'),('/v1beta/models/review-model:generateContent', {'contents':[{'role':'user','parts':[{'text':'Gemini prompt'}]}]},'Gemini response'),('/v1/responses',{'model':'review-model','input':'Responses prompt'},'Responses response')]:
            status,body,headers = call(path,payload,KEY)
            assert status==200 and expected in body, (path,status,body)
            detail_id = headers.get('x-request-id') or headers.get('X-Request-Id')
            assert expected in api('usage/format?id='+detail_id)['response']
        assert call('/v1/chat/completions',{'model':'error-model','messages':[]},KEY)[0]==429
        assert call('/v1/chat/completions',{'messages':[]},KEY)[0]==400
        assert call('/v1/chat/completions',{'model':'review-model'},'wrong')[0]==401
        assert call('/api/usage?page=-1')[0]==400
        assert int(api('usage?result=error')['stats']['total'])>=1
        assert int(api('usage')['stats']['inputTokens'])>=65
        queue_path = '/v8/management/observability/usage/queue?count=10000'
        assert call(queue_path, token=KEY)[0] == 401
        batch = json.loads(call(queue_path)[1])['data']
        repeated = json.loads(call(queue_path)[1])['data']
        assert batch == repeated and batch, 'Reading must not destructively consume usage records'
        assert isinstance(batch[0]['latency_ms'], int)
        assert isinstance(batch[0]['tokens']['input_tokens'], int)
        acknowledged = batch[0]['request_id']
        assert call('/v8/management/observability/usage/ack', {'ids':[acknowledged]})[0] == 200
        assert acknowledged not in [item['request_id'] for item in json.loads(call(queue_path)[1])['data']]
        assert api('usage/detail?id='+acknowledged)['id'] == acknowledged
        assert call('/v8/management/observability/usage/ack', {'ids':[acknowledged]})[0] == 200

        original = api('settings')
        uncaptured = dict(original, captureBodies=False)
        api('settings', uncaptured)
        for stream in [False, True]:
            status, body, headers = call('/v1/chat/completions', {'model':'review-model','stream':stream,'messages':[]}, KEY)
            assert status == 200
            detail_id = headers.get('x-request-id') or headers.get('X-Request-Id')
            for attempt in range(20):
                try:
                    detail = api('usage/detail?id='+detail_id)
                    break
                except AssertionError:
                    time.sleep(0.05)
            assert detail['responseBody'] is None
            assert int(detail['inputTokens']) == (12 if stream else 11), 'Usage must survive disabled body capture'
        api('settings', original)

        config_path = '/v8/management/config'
        old_config = json.loads(call(config_path)[1])['data']
        group = [{'name':'Desktop test', 'base-url':'http://127.0.0.1:18318/v1',
                  'keys':[{'api-key':'desktop-upstream'}],
                  'models':[{'name':'review-model','alias':'desktop-alias'}],
                  'custom-extension':{'preserved':True}}]
        try:
            path = config_path + '/api-keys/openai-compatibility'
            assert call(path, {'operation':'PUT','value':group})[0] == 200
            assert json.loads(call(path)[1])['data'] == group
            # Legacy aliases belong to Claude Desktop, not unidentified clients.
            assert call('/v1/chat/completions', {'model':'desktop-alias','messages':[]}, KEY)[0] == 503
            status, body, headers = call('/v1/chat/completions', {'model':'desktop-alias','messages':[]}, KEY, tool='claude-desktop')
            assert status == 200, body
            assert seen[-1]['authorization'] == 'Bearer desktop-upstream'
            assert seen[-1]['body']['model'] == 'review-model'
        finally:
            call(config_path, {'operation':'PUT','value':old_config})
            api('settings', original)
        print('PASS: original desktop config contract, hot reload, aliases, durable usage acknowledgement and capture-independent usage')
        print('PASS: auth, provider secrets, key creation, raw fields, sync/SSE, four protocols, format, usage, upstream errors and validation')
    finally:
        server.shutdown()

if __name__=='__main__':
    run()
