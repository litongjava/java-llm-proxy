# Java LLM Proxy

Java 21、tio-boot 和 UniChatClient 实现的模型代理，桌面端名称为 **LLM Poroxy UI**。前端直接复用 EasyCLIProxyAPI 的 React 页面和 Tauri 原生功能，来源与许可证见 [frontend/UPSTREAM.md](frontend/UPSTREAM.md)。

## 构建与启动

需要 Java 21、Maven、Node.js、Rust，以及当前操作系统的 Tauri 构建环境。Windows 还需要 WebView2 和 MSVC。

按照 `build.txt` 构建 Java，再启动桌面端：

```powershell
mvn clean install '-DskipTests' '-Dgpg.skip'
mvn package '-DskipTests' '-Dgpg.skip' -Pproduction
cd frontend
npm.cmd ci
npm.cmd run tauri -- dev
```

`tauri dev` 模式的桌面窗口从 `http://localhost:1420` 加载页面，需要保持 Vite 开发服务运行。
直接重新打开该模式生成的 `cpa-gui.exe` 不会自动启动 Vite；如果出现 `localhost refused to connect`，
请先在 `frontend` 目录执行 `npm.cmd run dev`，再刷新或重新打开桌面窗口。Java 内核的 `8318` 端口正常并不代表前端服务已启动。
普通浏览器访问开发地址会使用 Mock 数据，只用于验证页面渲染；真实账号和内核状态请在桌面窗口中查看。

生成可运行的调试桌面程序：

```powershell
npm.cmd run tauri -- build --debug --no-bundle
```

此构建方式将前端静态文件嵌入桌面程序，适合需要直接启动且不依赖 Vite 的场景。

Tauri 构建脚本把 Java JAR 放入桌面资源目录。启动内核时使用 `java -jar`，优先读取 `JAVA_HOME`。端口、管理密钥、客户端密钥、认证目录和代理设置沿用原桌面配置界面。

默认监听端口为 **8318**，首页 **Connect to CPA** 的 OpenAI 接入地址为 `http://127.0.0.1:8318/v1`。已有桌面配置会保留保存的端口；如仍显示 8317，请在内核或配置页面将端口改为 8318，保存并重启内核。连接地址随实际配置更新。

## 后端功能

- 管理密钥和客户端密钥分离，在拦截器中完成鉴权；管理响应使用 `RespBodyVo`，Tauri 自动解包。
- Provider、API Key、模型别名、YAML 配置读取/保存、配置子树修改和热加载。
- OpenAI、Anthropic、Gemini、Responses 请求；同协议保留扩展字段，跨协议转换系统提示词、消息、图片、工具调用和结果。
- 普通响应及逐事件 SSE 转换，Token 统计独立于正文捕获开关。
- Responses 与 Realtime WebSocket 双向连接；Realtime 支持上游主动发送会话事件。
- 原生图片编辑文件上传、视频创建/编辑/扩展/查询/内容、TTS 二进制响应、Gemini Interactions。视频任务保存创建时的凭据绑定，查询按客户端密钥隔离。
- OAuth 授权码与设备码流程、PKCE、状态校验、取消、有效期、回调防重放、刷新和凭证原子保存。内置提供方为 Codex、Claude、Antigravity、Kimi、Kimi AI、xAI、Meta、Devin；Vertex 支持服务账号导入及 JWT 换取 Token。
- 凭证导入、下载、字段编辑、启停、删除、批量刷新；模型目录、模型排除、优先级、权重和冷却重置。
- 管理 API 转发支持凭证索引及 `$TOKEN$` 替换，供原配额和模型发现页面使用。
- 原生插件 ABI 加载、配置重载、启停和卸载；插件商店支持自定义注册表、直接产物及 GitHub Release，安装时检查大小、摘要和压缩包路径。
- 插件 OAuth、配额、模型执行、流式输出、管理接口、资源页面、请求/响应拦截、完成通知、用量通知、鉴权和调度扩展。
- 插件凭证解析、命令行参数、嵌套模型调用及 HTTP 执行器；请求钩子按凭据选择前后执行，流式钩子接收完整 SSE 事件。
- 模型前缀、分组别名、会话绑定、Payload 规则、重试和失败凭据切换；凭据页面提供最近 20 个十分钟时间段的请求统计。
- SQLite 保存请求详情；持久化 Usage 队列在桌面数据库落库后确认，重复投递按请求 ID 去重。

## 请求详情

在原 Usage 页面点击请求即可打开 Request Detail，查看客户端请求头/正文、上游请求头/正文、上游响应头/正文以及最终响应头/正文。格式化按钮识别协议并提取提示词与响应，各部分均可复制。认证头在持久化前脱敏；正文受捕获开关及大小上限控制。

## 配置

Antigravity 的 Google OAuth 凭据从本地 `secrets.txt` 读取，不再内置在源码中。
首次配置可复制 `secrets.example.txt` 为 `secrets.txt`，填写 `proxy.oauth.antigravity.client-id` 和
`proxy.oauth.antigravity.client-secret`。该文件已加入 `.gitignore`，不要强制提交，也不要放入 `src/main/resources` 或发布包。

