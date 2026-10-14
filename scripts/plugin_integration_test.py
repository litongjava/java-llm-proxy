"""Exercise a real native plugin through the Java management and inference APIs."""
import hashlib
import base64
import io
import json
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from oauth_integration_test import free_port

def run():
    root = Path(__file__).resolve().parents[1]
    subprocess.run(['rustc','--crate-type','cdylib',str(root/'scripts/fixtures/plugin.rs'),'-o',str(root/'target/fixture.dll')],check=True)
    archive = io.BytesIO()
    with zipfile.ZipFile(archive,'w') as output:
        output.write(root/'target/fixture.dll','fixture.dll')
    payload = archive.getvalue()
    corrupt = [False]
    class Store(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self):
            if self.path == '/plugin.zip': body = payload
            else:
                body = json.dumps({'schema_version':2,'plugins':[{'id':'fixture','name':'Native fixture','version':'1.0.0','install':{'type':'direct','artifacts':[{'goos':'windows','goarch':'amd64','url':f'http://127.0.0.1:{self.server.server_port}/plugin.zip','size':len(payload),'sha256':'0'*64 if corrupt[0] else hashlib.sha256(payload).hexdigest()}]}}]}).encode()
            self.send_response(200)
            self.send_header('Content-Length',str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    store = ThreadingHTTPServer(('127.0.0.1',0),Store)
    threading.Thread(target=store.serve_forever,daemon=True).start()
    data = Path(tempfile.mkdtemp(prefix='plugin-review-',dir=root/'target'))
    config = data/'config.yaml'
    config.write_text('plugins:\n  enabled: true\n  dir: plugins\n',encoding='utf-8')
    port = free_port()
    base = f'http://127.0.0.1:{port}'
    def call(path,body=None,key='plugin-admin'):
        request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(request,timeout=30) as response:
                raw=response.read().decode()
                return response.status,json.loads(raw) if raw.startswith('{') else raw
        except urllib.error.HTTPError as error:
            raw=error.read().decode()
            return error.code,json.loads(raw) if raw.startswith('{') else raw
    def api(path,body=None):
        status,response=call('/v8/management/'+path,body)
        assert status==200 and response.get('ok'),(path,status,response)
        return response.get('data')
    with (data/'server.log').open('wb') as log:
        process=subprocess.Popen(['java','-jar',str(root/'target/java-llm-proxy-1.0.0.jar'),f'--server.port={port}',f'--proxy.data-dir={data}',f'--proxy.config={config}','--proxy.management-token=plugin-admin','--proxy.api-key=plugin-client',f'--proxy.plugins.registry-url=http://127.0.0.1:{store.server_port}/registry.json'],stdout=log,stderr=log,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            for _ in range(100):
                try:
                    if call('/api/status')[0]==200: break
                except OSError: pass
                if process.poll() is not None: raise AssertionError((data/'server.log').read_text())
                time.sleep(.1)
            assert api('plugins')['plugins']==[]
            assert api('plugins/store')['plugins'][0]['install_type']=='direct'
            corrupt[0]=True
            assert call('/v8/management/plugins/store/fixture/install',{})[0]==422
            corrupt[0]=False
            assert api('plugins/store/fixture/install',{})['status']=='ok'
            plugin=api('plugins')['plugins'][0]
            assert plugin['registered'] and plugin['metadata']['name']=='Native fixture',plugin
            assert plugin['supports_oauth'] and plugin['supports_quota']
            assert call('/v1/chat/completions',{'model':'blocked-model','messages':[]},'plugin-client')[0]==418
            assert api('plugins/fixture/echo')['from']=='native-management'
            status,resource=call('/v0/resource/plugins/fixture/panel',key='')
            assert status==200 and resource['from']=='native-management'
            started=api('oauth/auth-url?provider=fixture')
            assert api('oauth/status?state='+started['state'])['status']=='ok'
            credential=api('credentials')['files'][0]
            assert api('credentials?name=import.json',{'type':'fixture','custom':'input'})['name']=='imported.json'
            assert api('requests/api-call',{'auth_index':credential['auth_index'],'method':'GET','url':'https://plugin.invalid/quota','header':{}})['status_code']==201
            command = subprocess.run(['java','-jar',str(root/'target/java-llm-proxy-1.0.0.jar'),f'--server.port={free_port()}',f'--proxy.data-dir={data}/command',f'--proxy.config={config}','--plugin.fixture.greet'],capture_output=True,timeout=30)
            assert command.returncode==0 and b'plugin command ok' in command.stdout,command.stdout+command.stderr
            nested=api('plugins/fixture/nested')
            assert nested['ok'] and nested['result']['status_code']==200,nested
            assert 'native plugin works' in base64.b64decode(nested['result']['body']).decode(),nested
            quota=api('plugins/fixture/quota?auth_index='+credential['auth_index'])
            assert quota['buckets'][0]['remaining']==42
            assert api('plugins/fixture/quota/delete?auth_index='+credential['auth_index'],{})['success']
            status,result=call('/v1/chat/completions',{'model':'fixture-model','messages':[{'role':'user','content':'hello'}]},'plugin-client')
            assert status==200 and 'native plugin works' in json.dumps(result),(status,result)
            metrics=next(entry for entry in api('credentials')['files'] if entry['name']==credential['name'])
            assert len(metrics['recent_requests'])==20 and isinstance(metrics['recent_requests'][-1]['success'],int),metrics
            status,result=call('/v1/chat/completions',{'model':'fixture-model','messages':[{'role':'user','content':'hello'}],'stream':True},'plugin-client')
            assert status==200 and 'native stream' in result and '[DONE]' in result,(status,result)
            api('config/plugins/configs/fixture/greeting',{'operation':'PUT','value':'changed'})
            assert api('plugins')['plugins'][0]['registered']
            api('config/plugins/configs/fixture/enabled',{'operation':'PUT','value':False})
            assert not api('plugins')['plugins'][0]['registered']
            api('config/plugins/configs/fixture/enabled',{'operation':'PUT','value':True})
            assert api('plugins')['plugins'][0]['registered']
            assert api('plugins/fixture/delete',{})['file_deleted']
            assert api('plugins')['plugins']==[]
            print('PASS: native DLL ABI, store checksum, OAuth, auth import, command flags, HTTP executor, before/after credential hooks, quota, nested model/stream execution, reload and removal')
        finally:
            process.terminate()
            process.wait(timeout=15)
            store.shutdown()

if __name__=='__main__': run()
