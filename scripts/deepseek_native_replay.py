import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import tomllib
import urllib.error
import urllib.request
from oauth_integration_test import free_port


def run():
    parser = argparse.ArgumentParser()
    parser.add_argument('--installation', required=True)
    parser.add_argument('--request-log', required=True)
    args = parser.parse_args()
    installation = Path(args.installation)
    config = tomllib.loads((installation / 'config.toml').read_text(encoding='utf-8'))
    request = urllib.request.Request(
        f'http://127.0.0.1:{config["port"]}/api/settings',
        headers={'Authorization': 'Bearer ' + config['management-secret-key']})
    with urllib.request.urlopen(request) as response:
        settings = json.load(response)['data']
    record = json.loads(Path(args.request_log).read_text(encoding='utf-8'))
    provider = next(item for item in settings['providers'] if item['name'] == record['provider'])
    provider['apiKey'] = os.environ['DEEPSEEK_API_KEY']
    assert provider['baseUrl'].rstrip('/') in ('https://api.deepseek.com', 'https://api.deepseek.com/v1')
    body = json.loads(record['requestBody'])
    body['model'] = record['model']
    body['max_tokens'] = 64
    root = Path(__file__).resolve().parents[1]
    port = free_port()

    def call(path, payload=None):
        request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
            data=None if payload is None else json.dumps(payload).encode(),
            headers={'Authorization': 'Bearer replay-test', 'Content-Type': 'application/json',
                     'anthropic-version': '2023-06-01', 'User-Agent': 'claude-cli/replay'})
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, response.read().decode(), response.headers

    with tempfile.TemporaryDirectory(prefix='deepseek-replay-') as directory:
        with open(Path(directory) / 'server.log', 'wb') as log:
            process = subprocess.Popen(['java', '-jar', str(root / 'target/java-llm-proxy-1.0.0.jar'),
                f'--server.port={port}', f'--proxy.data-dir={directory}',
                f'--proxy.config={directory}/config.yaml', '--proxy.management-token=replay-test',
                '--proxy.api-key=replay-test'], stdout=log, stderr=log,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            try:
                for attempt in range(100):
                    try:
                        call('/api/status')
                        break
                    except OSError:
                        time.sleep(.1)
                call('/api/settings', {'providers': [provider], 'captureBodies': True})
                for stream in (False, True):
                    body['stream'] = stream
                    status, result, headers = call('/tools/claude-code/v1/messages', body)
                    assert ('message_stop' in result if stream else json.loads(result)['type'] == 'message')
                    detail = json.loads(call('/api/usage/detail?id=' + headers['x-request-id'])[1])['data']
                    outbound = json.loads(detail['upstreamRequestBody'])
                    assert outbound == body, 'Native payload was changed'
                    print(json.dumps({'stream': stream, 'status': status, 'native_payload_preserved': True,
                                      'message_completed': True}))
            except urllib.error.HTTPError as error:
                print(json.dumps({'status': error.code, 'error': error.read().decode()[:1500]}))
                raise SystemExit(1)
            finally:
                process.terminate()
                process.wait(timeout=20)


if __name__ == '__main__':
    run()
