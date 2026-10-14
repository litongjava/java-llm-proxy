"""Run OAuth and credential contract checks against an isolated Java process."""
import base64
import hashlib
import json
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

exchanges = []
polls = {}

class OAuthMock(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, value, status=200):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/profile':
            self.reply({'authorization':self.headers.get('Authorization')})
        else:
            self.reply({'models':[]})

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if 'application/json' in self.headers.get('Content-Type', ''):
            body = json.loads(raw or b'{}')
        else:
            body = {key:items[0] for key,items in urllib.parse.parse_qs(raw.decode()).items()}
        exchanges.append((self.path, body))
        if self.path.startswith('/device/'):
            device = 'device-' + str(len(exchanges))
            self.reply({'device_code':device,'user_code':'TEST-CODE','verification_uri':'http://localhost/verify','expires_in':900,'interval':1})
        elif self.path.startswith('/token/'):
            if body.get('grant_type', '').endswith('device_code'):
                device = body['device_code']
                polls[device] = polls.get(device,0) + 1
                if polls[device] == 1:
                    return self.reply({'error':'authorization_pending'}, 400)
            if body.get('grant_type') == 'refresh_token':
                return self.reply({'access_token':'rotated-access','refresh_token':'rotated-refresh','expires_in':3600})
            provider = self.path.split('/')[-1]
            self.reply({'access_token':provider+'-access','refresh_token':provider+'-refresh','expires_in':3600,'token':'devin-session-token$test'})
        elif self.path == '/key':
            self.reply({'api_key':'meta-minted','base_url':'http://localhost/v1','user_email':'meta@example.test'})
        elif self.path.endswith(':loadCodeAssist'):
            self.reply({'cloudaicompanionProject':{'id':'mock-google-project'}})
        elif self.path.endswith('/responses'):
            self.reply({'output':[{'type':'message','content':[{'type':'output_text','text':'OAuth proxy works'}]}], 'usage':{'input_tokens':3,'output_tokens':4}})
        else:
            self.reply({'error':'unknown'},404)

