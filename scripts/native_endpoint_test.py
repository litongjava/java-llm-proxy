"""Verify native multimedia requests, credential-pinned tasks and raw binary capture."""
import base64
import json
import subprocess
import tempfile
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from oauth_integration_test import free_port

def run():
    root = Path(__file__).resolve().parents[1]
    seen = []
    binary = b'\x00\xffRIFF\x80test\x00'
    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def respond(self, body, media='application/json'):
            self.send_response(200)
            self.send_header('Content-Type', media)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_POST(self):
            body = self.rfile.read(int(self.headers['Content-Length']))
            seen.append((self.path, body, self.headers.get('Authorization'), self.headers.get('Content-Type')))
            if self.path.endswith('/tts'): self.respond(binary, 'audio/wav')
            elif '/videos' in self.path: self.respond(b'{"request_id":"video-test","status":"pending"}')
            elif '/interactions' in self.path: self.respond(b'{"id":"interaction-test","outputs":[{"text":"native interaction"}]}')
            else: self.respond(b'{"data":[{"b64_json":"aW1hZ2U="}]}')
        def do_GET(self):
            seen.append((self.path, b'', self.headers.get('Authorization'), None))
            if self.path.endswith('/content'): self.respond(binary, 'video/mp4')
            else: self.respond(b'{"id":"video-test","status":"completed"}')
    mock = ThreadingHTTPServer(('127.0.0.1',0),Upstream)
    threading.Thread(target=mock.serve_forever,daemon=True).start()
    data = Path(tempfile.mkdtemp(prefix='native-review-',dir=root/'target'))
    port = free_port()
    def call(path, body=None, media='application/json', token='native-admin'):
        encoded = json.dumps(body).encode() if isinstance(body, dict) else body
        req = urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=encoded,headers={'Authorization':'Bearer '+token,'Content-Type':media})
        with urllib.request.urlopen(req,timeout=20) as response: return response.read(),response.headers
    with (data/'server.log').open('wb') as log:
        process = subprocess.Popen(['java','-jar',str(root/'target/java-llm-proxy-1.0.0.jar'),f'--server.port={port}',f'--proxy.data-dir={data}',f'--proxy.config={data}/config.yaml','--proxy.management-token=native-admin','--proxy.api-key=native-client'],stdout=log,stderr=log,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            for _ in range(100):
                try: call('/api/status'); break
                except OSError: time.sleep(.1)
            settings={'providers':[{'id':'native','name':'Native','protocol':'openai','baseUrl':f'http://127.0.0.1:{mock.server_port}/v1','apiKey':'media-key','models':['gpt-image-1','grok-imagine-video','grok-tts','sora-2']},
                {'id':'gemini','name':'Gemini','protocol':'gemini','baseUrl':f'http://127.0.0.1:{mock.server_port}/v1beta/models','apiKey':'gemini-key','models':['gemini-test']}],
                'aliases':[{'name':'image-alias','model':'gpt-image-1','providerId':'native'}]}
            call('/api/settings',settings)
            multipart=(b'--native-boundary\r\nContent-Disposition: form-data; name="model"\r\n\r\nimage-alias\r\n'
                b'--native-boundary\r\nContent-Disposition: form-data; name="image"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n'+binary+b'\r\n--native-boundary--\r\n')
            call('/v1/images/edits',multipart,'multipart/form-data; boundary=native-boundary','native-client')
            assert binary in seen[-1][1] and b'gpt-image-1' in seen[-1][1] and b'image-alias' not in seen[-1][1],seen[-1]
            assert seen[-1][2]=='Bearer media-key'
            for endpoint in ('/v1/videos','/v1/videos/generations','/v1/videos/edits','/v1/videos/extensions','/openai/v1/videos'):
                response,_=call(endpoint,{'model':'grok-imagine-video','prompt':'video'},token='native-client')
                assert json.loads(response)['request_id']=='video-test'
            call('/v1/videos/video-test',token='native-client')
            assert seen[-1][0]=='/v1/videos/video-test' and seen[-1][2]=='Bearer media-key',seen[-1]
            response,_=call('/openai/v1/videos/video-test/content',token='native-client')
            assert response==binary
            response,headers=call('/v1/tts',{'text':'hello'},token='native-client')
            assert response==binary and headers['Content-Type']=='audio/wav'
            detail=json.loads(call('/api/usage/detail?id='+headers['x-request-id'])[0])['data']
            assert base64.b64decode(detail['responseBody'].removeprefix('base64:'))==binary
            call('/v1beta/interactions',{'model':'gemini-test','input':'hello'},token='native-client')
            assert seen[-1][0]=='/v1beta/interactions'
            assert json.loads(call('/v1beta/models',token='native-client')[0])['models']
            assert json.loads(call('/v1beta/models/gemini-test',token='native-client')[0])['name']=='models/gemini-test'
            assert json.loads(call('/v1/models/gemini-test',token='native-client')[0])['id']=='gemini-test'
            try:
                call('/v1/videos/missing-task',token='native-client')
                raise AssertionError('Unknown task must not be sent upstream')
            except urllib.error.HTTPError as error: assert error.code==404
            print('PASS: multipart image bytes and alias, video create/edit/extend/retrieve/content, pinned credential, TTS binary capture, Gemini Interactions and unknown task rejection')
        finally:
            process.terminate()
            process.wait(timeout=15)
            mock.shutdown()

if __name__=='__main__': run()
