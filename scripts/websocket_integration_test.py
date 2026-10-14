"""Raw WebSocket handshake, authentication and multiple Responses turns through Java."""
import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from oauth_integration_test import free_port

def read_exact(stream, size):
    data = b''
    while len(data) < size:
        chunk = stream.read(size-len(data))
        if not chunk: raise EOFError('WebSocket ended')
        data += chunk
    return data

def frame(stream):
    header = read_exact(stream, 2)
    size = header[1] & 127
    if size == 126: size = struct.unpack('!H', read_exact(stream, 2))[0]
    if size == 127: size = struct.unpack('!Q', read_exact(stream, 8))[0]
    assert size < 1024*1024
    mask = read_exact(stream, 4) if header[1] & 128 else None
    payload = read_exact(stream, size)
    if mask: payload = bytes(value ^ mask[i % 4] for i, value in enumerate(payload))
    return header[0] & 15, payload

def send_frame(stream, value, masked=False):
    payload = json.dumps(value).encode()
    header = bytes([129, (128 if masked else 0) | (len(payload) if len(payload) < 126 else 126)])
    if len(payload) >= 126: header += struct.pack('!H', len(payload))
    if masked:
        mask = os.urandom(4)
        header += mask
        payload = bytes(value ^ mask[i % 4] for i, value in enumerate(payload))
    stream.write(header + payload); stream.flush()

def run():
    root = Path(__file__).resolve().parents[1]
    received = []
    class Mock(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *args): pass
        def do_GET(self):
            self.close_connection = True
            assert self.headers['Authorization'] == 'Bearer upstream-ws-key'
            assert self.path in ('/v1/responses','/v1/realtime?model=ws-model')
            accept = base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            self.send_response(101); self.send_header('Upgrade', 'websocket'); self.send_header('Connection', 'Upgrade'); self.send_header('Sec-WebSocket-Accept', accept); self.end_headers()
            if self.path.startswith('/v1/realtime'):
                send_frame(self.wfile, {'type':'session.created','session':{'id':'realtime-test'}})
            try:
                while True:
                    opcode, payload = frame(self.rfile)
                    if opcode == 8: break
                    received.append(json.loads(payload))
                    send_frame(self.wfile, {'type':'response.output_text.delta', 'delta':'websocket answer'})
                    send_frame(self.wfile, {'type':'response.completed', 'response':{'id':'ws-response','usage':{'input_tokens':3,'output_tokens':5}}})
            except (EOFError, ConnectionError): pass
    mock = ThreadingHTTPServer(('127.0.0.1', 0), Mock)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    data = Path(tempfile.mkdtemp(prefix='websocket-', dir=root/'target'))
    port = free_port()
    def call(path, body=None):
        request = urllib.request.Request(f'http://127.0.0.1:{port}'+path, data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization':'Bearer ws-admin','Content-Type':'application/json'})
        with urllib.request.urlopen(request, timeout=15) as response: return json.load(response)
    def connect(token, path='/v1/responses'):
        connection = socket.create_connection(('127.0.0.1', port), timeout=10)
        stream = connection.makefile('rwb', buffering=0)
        key = base64.b64encode(os.urandom(16)).decode()
        stream.write((f'GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {key}\r\nAuthorization: Bearer {token}\r\n\r\n').encode())
        status = stream.readline().decode()
        while stream.readline() not in (b'\r\n', b''): pass
        return connection, stream, status
    with (data/'server.log').open('wb') as log:
        process = subprocess.Popen(['java','-jar',str(root/'target/java-llm-proxy-1.0.0.jar'),f'--server.port={port}',f'--proxy.data-dir={data}',
            f'--proxy.config={data}/config.yaml','--proxy.management-token=ws-admin','--proxy.api-key=ws-client'],stdout=log,stderr=log,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            for _ in range(100):
                try: call('/api/status'); break
                except OSError: time.sleep(.1)
            call('/api/providers/save',{'name':'WebSocket test','protocol':'responses','baseUrl':f'http://127.0.0.1:{mock.server_port}/v1','apiKey':'upstream-ws-key','models':['ws-model']})
            connection, stream, status = connect('invalid')
            assert '401' in status, status
            stream.close(); connection.close()
            connection, stream, status = connect('ws-client')
            assert '101' in status, status
            for i in range(2):
                send_frame(stream, {'type':'response.create','model':'ws-model','input':f'turn {i}'}, True)
                assert json.loads(frame(stream)[1])['delta'] == 'websocket answer'
                assert json.loads(frame(stream)[1])['type'] == 'response.completed'
                time.sleep(.1)
            assert len(received) == 2
            stream.close(); connection.close()
            connection, stream, status = connect('ws-client','/v1/realtime?model=ws-model')
            assert '101' in status
            assert json.loads(frame(stream)[1])['type']=='session.created'
            send_frame(stream, {'type':'response.create','response':{'instructions':'realtime prompt'}}, True)
            assert json.loads(frame(stream)[1])['delta']=='websocket answer'
            assert json.loads(frame(stream)[1])['type']=='response.completed'
            stream.close(); connection.close()
            print('PASS: WebSocket authentication, two Responses turns, terminal events, immediate Realtime connection and server-first session event')
        finally:
            process.terminate(); process.wait(timeout=15); mock.shutdown()

if __name__ == '__main__': run()