def run():
    root = Path(__file__).resolve().parents[1]
    data = Path(tempfile.mkdtemp(prefix='oauth-review-', dir=root/'target'))
    secrets = data / 'secrets.txt'
    secrets.write_text('proxy.oauth.antigravity.client-id=mock-google-client\n'
                       'proxy.oauth.antigravity.client-secret=mock-google-secret\n', encoding='utf-8')
    mock = ThreadingHTTPServer(('127.0.0.1',0), OAuthMock)
    threading.Thread(target=mock.serve_forever,daemon=True).start()
    upstream = 'http://127.0.0.1:' + str(mock.server_port)
    port = free_port()
    base = 'http://127.0.0.1:' + str(port)
    args = ['java','-jar',str(root/'target/java-llm-proxy-1.0.0.jar'),f'--server.port={port}',
            '--proxy.management-token=oauth-review-admin','--proxy.api-key=oauth-review-client',
            f'--proxy.data-dir={data}',f'--proxy.config={data}/config.yaml',f'--proxy.auth-dir={data}/oauth']
    for provider in ['codex','claude','antigravity','devin','kimi','kimi-ai','meta','xai']:
        args += [f'--proxy.oauth.{provider}.token-url={upstream}/token/{provider}',
                 f'--proxy.oauth.{provider}.api-base-url={upstream}/v1']
        if provider in ['codex','claude','antigravity','devin']:
            args += [f'--proxy.oauth.{provider}.authorize-url={upstream}/authorize',
                     f'--proxy.oauth.{provider}.redirect-uri=http://127.0.0.1:{free_port()}/callback']
        else:
            args += [f'--proxy.oauth.{provider}.device-url={upstream}/device/{provider}']
    args += [f'--proxy.oauth.meta.key-url={upstream}/key']
    args += [f'--proxy.oauth.antigravity.profile-url={upstream}/profile',f'--proxy.oauth.antigravity.control-url={upstream}/v1internal']
    def call(path, body=None, token='oauth-review-admin', absolute=False):
        request = urllib.request.Request(path if absolute else base+path,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(request,timeout=10) as response:
                raw=response.read().decode()
                return response.status, json.loads(raw) if raw.startswith('{') else raw
        except urllib.error.HTTPError as error:
            raw=error.read().decode()
            return error.code,json.loads(raw) if raw.startswith('{') else raw
    def api(path, body=None):
        status,result=call('/v8/management/'+path,body)
        assert status==200 and result.get('ok'), (path,status,result)
        return result['data']
    with (data/'server.log').open('wb') as log:
        process = subprocess.Popen(args,cwd=data,stdout=log,stderr=log,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            for attempt in range(100):
                try:
                    if call('/api/status')[0]==200:
                        break
                except OSError:
                    pass
                time.sleep(.1)
            else:
                raise AssertionError('Java did not become ready')
            assert call('/v8/management/credentials',token='oauth-review-client')[0]==401
            api('credentials?name=import.json', {'type':'codex','access_token':'private-access','refresh_token':'private-refresh','custom':{'keep':True}})
            files=api('credentials')['files']
            assert len(files)==1 and 'private-access' not in json.dumps(files)
            index=files[0]['auth_index']
            api('credentials/fields',{'name':'import.json','note':'edited','headers':{'x-extra':'yes'}})
            saved=api('credentials/download?name=import.json')
            assert saved['custom']=={'keep':True} and saved['note']=='edited'
            api('credentials/status',{'name':'import.json','disabled':True})
            assert api('credentials')['files'][0]['disabled']
            api('credentials/status',{'name':'import.json','disabled':False})
            result=api('requests/api-call',{'authIndex':index,'method':'GET','url':upstream+'/profile','header':{'Authorization':'Bearer $TOKEN$'}})
            assert json.loads(result['body'])['authorization']=='Bearer private-access'
            result=api('credentials/refresh',{'name':'import.json'})
            assert result['success']==1
            assert api('credentials/download?name=import.json')['refresh_token']=='rotated-refresh'
            assert call('/v8/management/credentials/download?name=../outside.json')[0]==400
            api('credentials/delete?name=import.json',{})
            for provider in ['codex','claude','antigravity','devin']:
                started=api('oauth/auth-url?provider='+provider)
                query={k:v[0] for k,v in urllib.parse.parse_qs(urllib.parse.urlsplit(started['url']).query).items()}
                assert query['state']==started['state'] and query['code_challenge_method']=='S256'
                callback=query['redirect_uri']+'?'+urllib.parse.urlencode({'code':'test-code','state':started['state']})
                assert call('/v8/management/oauth/callback',{'provider':provider,'state':'forged','code':'test-code'})[0]==404
                assert call(callback,absolute=True)[0]==200
                assert api('oauth/status?state='+started['state'])['status']=='ok'
                assert call('/v8/management/oauth/callback',{'provider':provider,'redirect_url':callback})[0]==409
                token_request=next(body for path,body in reversed(exchanges) if path=='/token/'+provider)
                if provider == 'antigravity':
                    assert query['client_id'] == 'mock-google-client'
                    assert token_request['client_id'] == 'mock-google-client'
                    assert token_request['client_secret'] == 'mock-google-secret'
                challenge=base64.urlsafe_b64encode(hashlib.sha256(token_request['code_verifier'].encode()).digest()).decode().rstrip('=')
                assert challenge==query['code_challenge']
            for provider in ['kimi','kimi-ai','meta','xai']:
                started=api('oauth/auth-url?provider='+provider)
                assert started['flow']=='device' and started['user_code']=='TEST-CODE'
                assert api('oauth/status?state='+started['state'])['status']=='wait'
                time.sleep(1.05)
                assert api('oauth/status?state='+started['state'])['status']=='ok'
            started=api('oauth/auth-url?provider=kimi')
            api('oauth/session/cancel?state='+started['state'],{})
            assert api('oauth/status?state='+started['state'])['error']=='cancelled'
            files=api('credentials')['files']
            assert len(files)==8, len(files)
            assert all(item['status']=='active' for item in files)
            codex = next(item for item in files if item['type']=='codex')
            api('credentials/fields', {'name':codex['name'],'models':['oauth-test']})
            status,result=call('/v1/responses',{'model':'oauth-test','input':'hello'},'oauth-review-client')
            assert status==200 and 'OAuth proxy works' in json.dumps(result), (status,result)
            print('PASS: eight OAuth provider contracts, PKCE, loopback callback, replay protection, device polling, cancellation, credential CRUD, secret redaction, refresh rotation, quota API token substitution and OAuth model invocation')
        finally:
            process.terminate()
            process.wait(timeout=15)
            mock.shutdown()

if __name__=='__main__':
    run()
