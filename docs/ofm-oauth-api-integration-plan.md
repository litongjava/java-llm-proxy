# OFM 账号登录与 API 能力整合方案

状态：历史设计提案。2026-10-16 用户选择先完成非 WASM 渠道的原生 Java 接入，当前实现以[原生渠道接入说明](native-account-channels.md)为准，本文的 Node 辅助进程方案未实施。源项目 Gemini 实际采用与现有 Antigravity 相同的 OAuth 客户端和产品身份，不作为独立新增渠道。

## 1. 结论与范围

推荐采用 **Java 主网关 + OFM Node.js 本地渠道引擎（sidecar）+ 现有 React/Tauri 管理界面**。

不是把 DSH 本体嵌入 Java，不是把浏览器页面嵌入 OAuth 页，也不是用 OFM 替换现有 Java 内核。目标项目已经具备 OAuth、凭据管理、模型路由、流式协议转换和对外 API；本次补齐的是新增渠道与现有体系之间的契约。

第一阶段保留现有 Codex、Claude、Antigravity、Kimi、xAI、Meta、Devin、Vertex 接入。新增渠道从 OFM 独立运行时接入，登录、刷新令牌与账号内调度仍由该运行时负责。一个渠道只分配给一个执行引擎，避免双重刷新、重复注册和模型重名。

### 1.1 用户可见结果

- OAuth 页新增可用的 OFM 渠道卡片，支持添加多个账号、选择浏览器、复制登录链接、取消与查看状态。
- 非标准 OAuth 渠道明确显示“网页登录”“设备码”“二维码”或“导入”，不把所有渠道误称为 OAuth。
- 账号页统一展示 Java 与 OFM 账号，标记来源，支持各自实际具备的启停、删除、优先级、模型及配额操作。
- 首页仍展示 Java API 地址，默认 `http://127.0.0.1:8318/v1`；外部调用方不需要知道 Node 端口或渠道令牌。
- Java 原生账号即使 OFM 引擎未安装、启动失败或崩溃，也能继续使用。
- 首页分别展示“本地服务就绪”“登录完成”“模型已发现”“推理已验证”。不能把模型列表存在等同于调用成功。截图中的 1 个凭据、9 个模型和 0% 成功率恰好说明这几个状态需要区分。

### 1.2 本次不做

- 不重写十三套渠道协议，不把整个 DSH 插件加载进 JVM。
- 不自动导入原 DSH、用户浏览器或其他 IDE 的凭据。
- 不新增设备身份轮换、验证码规避、IP 换额等策略；复用渠道前需要审计并关闭不需要的自动任务。
- 不承诺所有订阅账号可转供公共服务；须逐提供商核对授权范围、额度与服务条款。
- EAC 不列入首批，后续作为可选渠道保留其原有服务端授权，不解密或复制密封密钥到 Java。
- 不宣称截图中的所有渠道已真实可用；本方案为源码审阅结论，未进行真实账号登录或推理测试。

## 2. 现状与证据

### 2.1 Java 项目不是空壳

