# Codex CLI 语音会话

Java 内核支持 Codex CLI 的 `/v1/live` 会话创建与后续 Realtime WebSocket 连接。默认本地端口为 `8318`；原版 CPA 通常使用 `8317`。客户端需要指向实际运行的 Java 内核端口，重新构建本身不会替换正在运行的内核。

## 请求流程

1. 客户端发送 `POST /v1/live`。也支持 `POST /v1/realtime` 和 `POST /v1/realtime/calls`。
2. 内核从可用 Codex OAuth 凭据中选择账号，将请求发送到该账号配置的 Codex 上游基础地址下的 `/realtime/calls?intent=quicksilver&architecture=avas`。默认基础地址为 `https://chatgpt.com/backend-api/codex`。
3. 上游成功创建后，保留状态码、Content-Type 和 SDP 正文，把 Location 改写为本地 `/v1/realtime/calls/{call_id}`。
4. 后续 WebSocket 使用创建时的同一账号，并再次核验客户端访问密钥。默认连接 `https://api.openai.com/v1` 对应的 WebSocket 地址。

支持以下三种后续连接形式，转发时保留各自路径形式：

- `/v1/live/{call_id}` → `/v1/live/{call_id}`。
- `/v1/realtime/calls/{call_id}` → `/v1/realtime/calls/{call_id}`。
- `/v1/realtime?call_id={call_id}` → `/v1/realtime?intent=quicksilver&call_id={call_id}`。

上述接口也支持 `/tools/codex` 等已有工具前缀，返回的 Location 保留前缀。普通 `/v1/realtime?model=...` 继续使用已有的独立 Realtime 处理流程。

创建请求支持 JSON、multipart 的 `sdp` / `session` 字段，以及 `application/sdp`、`text/plain` 正文。multipart 由框架解析。确定的输入字段使用实体类，动态 session 配置和 JSON 扩展字段使用 `Kv`。默认模型为 `gpt-live-1-codex`；`gpt-realtime`、其日期变体和 realtime-preview 名称映射到该模型。显式配置凭据模型列表时，需要允许目标语音模型，模型排除规则仍然生效。

协议成功响应保持上游 SDP / JSON，不套业务包装；否则 Codex 无法读取 SDP。代理自身产生的 HTTP 错误使用 `RespBodyVo`。WebSocket 消息原样转发，不追加 Responses 的 model 字段，不改写语音事件。

## 连接管理

后续连接在上游握手成功后才返回本地 `101`。握手期间收到的上游主动事件先缓存，再按顺序交付客户端。上游拒绝握手时保留其 HTTP 状态，例如 `404`，客户端可以重新尝试连接该会话。

会话只允许一个活动连接。不同访问密钥不能接入其他密钥创建的会话；重复连接返回 `409`。会话保存于进程内，未连接会话一小时后过期，连接结束后释放；内核重启后需要重新创建。

配置通过 `EnvUtils` 读取，可使用启动参数：

```text
--proxy.live.max-sessions=1000
--proxy.live.handshake-timeout-seconds=30
--proxy.live.sideband-base-url=https://api.openai.com/v1
```

`sideband-base-url` 是服务端受控配置，适用于测试或兼容部署，不能从客户端 Location 任意指定上游主机。HTTP 与 WebSocket 都沿用凭据代理配置和全局代理配置。

## 验证

```powershell
mvn clean package '-DskipTests' '-Dgpg.skip' -Pproduction
mvn test '-Dgpg.skip'
python scripts/live_integration_test.py
python scripts/websocket_integration_test.py
```

Live 集成测试启动隔离 Java 进程和本地模拟上游，使用虚构 OAuth 凭据，覆盖 JSON / multipart / SDP 创建、201 与 Location、三种连接形式、工具前缀、账号绑定、跨密钥隔离、重复连接拒绝、上游 404 后重试、服务端主动事件及文本和二进制双向传输。测试日志保存在 `target/live-*`。

这是信令与 WebSocket 链路验证，尚未使用真实 Codex CLI 麦克风完成端到端通话。本次移植范围不包括原版可选的 WebRTC 媒体中继、临时 client secret 签发和 SIP 控制；音频媒体仍由客户端与上游通过 WebRTC 传输，客户端网络需能访问相应媒体服务。
