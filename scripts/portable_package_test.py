"""Launch an isolated portable desktop and verify first-run Java kernel installation."""
import hashlib
import json
import subprocess
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path
from oauth_integration_test import free_port

def run():
    root = Path(__file__).resolve().parents[1]
    releases = root/'target/release-review'
    manifest = json.loads((releases/'portable-update-windows.json').read_text())
    asset = manifest['assets']['windows-amd64']
    archive = releases/asset['url'].rsplit('/',1)[1]
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==asset['sha256']
    destination = Path(tempfile.mkdtemp(prefix='portable-review-',dir=root/'target'))
    with zipfile.ZipFile(archive) as package:
        package.extractall(destination)
    folder = next(destination.iterdir())
    port = free_port()
    (folder/'config.toml').write_text(f'''locale = "en"
port = {port}
host = "127.0.0.1"
start-core-on-launch = true
silent-start = false
management-secret-key = "portable-review-admin"
api-keys = [{{ key = "portable-review-client", remark = "Portable test" }}]
plugins-enabled = false
proxy-url = ""
allow-lan = false
close-behavior = "exit"
''',encoding='utf-8')
    process = subprocess.Popen([str(folder/'LLMPoroxyUI.exe')],cwd=folder)
    try:
        status = None
        for _ in range(180):
            if process.poll() is not None: raise AssertionError(f'Desktop exited with {process.returncode}')
            try:
                request = urllib.request.Request(f'http://127.0.0.1:{port}/api/status',headers={'Authorization':'Bearer portable-review-admin'})
                with urllib.request.urlopen(request,timeout=1) as response: status=json.load(response)
                break
            except OSError: time.sleep(.25)
        assert status and status['ok'],f'Portable Java startup failed: {folder}'
        assert (folder/'cpa-core/java-llm-proxy.jar').is_file(),folder
        assert status['data']['name']=='Java LLM Proxy'
        print('PASS: portable SHA-256, extraction, visible desktop launch, automatic JAR installation and authenticated Java management API')
    finally:
        process.terminate()
        process.wait(timeout=20)

if __name__=='__main__': run()