| 能力 | 已有实现与整合意义 |
|---|---|
| Java 运行栈 | [pom.xml](<D:/code/java/project-litongjava/java-llm-proxy/pom.xml#L8-L19>)：Java 21、tio-boot；沿用现有 Handler/Service 风格，不引入另一套 Spring Web 控制器 |
| API 与管理路由 | [LLMProxyAppConfig.java](<D:/code/java/project-litongjava/java-llm-proxy/src/main/java/nexus/io/llm/proxy/config/LLMProxyAppConfig.java#L29-L120>)：已有 `/v8/management` 与 Chat、Responses、Messages、Gemini 入口 |
| OAuth 状态机 | [OAuthService.java](<D:/code/java/project-litongjava/java-llm-proxy/src/main/java/nexus/io/llm/proxy/service/OAuthService.java#L39-L140>)：会话、超时、PKCE、设备码与插件登录分支；保留 Java 原生流程 |
| 凭据转 Provider | [CredentialProviderService.java](<D:/code/java/project-litongjava/java-llm-proxy/src/main/java/nexus/io/llm/proxy/service/CredentialProviderService.java#L12-L86>)：把本地凭据加载为 Provider，再按渠道刷新；不应把 OFM 的脱敏账号引用放进这条路径 |
| 调度 | [RoutingService.java](<D:/code/java/project-litongjava/java-llm-proxy/src/main/java/nexus/io/llm/proxy/service/RoutingService.java#L15-L114>)：会话亲和、优先级、权重、冷却；需要与 OFM 内部账号调度分层 |
| 请求执行 | [ProxyService.java](<D:/code/java/project-litongjava/java-llm-proxy/src/main/java/nexus/io/llm/proxy/service/ProxyService.java#L47-L170>)：选择 Provider、转换协议、构建上游请求；可复用，不再造一个对外网关 |

目标工作区已有未提交的 SSE、配额页与凭据相关改动。本方案不覆盖这些改动；实施时从当前工作树建立基线，避免整文件替换和回退。

### 2.2 截图前端的真实连接方式

前端来自 EasyCLIProxyAPI，但现有桌面运行器已经启动 Java JAR，不是仍在运行 Go CPA。React 服务通过 Tauri invoke 调用 Rust，再请求 Java 管理 API。

- [managementApi.ts](<D:/code/java/project-litongjava/java-llm-proxy/frontend/src/services/managementApi.ts#L53-L93>)：管理请求不是直接 fetch。
- [management_api.rs](<D:/code/java/project-litongjava/java-llm-proxy/frontend/src-tauri/src/management_api.rs#L78-L133>)：PUT/PATCH/DELETE 会被转换为 POST，附带 `X-Proxy-Method`；配置写入还会封装 `operation/value`。
- [management_api.rs](<D:/code/java/project-litongjava/java-llm-proxy/frontend/src-tauri/src/management_api.rs#L443-L461>)：管理根路径固定为 `/v8/management`。
- [ManagementPages.tsx](<D:/code/java/project-litongjava/java-llm-proxy/frontend/src/pages/ManagementPages.tsx#L45-L103>)：内置渠道静态定义；已有插件渠道动态扩展，但并非自动支持 DSH 渠道。
- [authFiles.ts](<D:/code/java/project-litongjava/java-llm-proxy/frontend/src/services/authFiles.ts#L73-L91>)：账号判断与 JSON 凭据文件耦合，需要扩展为外部账号引用。
- [core_runtime.rs](<D:/code/java/project-litongjava/java-llm-proxy/frontend/src-tauri/src/core_runtime.rs#L1482-L1541>)：现有桌面生命周期管理 Java；不需要替换为 Node，只增加渠道引擎状态展示。

因此不能仅修改 Base URL，也不应让 React 直接拿 OFM 管理 Key 调用 Node。

### 2.3 OFM 已有可复用的独立运行时

[独立服务说明](<D:/code/project/project-deepseek-harness/dsh-our-free-model/packages/standalone/README.md#L1-L68>)表明：

- 不要求安装 DSH；支持独立数据目录、回环监听、API Key、账号渠道和管理接口。
- 已明确提供 `/v1/models`、`/v1/chat/completions`、`/v1/responses`。
- 不能只复制 standalone 目录：仍依赖共享核心、生成的渠道业务产物及 WASM。
- 独立制品尚未正式发布，不能当作已有稳定 npm SDK 使用。
- 专项测试主要使用本机替身，不能据此推断全部真实渠道可用。

关键复用点：

- [runtime.mjs](<D:/code/project/project-deepseek-harness/dsh-our-free-model/packages/standalone/channels/runtime.mjs#L6-L98>)：每实例 Worker、账号渠道 RPC、模型清单、取消传播。
- [contracts.mjs](<D:/code/project/project-deepseek-harness/dsh-our-free-model/packages/standalone/channels/contracts.mjs#L3-L32>)：独立平台适配，避免直接依赖 DSH 宿主。
- [management.mjs](<D:/code/project/project-deepseek-harness/dsh-our-free-model/packages/standalone/management.mjs#L186-L215>)：渠道管理是 `/api/management/channels/rpc`，与 Java 管理 API 不同。
- [credentials.mjs](<D:/code/project/project-deepseek-harness/dsh-our-free-model/packages/standalone/channels/credentials.mjs#L5-L40>)：凭据以 JSON 原子保存，并非已加密存储；Windows 上 `0600` 也不能替代 ACL 验证。

## 3. 技术选型

| 方案 | 优点 | 成本与风险 | 决策 |
|---|---|---|---|
| 全量 Java 移植 | 单运行时，便于最终统一 | 要重写认证、签名、专用协议、流式解析、WASM 等，维护分叉大 | 暂不采用；后续逐渠道迁移 |
| Java + OFM sidecar | 复用登录与推理，隔离故障，保持现有产品 | 多进程打包、桥接契约、凭据边界 | 推荐 |
| 前端直接调用 OFM | 原型快 | 暴露内部密钥、双 API 地址、绕开 Java 审计和消费 Key | 不采用 |
| 将 JS 直接装成现有原生插件 | 看似沿用插件页 | 当前原生插件 ABI 与 JS/Cordis 模块不同，并非直接兼容 | 首期不走这条路 |

首期把 sidecar 看作受 Java 管理的“渠道引擎”，不是普通无约束 HTTP 代理。

## 4. 总体架构与责任边界

```text
React 管理界面
  -> Tauri management_request
    -> Java /v8/management/channels/*
      -> ChannelEngineClient（白名单 DTO / RPC 适配）
        -> OFM 本地管理桥接接口
          -> 渠道登录、账号池、刷新与模型目录

SDK / Claude Code / Codex 等调用方
  -> Java :8318 /v1/*（消费 Key 鉴权、限流、审计、路由）
    ├─ 原 Java Provider -> 原有上游
    └─ OFM Provider -> 127.0.0.1:<动态端口>/v1/*
                       -> 渠道适配器 -> 已授权上游
```

| 模块 | 唯一责任 |
|---|---|
| Java | 对外 Key、权限、模型别名、引擎级路由、外层协议转换、对外逻辑请求统计 |
| OFM | 新渠道 OAuth/网页登录、上游 token、续期、渠道内账号选择、上游协议、内部重试明细 |
| Tauri | 启动 Java、打开选定浏览器、桌面配置和状态展示 |
| React | 渲染脱敏账号和登录状态；不接触上游 token/refresh token |

**进程归属选择：由 Java 管理 Node。** 这样桌面模式与 headless Java 模式共用同一生命周期；Tauri 不再另行启动一个 Node，避免两方重复拉起。后续可以实现进程运行器接口，但不改变唯一所有者原则。

## 5. 渠道与能力注册

新增 `ChannelProviderDescriptor`，由 Java 管理接口返回：

```json
{
  "id": "ofm:cline",
  "engine": "ofm",
  "displayName": "Cline",
  "available": true,
  "authMethods": ["device_code"],
  "capabilities": {
    "login": true,
    "cancelLogin": true,
    "refresh": true,
    "quota": false,
    "accountPriority": false,
    "exportCredential": false
  }
}
```

此 JSON 是拟定契约，不是对现有 Cline 功能逐项认证；取值以渠道适配测试为准。UI 只展示后端声明且通过验收的能力，不靠 provider 名字猜功能。

渠道分组建议：

1. **既有 Java 渠道**：完全保留，不改登录流程。
2. **第一批 OFM 渠道**：从 Cline、Gemini Code Assist 中选取具备合法测试账号和可用网络者，完成登录、刷新、文本与工具调用的端到端验证。
3. **第二批**：CodeArts、CodeBuddy、WorkBuddy、LobsterAI、MiniMax 等，逐家启用。
4. **复杂渠道**：Qoder/Qoder CN、TRAE、Loomy、Raccoon、ZCode 按专用协议、二维码、设备证明、浏览器依赖分别验收，不保证只增加 OAuth 配置就能接入。
5. **免密来源**：Kilo/OpenCode 作为独立来源分类，默认按产品决策启用，不显示“登录”按钮。

Gemini Code Assist 与现有 Antigravity/Vertex 不是同一 provider，MiniMax 与现有 Kimi 也不能混用；显示名相近不代表 token、端点和额度可互换。

## 6. 登录与账号管理设计

### 6.1 API 契约（新增，均走现有管理鉴权）

以下路径相对于 `/v8/management`，变更操作使用 POST，以兼容现有 Rust 转发约定：

| 方法与路径 | 用途 |
|---|---|
| GET `/channels/providers` | 渠道注册表与支持的登录方式 |
| GET `/channels/engine/status` | 引擎版本、ready/degraded 状态、兼容版本 |
| POST `/channels/oauth/start` | `{provider, authMethod}`，创建登录事务 |
| GET `/channels/oauth/status?session_id=...` | 查询脱敏状态 |
| POST `/channels/oauth/cancel` | `{session_id}`，取消登录及底层轮询 |
| POST `/channels/oauth/callback` | 仅对声明支持手动回调的渠道开放 |
| GET `/channels/accounts` | 联合账号只读视图，支持 provider 过滤 |
| POST `/channels/accounts/status` | `{account_id, enabled}` |
| POST `/channels/accounts/delete` | 删除指定引擎账号；不接受文件路径 |
| POST `/channels/accounts/refresh` | 请求所属引擎刷新，支持异步任务 |
| GET `/channels/accounts/models` | 某渠道或账号可访问的模型 |
| GET `/channels/accounts/quota` | 支持时查询，否则返回 unsupported，不返回 0 |
| POST `/channels/models/sync` | 显式同步，不等同于消耗额度的推理探测 |

沿用 Java `RespBodyVo` 外层封装；具体 HTTP 状态、业务错误码与 data DTO 建立契约测试。前端调用新增 `channels` 服务，不能假设旧 `start_oauth` 原生命令自动认识这些路径。

### 6.2 登录状态机

```text
CREATED -> WAITING_USER -> EXCHANGING -> SUCCEEDED
                  └-----> FAILED / CANCELLED / EXPIRED
```

- 创建事务返回 `sessionId/provider/flow/authorizationUrl/userCode/expiresAt/pollIntervalMs`；二维码流程可附 `qrPayload`。不返回 access token。
- Java session ID 是管理操作句柄；与上游 OAuth state、Node 的内部 flow ID 分离，维护映射，不擅自重写上游 state。
- 提供商协议支持时使用 PKCE；上游 state、回调重放防护及 token exchange 仍由所属引擎验证。
- Tauri 按用户选择打开系统浏览器；选择“不自动打开”时只显示链接。Node 托管模式禁止重复打开默认浏览器。
- Node 完成令牌交换和持久化后才报告成功；Java 随后更新账号视图、触发模型同步。不要以浏览器打开或回调到达作为成功判据。
- 页面切换取消 UI 轮询；是否同时取消登录须明确交互。点击取消必须下传，不得只关闭弹窗。
- 严格处理过期、拒绝授权、slow_down、端口冲突、晚到回调与重复点击。进程重启后未完成事务标记失效，不复用旧 state。
- 账号去重键为 `(engine, provider, region, upstreamSubject)`；只有可信用户信息接口或验证后的身份信息可作为 subject，不能只按邮箱合并。
- 刷新由 OFM 负责，按账号 single-flight；原子替换轮换的 refresh token。Java 不再调自身 CredentialService 刷新这些账号。

### 6.3 账号视图与凭据存储

新增统一视图 `ChannelAccountView`：

```json
{
  "id": "ofm:cline:opaque-account-id",
  "engine": "ofm",
  "provider": "ofm:cline",
  "displayName": "已脱敏账号名称",
  "enabled": true,
  "status": "active",
  "expiresAt": null,
  "credentialKind": "external_reference",
  "capabilities": {"download": false, "refresh": true}
}
```

不要伪造一个可下载的 JSON 凭据文件以迎合旧 UI。扩展账号页数据源，把 Java 原生文件账号与 OFM 外部引用组合显示；文件下载、字段编辑和打开目录等只对相应来源开放。首期关闭 OFM 凭据导出；未来若需要迁移，应采用专门的管理员确认、加密导出流程。

OFM 是新增渠道 token 的唯一权威存储，Java 只保留 opaque ID、模型与状态。分配独立数据目录，不能与原 DSH 或用户已运行的 standalone 共享可写目录。

现有 standalone 明文 JSON 凭据存储须在发布前加固：通过可替换 credential store 引入 AEAD 加密，密钥用 OS 安全存储/外部 secret 引导，不能与密文放在同一普通文件。Windows 明确设置和验证 ACL；原子写、防并发、旧格式迁移与恢复失败都要测试。

## 7. OFM 管理桥接：必须新增的边界

现有 `/api/management/channels/rpc` 可以复用为内部实现，但不直接暴露到 Java 公共管理 API。建立薄的版本化 facade，由渠道适配表把统一操作映射到原 RPC。

### 7.1 需要新增到引擎的能力

- `bridgeVersion`、渠道能力列表和结构化就绪信息。
- 统一的 start/status/cancel/accounts/models 接口；登录流程不统一的渠道由自己的 adapter 转换。
- headless/managed 开关：不启动管理浏览器、不打印一次性管理链接、不主动拉起登录浏览器。
- 将端口、实例 ID、桥接协议版本写入受限权限的 ready 文件，原子提交；Java 验证实例 ID 和随机握手挑战，不仅测试端口是否打开。
- 默认关闭自动签到、自动额度领取、订阅出口、应用内自更新和不需要的恢复请求；注意现有 `--no-refresh` **不会关闭渠道自动任务**，必须提供真正的托管策略开关。
- 有界、可取消的推理与登录任务，允许受控优雅退出。

### 7.2 内部鉴权

必须区分三个身份域：外部消费 Key、Java 管理 Key、Java→OFM 桥接凭据。

现有 standalone 的同一个 `forwardKey` 能访问推理与管理接口，因此不能把它当作外部消费 Key，也不能发给 React。集成版建议分离 bridge management token 与 inference token，接口校验不同 scope；令牌经安全本地 IPC 或受限配置传递，不放 URL、命令行、日志和 ready 文件。

白名单 facade 不允许任意 RPC、URL、文件路径或 shell 参数。特别禁止透明代理备份导出、密钥查询、密钥轮换等操作。现有 `gateway.getEnabled` 返回内容包含内部 API Key，不能把原响应直接交给 UI。

## 8. 对外 API 与模型路由

### 8.1 模型命名

外部 ID 建议：`ofm/<channel>/<upstream-model>`。

例如 `ofm/cline/<实际发现的模型ID>`，而不是预置一个未经验证的模型名。保存显式映射：

```text
publicModelId -> engineId + channelId + engineModelId + capabilities
```

不要按 `/` 固定拆三段，上游模型 ID 本身可能包含斜杠。现有 Java 模型 ID 和别名保持不变；冲突通过命名空间消除，不静默覆盖。

### 8.2 Java 集成点

- 新增 `ChannelProviderCatalogService` 产生内存中的引擎 Provider，与原 API Key Provider、OAuth Provider 合并。
- 引擎 Provider 不设置 `credentialName`，避免进入 Java 凭据刷新；内部密钥在请求发送时从 secret provider 注入，不出现在可序列化配置视图。
- 模型解析不仅选 provider，还要按目标端点选择协议路径。现有 Provider 只有一个 `protocol`，不可默认它同时代表 Chat 与 Responses。
- 第一阶段先支持 Chat；Responses 增加 endpoint-aware selection 后直通到引擎原生 Responses，避免无谓 Chat→Responses→Chat 转换。
- Anthropic Messages、Gemini 可借助已有 Java 转换层，但只有工具、图片、思考、usage 语义通过专项测试后才宣称支持该渠道。未知扩展字段不能静默丢失。
- OFM 不支持的 realtime、音视频、服务端持久化 Responses 等能力明确拒绝，不能因为 Java 有对应路由就向所有模型广播。

### 8.3 推理与重试规则

1. Java 验证消费 Key、模型访问权限、请求大小、并发和预算。
2. 解析模型映射，选 Java 原生 Provider 或 OFM 引擎 Provider。
3. 外部 Authorization/Cookie 不向引擎透传；Java 注入引擎推理凭据。
4. 流式请求采用有界缓冲与增量转发；客户端断连必须取消 Node 与实际上游请求。
5. Java 只做引擎级选择，Node 做渠道内账号选择；首期禁止同一请求在两层各进行多次自动重试。
6. 默认不跨引擎静默切换收费/免费渠道；若配置 fallback，明确模型、预算与用户预期。
7. 一旦对外发送任何 SSE 事件就不透明重试；输出工具调用后绝不自动重新执行同一回合。
8. 429 遵守 Retry-After；401 最多由所属引擎进行一次受控刷新；403 标记权限/政策失败，不用换身份自动规避。
9. 流内报错按各协议终止语义处理，不能伪装正常完成或以空响应算成功。

### 8.4 统计归属

Java Usage 是对外逻辑请求的权威记录；Node 保留实际物理调用、上游账号、重试等内部诊断。两者通过 `requestId/attemptId` 关联，不能把同一次调用加算两遍。

第一阶段 Node 若不能回传实际选中账号，Java 只显示“OFM/渠道”，不猜账号、不伪造明细。后续用版本化结果元数据补齐。配额未知显示 unknown/unsupported；免费价显示 0 需有依据，不能因为计费不可得就假定免费。

## 9. 前端页面改造

| 页面 | 改造内容 |
|---|---|
| OAuth Sign-In | 合并 Java 内置卡片与动态渠道描述；按 flow 展示 URL、设备码、二维码；显示有效期与失败原因 |
| Authentication Files | 扩展为账号/凭据联合视图；增加来源和能力，外部账号禁止文件下载/任意字段编辑 |
| Home | 保留 Java URL 和消费 Key；增加 OFM 引擎状态，不用一个 Running 代表所有上游正常 |
| API Access | 现有主要是上游 API Key 组管理；新增渠道应与消费者 Key 管理明确分区 |
| Quota Lookup | 根据 capability 查询；显示实际来源与更新时间，不把不支持显示成余额为零 |
| Usage | 显示模型命名空间、引擎、逻辑请求、失败原因；详细内部尝试另列 |
| Version Management | 增加 OFM 引擎与 bridge 兼容版本；和 Java、桌面更新解耦 |

可复用浏览器选择、账号卡片、轮询清理、模型健康和现有配色，不复制 OFM 全套管理页面，避免两套设置互相覆盖。

同时补齐既有风险：手动 OAuth callback 的 HTTP 200 + `ok:false` 必须按失败处理；远程管理连接不得接受无效 TLS 证书；新增流程必须按 expiresAt 结束轮询。

## 10. 运行、打包与升级

- Node 引擎需符合 OFM 当前 engines：`^22.19.0 || >=24.0.0`。发布时固定一个验证过的版本，不依赖用户随意安装的 Node。
- 开发阶段可以由 Java 指向完整 OFM checkout；发行阶段生成独立引擎包，包含 CLI、共享核心、渠道产物、WASM、许可证和必要资源，不包含开发缓存、账号数据与构建目录。
- Java `ChannelEngineProcessService` 使用参数数组启动子进程，不拼 shell 命令；监听回环随机端口，等待带实例身份的 readiness。
- 初期可配置外部手动启动模式以便诊断，但默认托管模式只能有一个进程所有者。
- 状态区分 STARTING、READY、DEGRADED、STOPPED、FAILED；渠道模型首次加载慢不应阻塞 Java 原有服务。
- 采用有限指数退避重启；连续失败停止自动重启并展示诊断，不建立无限循环。
- 停止时先停止接收新请求，给在途请求有限宽限期，随后取消并回收进程树；Windows 复用/扩展现有 Job 管理思路，验证 Java 被强杀后 Node 不残留。Unix 验证进程组与孤儿退出策略。
- 不盲目删除数据锁；核验 owner PID、实例 nonce 和实际存活状态。
- 安装与更新验证签名/摘要、限制解压路径、原子切换；失败可回滚旧二进制。若数据 schema 不可逆，必须阻止不兼容回滚或在停服快照后恢复。
- 上游许可证与署名完整保留，记录 OFM 和渠道包来源提交、生成脚本与本地补丁；WASM 与第三方依赖另行核对再分发许可。

## 11. 安全与暴露范围

“对外 API”第一阶段定义为提供给本机其他程序使用，不等同于直接暴露公网。

- Java 和 Node 默认回环监听；如需 LAN，仅 Java 开放，独立消费 Key、访问控制、并发限额和审计必须先验收。
- 公网模式另设里程碑：TLS 入口、租户隔离、消费 Key 生命周期、限流、账单、合法转供权限、敏感数据策略；管理路由不能随推理路由无差别暴露。
- 消费 Key 不可访问管理端点；Java 管理 Key 不发送给 Node；上游 token 不发送给外部调用方。
- 新增登录 URL 与手动回调须校验协议、预期域名、state 和固定回调配置，拒绝 `file:`、`javascript:` 与任意地址请求。
- 不记录授权码、PKCE verifier、token、二维码临时票据、Cookie 或完整 callback URL；沿用并扩充日志脱敏测试。
- 配置出站代理只用于合法网络连通性，不用于绕过配额；回环桥接请求明确不走代理。
- OFM 代码可进行接口兼容和用户授权登录，但不能把“采用 OAuth”自动解释为“允许第三方转售/无限调用”。发布前做逐渠道政策评审。

## 12. 代码落点（拟新增）

Java package 前缀：`nexus.io.llm.proxy`。

```text
model/channel/
  ChannelProviderDescriptor, ChannelAccountView, ChannelLoginSession
  ChannelModelBinding, ChannelEngineStatus
service/channel/
  ChannelEngineProcessService  # Node 生命周期与就绪检查
  ChannelEngineClient          # HTTP + 内部鉴权 + 白名单
  ChannelOAuthFacade          # 统一登录事务
  ChannelAccountFacade        # 账号元数据与操作
  ChannelProviderCatalogService # 模型与 Provider 注册
  ChannelEngineSecretService  # 内部令牌获取，不进入DTO
handler/channel/
  ProviderListHandler, EngineStatusHandler
  OAuthStartHandler, OAuthStatusHandler, OAuthCancelHandler
  AccountListHandler, AccountStatusHandler, AccountDeleteHandler
```

修改范围：现有路由配置增加管理入口，模型选择增加引擎目录来源，现有执行路径增加受控引擎凭据注入与按端点选协议。前端增加 channel service 与动态卡片，账号管理增加外部引用数据源。不要先在大型 ProxyService 中堆叠全部渠道的专用逻辑。

OFM 侧在独立运行时周边增加版本化 bridge facade、托管启动策略、凭据存储扩展和 readiness；渠道原始业务保持尽量少的补丁。修改生成源后重新生成业务产物，不手改巨大的生成 bundle。

## 13. 分阶段交付与验收

以下为工程估算，假设 1 位熟悉 Java/Tauri/Node 的工程师；真实渠道变化、账号授权审批与平台政策不在估算保证内。

| 阶段 | 主要交付 | 完成门槛 | 估算 |
|---|---|---|---|
| P0 基线与契约 | 当前改动隔离、范围/渠道清单、bridge DTO、mock upstream、许可证清单 | Java 原有测试基线记录，关键 RPC映射验证 | 2–3 人日 |
| P1 本地引擎 | 启动/停止、鉴权、readiness、模型同步、Chat 普通/流式 | 无 DSH 环境可运行；Node 故障不影响原渠道 | 3–5 人日 |
| P2 登录整合 | 动态卡片、登录/取消/过期、外部账号视图、存储加固 | 至少一个真实授权渠道完成登录→刷新→推理 | 4–6 人日 |
| P3 API兼容 | Responses、必要 Messages/Gemini转换、取消、usage、重试边界 | 协议矩阵、工具、图片、流内错误与回归通过 | 3–5 人日 |
| P4 渠道扩展与发布 | 分批渠道验收、跨平台包、更新回滚与文档 | 每渠道记录真实支持范围，发布包无秘密 | 4–8 人日起 |

首个实用版本以 P0–P2 为目标，约 9–14 人日。首期不承诺十三个渠道全部实测、所有协议完整支持或公网运营能力。

### 13.1 必测矩阵

- OAuth：state错误、重放、超时、取消、拒绝、浏览器打开失败、端口冲突、刷新轮换、多账号并发。
- 凭据：Java与Node只由各自所属引擎刷新；前端/日志/ready文件无token；Windows ACL、加密迁移、原子保存失败。
- API：普通/流式、空正文、usage缺失、工具多轮、图片、模型命名冲突、不支持能力明确拒绝。
- SSE：正确Content-Type、误标Content-Type、半帧、多帧、UTF-8边界、慢消费者、断流、流内错误；复用目标项目正在进行的 SSE 修复并增加桥接回归，不覆盖该工作。
- 重试：429不反复重发、403不绕过、已出事件不重试、工具不重复执行、Java/Node重试数有总上限。
- 进程：Node启动失败、Worker退出、Java重启/强杀、端口占用、数据锁、版本不兼容、旧引擎回滚。
- 权限：消费Key不能访问管理API；桥接API不对局域网开放；未知RPC/URL/路径拒绝；任意Origin不获管理权限。
- 统计：一个客户端请求只计一次逻辑用量，内部尝试可追溯，未知账号或额度不伪造。
- 回归：已有 Codex/Claude/Antigravity 等登录、原 API Key 上游、Usage与桌面生命周期不退化。

离线契约测试与真实验收分别报告。真实测试只在用户授权的测试账号上执行，并事先提示消耗额度；不能用模拟服务200替代真实可用性证明。

## 14. 开工前需确认

建议默认采用：保留 Java 主网关；新增 OFM sidecar；先做本机 API；先落地一个真实渠道；不默认开启自动签到、EAC或公网访问。

实施前确认四项：

1. 首批实际要接入的渠道和可用于授权验收的账号。
2. API服务范围是本机、LAN还是公网多租户。
3. 是否接受随安装包携带 Node；若要求纯Java，需要改用逐渠道移植计划并重估工作量。
4. OFM代码与渠道协议的许可/服务条款审核结果，以及复杂渠道的启用策略。

**最终验收链路：在现有桌面OAuth页登录新增渠道 → 账号页出现脱敏账号 → Java模型目录可见 → 使用现有Java消费Key调用8318端口成功 → Token续期、流式工具调用、取消与错误统计均可验证。**
