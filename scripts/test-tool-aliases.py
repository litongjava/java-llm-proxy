"""Exercise the packaged proxy against a local DeepSeek-compatible fixture."""
import argparse
import hashlib
import json
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="java")
    parser.add_argument("--jar", default="target/java-llm-proxy-1.0.0.jar")
    args = parser.parse_args()
    received = []

    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(body)
            usage = {"prompt_tokens": 62400, "completion_tokens": 522,
                     "prompt_cache_hit_tokens": 60000, "prompt_cache_miss_tokens": 2400}
            result = {"id": "fixture-response", "model": body["model"], "usage": usage,
                      "choices": [{"index": 0, "message": {"role": "assistant", "content": "hello"},
                                   "finish_reason": "stop"}]}
            if body.get("stream"):
                assert body["stream_options"]["include_usage"] is True
                events = [dict(result, usage=None, choices=[{"index": 0, "delta": {"content": "hello"}}]),
                          dict(result, choices=[{"index": 0, "delta": {}, "finish_reason": "stop"}])]
                data = ("".join("data: " + json.dumps(event) + "\n\n" for event in events)
                        + "data: [DONE]\n\n").encode()
                content_type = "text/event-stream"
            else:
                data = json.dumps(result).encode()
                content_type = "application/json"
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    with socket.socket() as port_socket:
        port_socket.bind(("127.0.0.1", 0))
        port = port_socket.getsockname()[1]
    origin = f"http://127.0.0.1:{port}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, body=None, management=False, token=True):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + ("test-admin" if management else "test-client")
        req = urllib.request.Request(origin + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            with opener.open(req, timeout=15) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    with tempfile.TemporaryDirectory(prefix="proxy-tool-alias-") as directory:
        work = Path(directory)
        base = f"http://127.0.0.1:{upstream.server_port}/v1"
        identity = hashlib.sha256(f"openai-compatibility\n{base}\n\nDeepSeek fixture".encode()).hexdigest()

        def scope(model):
            return {"providers": [{"section": "openai-compatibility", "identity": identity,
                                   "models": [{"name": model, "alias": "shared-alias"}]}]}

        config = {"access": {"api-keys": ["test-client"]}, "oauth": {"auth-dir": str(work / "auth")},
                  "plugins": {"enabled": False}, "requests": {"proxy-url": "direct"},
                  "api-keys": {"openai-compatibility": [{"name": "DeepSeek fixture", "base-url": base,
                      "models": [{"name": "deepseek-flash"}, {"name": "deepseek-v4-pro"}],
                      "keys": [{"api-key": "fixture-key"}]}]},
                  "tool-model-aliases": {"claude-desktop": scope("deepseek-flash"), "codex": scope("deepseek-v4-pro")}}
        config_file = work / "config.yaml"
        config_file.write_text(json.dumps(config), encoding="utf-8")
        with (work / "server.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen([args.java, f"-Dserver.port={port}", f"-Dproxy.data-dir={work / 'data'}",
                f"-Dproxy.config={config_file}", "-Dproxy.management-token=test-admin",
                "-jar", str(Path(args.jar).resolve())], cwd=work, stdout=log, stderr=subprocess.STDOUT,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            try:
                deadline = time.monotonic() + 25
                while True:
                    try:
                        if request("/api/status", management=True)[0] == 200:
                            break
                    except OSError:
                        pass
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise AssertionError("Proxy startup failed: " + (work / "server.log").read_text(encoding="utf-8")[-3000:])
                    time.sleep(0.2)
                public = json.loads(request("/v1/models")[1])["data"]
                assert {entry["id"] for entry in public} == {"deepseek-flash", "deepseek-v4-pro"}
                assert request("/tools/codex/v1/models", token=False)[0] == 401
                assert "shared-alias" in request("/tools/codex/v1/models")[1]
                payload = {"model": "shared-alias", "messages": [{"role": "user", "content": "test"}], "max_tokens": 1000}
                assert request("/tools/opencode/v1/messages", payload)[0] == 503
                for tool, stream in [("claude-desktop", False), ("claude-desktop", True), ("codex", False)]:
                    status, body = request(f"/tools/{tool}/v1/messages", dict(payload, stream=stream))
                    assert status == 200, (status, body, (work / "server.log").read_text(encoding="utf-8")[-4000:])
                    assert "hello" in body
                assert [body["model"] for body in received] == ["deepseek-flash", "deepseek-flash", "deepseek-v4-pro"]
                queue = json.loads(request("/v8/management/observability/usage/queue", management=True)[1])
                records = queue.get("data", queue)
                if isinstance(records, dict):
                    records = records.get("usage", records.get("queue", records))
                assert isinstance(records, list) and len(records) == 3, records
                for record in records:
                    assert record["model"].startswith("deepseek-"), record
                    assert record["alias"] == "shared-alias", record
                    assert record["provider"].startswith("DeepSeek fixture"), record
                    assert record["response_model"] == record["model"], record
                    assert int(record["tokens"]["cache_read_tokens"]) == 60000, record
                print("PASS: authentication, public catalog, isolated routing, JSON/SSE translation, real provider/model and 60000 cached tokens")
            finally:
                process.terminate()
                process.wait(timeout=10)
                upstream.shutdown()


if __name__ == "__main__":
    main()
