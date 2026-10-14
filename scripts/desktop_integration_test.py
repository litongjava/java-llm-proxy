"""Verify the running Tauri collector and Java child against a local mock upstream."""
import json
import sqlite3
import threading
import time
from pathlib import Path
from http.server import ThreadingHTTPServer
import integration_test as http

http.BASE = 'http://127.0.0.1:18319'
http.ADMIN = 'desktop-review-token'
http.KEY = 'desktop-review-key'
# The helper's default argument was bound when its module was imported.
http.call.__defaults__ = (None, http.ADMIN)

def run():
    database = Path('frontend/src-tauri/target/debug/usage-records/usage.db')
    assert database.is_file(), 'Start the Tauri desktop test instance first'
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            if http.call('/api/status')[0] == 200:
                break
        except (OSError, http.urllib.error.URLError):
            pass
        time.sleep(0.25)
    server = ThreadingHTTPServer(('127.0.0.1', 18318), http.Upstream)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        groups = [{'name':'Desktop integration test', 'base-url':'http://127.0.0.1:18318/v1',
                   'proxy-url':'direct',
                   'keys':[{'api-key':'desktop-upstream'}],
                   'models':[{'name':'review-model', 'alias':'desktop-review-model'}]}]
        status, body, _ = http.call('/v8/management/config/api-keys/openai-compatibility', {'operation':'PUT','value':groups})
        assert status == 200, (status, body)
        status, body, headers = http.call('/v1/chat/completions', {'model':'desktop-review-model','messages':[{'role':'user','content':'Desktop request detail verification'}]}, http.KEY)
        assert status == 200, body
        request_id = headers.get('x-request-id') or headers.get('X-Request-Id')
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            with sqlite3.connect(database) as connection:
                row = connection.execute('SELECT input_tokens,output_tokens FROM usage_events WHERE request_id=?', (request_id,)).fetchone()
            if row:
                break
            time.sleep(0.25)
        assert row == (11, 6), ('Tauri collector must store real Java usage', row)
        detail = http.api('usage/detail?id=' + request_id)
        formatted = http.api('usage/format?id=' + request_id)
        assert detail['requestBody'] and detail['responseBody']
        assert detail['requestHeaders'] and detail['responseHeaders']
        assert 'Desktop request detail verification' in formatted['prompt']
        assert 'proxy response' in formatted['response']
        queue = json.loads(http.call('/v8/management/observability/usage/queue?count=1000')[1])['data']
        assert request_id not in [item['request_id'] for item in queue]
        # Re-delivery models a lost acknowledgement without changing the stored request.
        with sqlite3.connect('frontend/src-tauri/target/debug/cpa-core/data/proxy.db') as connection:
            connection.execute('INSERT OR IGNORE INTO usage_queue(id) VALUES (?)', (request_id,))
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            queue = json.loads(http.call('/v8/management/observability/usage/queue?count=10000')[1])['data']
            if request_id not in [item['request_id'] for item in queue]:
                break
            time.sleep(0.25)
        assert request_id not in [item['request_id'] for item in queue]
        with sqlite3.connect(database) as connection:
            assert connection.execute('SELECT count(*) FROM usage_events WHERE request_id=?', (request_id,)).fetchone()[0] == 1
        print('PASS: Tauri started Java, provider config hot reload, real request collected into original SQLite, acknowledgement and redelivery deduplication, request details and formatting')
    finally:
        server.shutdown()

if __name__ == '__main__':
    run()
