"""Exercise actual HTTP routes with an isolated proxy and a local Codex OAuth upstream."""
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
    seen = []
    opaque = {'type': 'compaction', 'id': 'cmp-fixture', 'encrypted_content': 'opaque-exact-payload'}
    reasoning = {'type':'reasoning','id':'reasoning-fixture','encrypted_content':'signed-content'}

    class Upstream(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *args): pass
        def handle(self):
            try: super().handle()
            except ConnectionResetError: pass  # The isolated Java process closes keep-alive sockets on shutdown.
        def do_GET(self):
            self.send_response(200)
            self.send_header('Content-Length', '13')
            self.end_headers()
            self.wfile.write(b'{"models":[]}')
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            seen.append((self.path, body, dict(self.headers)))
            assert self.headers['Authorization'] == 'Bearer fixture-codex-token'
            if self.path.endswith('/responses'):
                assert body['stream'] is True and body['store'] is False
                assert 'messages' not in body
                if body['input'][-1] == {'type': 'compaction_trigger'}:
                    output = [reasoning, opaque, reasoning]
                else:
                    assert body['input'][1:4] == [reasoning, opaque, reasoning]
                    output = [{'type':'message','role':'assistant','content':[{'type':'output_text','text':'continued'}]}]
                response = {'id':'resp-test','status':'completed','created_at':1,'output':output, 'usage':{'input_tokens':13,'output_tokens':7,'total_tokens':20}}
                wire = ('event: response.completed\ndata: ' + json.dumps({'type':'response.completed','response':response}) + '\n\n').encode()
                media = 'text/event-stream'
            else:
                assert self.path in ('/v1/images/generations','/v1/images/edits')
                assert body['model'] == 'gpt-image-2' and 'stream' not in body and 'input' not in body
                assert self.headers['ChatGPT-Account-Id'] == 'fixture-account'
                wire = b'{"data":[{"b64_json":"fixture-image"}]}'
                media = 'application/json'
            self.send_response(200)
            self.send_header('Content-Type', media)
            self.send_header('Content-Length', str(len(wire)))
            self.end_headers()
            self.wfile.write(wire)

    mock = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    data = Path(tempfile.mkdtemp(prefix='codex-native-', dir=root/'target'))
    auth = data/'auth'
    auth.mkdir()
    (auth/'fixture-private-account.json').write_text(json.dumps({'type':'codex','access_token':'fixture-codex-token','account_id':'fixture-account','models':['gpt-6-astra']}), encoding='utf-8')
    port = free_port()
    def call(path, body=None):
        request = urllib.request.Request(f'http://127.0.0.1:{port}'+path, data=None if body is None else json.dumps(body).encode(), headers={'Authorization':'Bearer fixture-client','Content-Type':'application/json'})
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    with (data/'server.log').open('wb') as log:
        process = subprocess.Popen(['java','-jar',str(root/'target/java-llm-proxy-1.0.0.jar'),f'--server.port={port}',f'--proxy.data-dir={data}',f'--proxy.auth-dir={auth}',f'--proxy.config={data}/config.yaml','--proxy.api-key=fixture-client',f'--proxy.oauth.codex.api-base-url=http://127.0.0.1:{mock.server_port}/v1'], cwd=data, stdout=log, stderr=log, creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            for _ in range(100):
                try: models = call('/tools/codex/v1/models'); break
                except OSError: time.sleep(.1)
            else: raise AssertionError('Proxy did not start')
            for model in ('gpt-6-astra', 'gpt-image-2'):
                entry = next(item for item in models['data'] if item['id'] == model)
                assert entry['owned_by'] == 'openai', entry
                assert entry['sources'] == [{'provider':'codex','model':model,'owned_by':'openai'}], entry
            assert 'fixture-private-account' not in json.dumps(models)
            user = {'role':'user','content':'Keep this user instruction'}
            result = call('/tools/codex/v1/responses/compact', {'model':'gpt-6-astra','input':[user,{'role':'assistant','content':'Earlier work'}]})
            assert result['object'] == 'response.compaction' and result['output'] == [user, reasoning, opaque, reasoning]
            assert result['usage']['total_tokens'] == 20
            continued = call('/tools/codex/v1/responses', {'model':'gpt-6-astra','stream':False,'input':result['output']+[{'role':'user','content':'Continue'}]})
            assert continued['output'][0]['content'][0]['text'] == 'continued'
            for endpoint in ('generations','edits'):
                body = {'model':'gpt-image-2','prompt':'Draw a small icon','n':1,'background':'transparent'}
                if endpoint == 'edits': body['images'] = [{'image_url':'data:image/png;base64,fixture'}]
                result = call('/tools/codex/v1/images/'+endpoint, body)
                assert result['data'][0]['b64_json'] == 'fixture-image'
                assert seen[-1][1] == body
            print('PASS: scoped models ownership/source, compact HTTP to native trigger, canonical encrypted output and usage, Codex OAuth generation/edit routes and account header')
        finally:
            process.terminate()
            process.wait(timeout=15)
            mock.shutdown()


if __name__ == '__main__': run()