`EnvUtils` 启动时自动读取 Java 进程工作目录以及用户主目录中的 `secrets.txt`。
从项目根目录启动时可直接使用根目录的文件；桌面端的工作目录是内核安装目录，
可将文件放入该目录，或合并到用户主目录的 `secrets.txt`（Windows 为 `%USERPROFILE%/secrets.txt`）。
环境变量或启动参数中的 `proxy.oauth.antigravity.client-id` / `client-secret` 优先于文件配置。
修改文件后需重启 Java 内核。
凭据缺失时仅 Antigravity OAuth 返回明确的配置错误，其他提供方不受影响。

如果项目根目录已配置却仍提示 `Configure proxy.oauth.antigravity.client-id ...`，
请检查桌面内核的工作目录：桌面端默认从 `cpa-core` 启动 Java，不会读取项目根目录的文件。
将上述两项配置合并到内核工作目录的 `secrets.txt`，然后在桌面端重启内核。
完整排查和验证步骤见 [Antigravity 登录配置](docs/antigravity-oauth.md)。

Java 配置通过 `EnvUtils` 读取，可通过环境变量或启动参数设置。

| 配置 | 用途 |
| --- | --- |
| `server.port` | HTTP 与 WebSocket 共用的监听端口，默认 8318 |
| `server.address` | 监听地址 |
| `proxy.management-token` | 管理接口密钥 |
| `proxy.api-key` | 启动时配置的客户端密钥 |
| `proxy.config` | 桌面 YAML 文件 |
| `proxy.data-dir` | SQLite 数据目录 |
| `proxy.auth-dir` | 凭证目录；未设置时读取 YAML 的 `oauth.auth-dir` |
| `proxy.capture.max-bytes` | 单段正文捕获上限，默认 4 MiB |
| `proxy.outbound-proxy` | 出站代理；请求/凭证配置可覆盖 |
| `proxy.plugins.registry-url` | 默认插件商店地址 |

OAuth 提供方支持 `proxy.oauth.<provider>.authorize-url`、`token-url`、`device-url`、`client-id`、`client-secret`、`redirect-uri` 和 `api-base-url`，便于对接自有授权服务及运行隔离测试。

`proxy.stream.max-event-bytes` 控制单个 SSE 事件上限，默认 16 MiB。`proxy.native.max-response-bytes` 控制原生多媒体非流式响应上限，默认 128 MiB。二进制请求和响应在详情中以 `base64:` 前缀保存，实际转发保持原始字节。Devin 详情展示 Connect-RPC 解码后的内容。

## 更新与发布产物

桌面和 Java 内核使用本项目的 [GitHub](https://github.com/litongjava/java-llm-proxy) 与 [GitCode](https://gitcode.com/ppnt/java-llm-proxy) 发行源。桌面更新校验清单中的 SHA-256；内核下载校验发行摘要或同名 `.sha256` 文件，并检查 JAR 启动入口。

两个组件共享发行仓库，发布时请使 Java、Cargo 和发行标签版本保持一致，在同一 Release 上传桌面清单、桌面包、六个平台内核包及摘要文件。打包脚本只生成文件：

```powershell
python scripts/package_release.py --version 1.0.0 --jar target/java-llm-proxy-1.0.0.jar --desktop windows-amd64=frontend/src-tauri/target/debug/cpa-gui.exe --output target/release-review
```

该示例生成用于验收的调试桌面包。正式发布应先构建 release 桌面程序，再把 `--desktop` 指向对应产物。便携包仍需要本机 Java 21；macOS 应使用 Tauri 的应用包与签名流程。发布产物尚未上传到远端。

## 验证

```powershell
mvn test '-Dgpg.skip'
mvn package '-DskipTests' '-Dgpg.skip' -Pproduction
python scripts/backend_smoke_test.py
python scripts/oauth_integration_test.py
python scripts/plugin_integration_test.py
python scripts/protocol_matrix_test.py
python scripts/websocket_integration_test.py
python scripts/native_endpoint_test.py
cd frontend
npm.cmd run build
cd src-tauri
cargo test management_api::tests -- --nocapture
cargo test tests::app_update -- --nocapture
cargo test tests::core_runtime -- --nocapture
```

OAuth 测试使用本地模拟授权服务，覆盖八个提供方的流程协议，不代表真实账号和各提供方全部模型均已实测。插件测试用 Rust 编译真实 DLL，验证安装、ABI、OAuth、配额、宿主回调、普通/流式模型调用、管理页面、配置重载和卸载。

`scripts/desktop_integration_test.py` 验证运行中的测试桌面、Java 子进程和桌面 Usage 数据库。该测试使用专用测试配置，不应直接针对日常配置运行。

生成便携包后，`python scripts/portable_package_test.py` 在独立目录显示桌面窗口，验证首次安装内核和管理接口，结束时关闭该测试实例。打包脚本的测试可在 `frontend` 执行 `npx bun test tests/portableUpdateManifest.test.ts tests/portablePreparation.test.ts`。

模型目录数据来自 CLIProxyAPI，许可证随资源保存。接口覆盖、验证范围和后续适配项见 [后端适配记录](docs/backend-adaptation.md)。本地模拟服务测试验证协议契约，真实 OAuth 账号、第三方 Go 插件和各提供方专用能力仍需分别验收，不能据此认定与参考项目全部功能等价。
