# Antigravity 登录配置

## 报错原因

登录时出现以下提示，表示 Java 内核没有读到完整的 OAuth 客户端配置：

```text
This sign-in did not finish: Management API error (500): Configure proxy.oauth.antigravity.client-id and proxy.oauth.antigravity.client-secret in secrets.txt. Existing accounts are not affected.
```

后端在生成 Google 授权地址前检查这两个字段；任一字段缺失或只有空白，就会返回配置错误。此时尚未向 Google 发起授权请求，无需删除已有账号或重复登录。

## 配置文件应放在哪里

配置统一通过 `EnvUtils.get` 读取，`secrets.txt` 在 Java 启动时加载。文件的位置取决于 **Java 进程的工作目录**：

| 启动方式 | 配置位置 |
| --- | --- |
| 在项目根目录运行 Java | 项目根目录的 `secrets.txt` |
| 桌面端启动内核 | 内核安装目录 `cpa-core` 下的 `secrets.txt` |
| 同一用户下多个启动目录共享配置 | 用户主目录的 `secrets.txt` |

桌面开发环境通常使用 `frontend/src-tauri/target/debug/cpa-core`。项目根目录即使已有完整配置，桌面内核也不会自动向上搜索该文件。JAR 所在目录也不一定是工作目录。

可以使用 JDK 工具核对运行目录，不需要输出密钥：

```powershell
# Replace 12345 with the Java kernel process ID.
jcmd 12345 VM.system_properties | Select-String '^user.dir='
```

## 修复步骤

1. 找到内核实际工作目录。
2. 将下面两项配置合并到该目录的 `secrets.txt`。使用实际的、相互匹配的 OAuth 客户端凭据，不要把占位文字当作凭据。
3. 在桌面端重启 Java 内核，再点击 Antigravity 登录。

```properties
proxy.oauth.antigravity.client-id=<OAuth client ID>
proxy.oauth.antigravity.client-secret=<OAuth client secret>
```

如果目标文件已经存在，保留其中其他配置。不要将 `secrets.txt` 放入 `src/main/resources`、提交到 Git 或打进发布包。

用户主目录的配置适合跨安装目录使用；但工作目录中同名配置会覆盖它。`my.txt`、启动参数、Java 系统属性和环境变量也可能覆盖文件值。排查时只检查配置是否存在、是否为空，不打印凭据。

## 验证

登录入口为 `GET /v8/management/oauth/auth-url?provider=antigravity`，需要管理接口认证。修复后应返回 HTTP 200，并在响应数据中提供 Google 授权地址、`state` 和 `authorization_code` 流程类型。授权地址不应包含 `client_secret`。

仅生成授权地址不能证明 Google 账号授权已经完成；还需要用户在 Google 页面完成登录和同意授权。接口验证创建的会话可通过 `POST /v8/management/oauth/session/cancel` 携带 `state` 取消。

本次本地验证发现：项目根目录配置完整，桌面内核工作目录缺少 `secrets.txt`。修复时仅将上述两项写入内核运行目录，没有修改账号文件或将凭据写入源码。

重启后，同一授权入口由 HTTP 500 恢复为 HTTP 200；已确认返回 Google 授权地址、非空 `state` 和 `authorization_code` 流程，地址中不包含 `client_secret`。测试会话取消接口返回 HTTP 200。本次验证未执行真实 Google 账号登录。

## 授权后的失败定位

Google 页面完成跳转后，内核依次执行 Token 换取、用户资料读取、Antigravity 项目初始化以及凭据保存。旧提示 `Token exchange failed` 将这些阶段统一显示，不能据此断定客户端凭据错误。

现在登录状态接口保留经过脱敏的失败阶段和处理建议：

| 提示 | 处理方式 |
| --- | --- |
| `invalid_grant` | 发起新的登录，使用本次授权生成的回调；不要刷新或重复提交旧回调 |
| `invalid_client` | 核对 OAuth client-id 与 client-secret 是否匹配，重启内核后重新登录 |
| `profile request failed (HTTP ...)` | 检查 Google 用户资料访问权限和出站代理 |
| `Antigravity loadCodeAssist failed (HTTP 403)` | 检查账号的 Antigravity 使用资格及权限 |
| `Antigravity onboardUser failed` | 检查账号初始化权限和出站代理 |
| `No active project was returned` | 确认账号已完成 Antigravity 初始化并有可用项目 |
| `Request timed out` / `TLS connection failed` | 检查桌面代理配置、代理服务连接和证书信任 |

Token、授权码、上游完整响应和原始异常消息不写入登录错误提示。内核日志只记录提供方、阶段、异常类型和脱敏后的原因。失败流程不会覆盖已有账号文件。

### 项目初始化请求

`loadCodeAssist` 使用 Antigravity User-Agent；需要初始化项目时，`onboardUser` 默认访问 `daily-cloudcode-pa.googleapis.com`，携带客户端标识、`ide_version` 和 `X-Goog-Api-Client`。每一步都先检查 HTTP 状态和错误字段，避免把上游拒绝误当成“没有项目”。

配置仍使用 `EnvUtils` 获取。以下配置供自有网关或隔离测试使用，通常不需要修改：

| 配置 | 含义 |
| --- | --- |
| `proxy.oauth.antigravity.control-url` | 项目查询服务根地址；显式设置时也作为初始化服务的默认地址 |
| `proxy.oauth.antigravity.onboard-url` | 独立覆盖初始化服务根地址，不包含 `:onboardUser` 后缀 |
| `proxy.oauth.antigravity.client-version` | 项目初始化上报的客户端版本 |
| `proxy.oauth.antigravity.user-agent` | Antigravity 请求的 User-Agent |

### 回归验证范围

8 项 OAuth 单元测试通过。隔离集成测试覆盖八个提供方的 OAuth 流程，并新增 Token 拒绝、资料读取失败、项目接口拒绝和项目初始化成功场景，检查错误脱敏、请求头、初始化元数据以及失败时已有凭据保持不变。

运行内核的真实 Google Token 接口也已验证：使用故意无效的测试授权码，Google 返回 `invalid_grant`，状态接口能显示对应原因。此测试仅证明网络和错误传递正常，真实账号登录成功仍须以完成新授权后状态为 `ok`、账号文件已保存为准。

部署修复版后，用户重新完成 Google 授权并确认登录成功；随后通过管理接口核对，Antigravity 账号已保存且状态为 `active`。旧流程未保留此次失败的具体原因，因此不能将最初的笼统报错精确归因到某一条 Google 响应。
