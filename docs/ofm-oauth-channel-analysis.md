# OFM 账号渠道与 OAuth 移植分析

分析日期：2026-10-16。分析对象为本地 `dsh-our-free-model` 源码快照，以下源码路径均相对于该项目。本文记录源码分析，不代表上游服务在分析当日已经通过真实账号登录或推理验证。

## 结论

将其中的账号授权流程移植到 Java 代理在技术上可行，无需整体运行 DeepSeek Harness。应同时移植授权、凭据续期和对应推理适配，不能只提取名称包含 `oauth` 的文件后把令牌直接用作任意 OpenAI API Key。

Cline 最适合优先完成端到端接入：登录采用设备授权，无需监听浏览器回调端口，推理使用 OpenAI Chat Completions。MiniMax Code 的授权也容易移植，但推理采用 Anthropic Messages。CodeArts 需要额外实现 DPoP 与华为请求签名；Qoder 的推理依赖客户端 WASM，加工成本明显更高。

## 项目原理

OFM 将匿名免费渠道、自带 API Key 的聚合渠道、账号渠道和 EAC 协付渠道组织在同一宿主中。账号渠道的核心位于 `vendor/channel-pack/src/`，实际发布入口是由该目录构建的 `vendor/channel-pack/pack.js`。

账号接入通常分为产品配置、授权流程、凭据服务、模型适配器四层。授权流程返回登录链接或设备码，用户在上游完成登录，服务端通过回调或轮询取得凭据。认证服务负责持久化、到期判断与刷新；账号池负责选择账号；适配器负责模型目录、上游协议和流式事件转换。本地 OpenAI 网关将这些适配器统一暴露为客户端可调用的接口。

宿主依赖包括 Cordis 服务体系、DSH 凭据库和 LLM 适配接口。协议函数通常允许注入 HTTP fetch 实现，说明网络流程与宿主可以分离。Java 接入可以用本项目的配置、凭据存储和 HTTP 客户端替换宿主组件，不需要保留 TypeScript 服务对象。

## 十三个账号渠道的真实类型

- **CodeArts**：OAuth authorization code，PKCE S256，加 ES256 DPoP。授权结果是临时 AK/SK/security token，后续推理需要华为签名，不是 Bearer token 直传。参考 `src/oauth.ts`、`src/login.ts`、`src/sign.ts`、`src/llm-adapter.ts`，这里的 `src/` 均位于 `vendor/channel-pack/` 下。令牌端点为华为 STS `/v1/oauth2/tokens`。
- **CodeBuddy**：腾讯 external-link-v2 浏览器授权，先请求 state，再轮询 token 和账号资料，并支持刷新。它是产品专用授权协议，不能按标准 OAuth token 表单处理。参考 `vendor/channel-pack/src/buddy-oauth.ts`、`buddy.ts`、`buddy-adapter.ts`。
- **WorkBuddy**：复用 Buddy 授权与适配实现，但使用独立产品配置、域名和账号资料。参考 `vendor/channel-pack/src/product.ts`；不要将两个产品的凭据与请求头混用。
- **LobsterAI**：本地浏览器回调收到 authCode 后换 token，源码不要求 PKCE/DPoP；推理接口为 `/api/proxy/v1/chat/completions`。参考 `vendor/channel-pack/src/lobsterai-oauth.ts`、`lobsterai.ts`、`lobsterai-adapter.ts`。
- **Qoder 国际版**：设备授权加 PKCE，浏览器授权后轮询 `/api/v1/deviceToken/poll`，无需本地回调。真实推理使用加密请求体及专有 SSE 接口。参考 `vendor/channel-pack/src/qoder-oauth.ts`、`qoder-product.ts`、`qoder-wasm.ts`。
- **Qoder 中国版**：复用 Qoder 流程，具有独立授权、OpenAPI 和推理域名；不能只替换模型名称。参考 `vendor/channel-pack/src/qoder-product.ts`。
- **TRAE**：产品专用 AuthCode 交换与用户信息查询，通过本地 `/authorize` 回调接收结果。源码默认回调端口为 18080，但文件注释对端口是否固定存在不同描述，实际兼容范围需要实测确认。推理走 `/api/agent/v3/llm_utils_chat`。参考 `vendor/channel-pack/src/trae-oauth.ts`、`trae.ts`、`trae-adapter.ts`。
- **Cline**：WorkOS OAuth 设备授权后，再向 Cline 注册换取产品凭据；刷新通过 Cline 自己的接口。OpenAI 推理鉴权值必须保留 `workos:` 前缀。参考 `vendor/channel-pack/src/cline-oauth.ts`、`cline-auth.ts`、`cline.ts`、`cline-adapter.ts`。
- **Loomy**：短信验证码及微信登录，获得讯飞 session，账号接口使用 HMAC-SHA1 签名。虽然文件名为 `loomy-oauth.ts`，这不是标准 OAuth 授权。参考 `vendor/channel-pack/src/loomy-oauth.ts`、`loomy-wechat-login.ts`、`loomy-sign.ts`。
- **Raccoon**：微信扫码轮询或短信验证码登录，获得 access/refresh token；短信路径需要验证码参数及手机号加密。源码保留桌面授权码交换，但默认采用扫码流程。参考 `vendor/channel-pack/src/raccoon-oauth.ts`、`raccoon-login-page.ts`。它不是直接可复用的通用 OAuth provider。
- **MiniMax Code**：OAuth 设备授权加 PKCE，具有 device code、token、refresh 流程。等待授权既可能表现为标准 OAuth error，也可能是 HTTP 200 的 `status=pending`。推理采用 Anthropic Messages。参考 `vendor/channel-pack/src/minimax-oauth.ts`、`minimax-product.ts`、`minimax-messages.ts`。
- **ZCode**：服务端中介设备授权，初始化 `/api/v1/oauth/cli/init`，用户在返回链接登录，轮询 `/api/v1/oauth/cli/poll/{flow_id}` 获得 token。源码明确使用这一授权路径，不读取本机 ZCode 客户端凭据。参考 `vendor/channel-pack/src/zcode-login.ts`、`zcode-upstream.ts`、`zcode-anthropic.ts`。
- **Gemini**：Google OAuth authorization code 加 refresh token；使用客户端 ID 与客户端 secret，不采用该项目 CodeArts 的 PKCE/DPoP。Google Cloud Code 推理走 `generateContent` / `streamGenerateContent`，还涉及项目发现与配额查询。参考 `vendor/channel-pack/src/gemini-oauth.ts`、`gemini-project.ts`、`gemini-adapter.ts`。应与本项目已有 Gemini/Antigravity 能力比较后再决定复用边界。

## 不属于上述 OAuth 移植范围的功能

匿名 OpenCode 与 Kilo 是免费网关路径，不要求这套账号 OAuth。上游包还包含 OpenCode 账号适配，但 OFM 挂载时明确关闭该入口，见 `vendor/channel-pack/NOTICE.md`。

自带 Key 的聚合注册表虽然列出 OAuth 或 ADC 类型的提供商，README 明确说明这些条目只能展示、不能由该聚合车道驱动。注册表中的存在不等于已实现登录或推理。

EAC 使用维护者协付网关、GitHub 授权和宿主访问控制。它不是独立模型厂商的 OAuth 渠道，不应通过复制密封凭据或删除授权判断接入。普通账号渠道的实现也不意味着可以用任意网页 Cookie 代替授权所得凭据。

## Cline 的最小完整接入链路

1. POST `https://api.workos.com/user_management/authorize/device`，使用源码公开客户端 ID，取得 device code、user code、验证链接、有效期及轮询间隔。
2. 向客户端返回验证链接与 user code，服务端保留 device code。轮询 `/user_management/authenticate`，使用 `urn:ietf:params:oauth:grant-type:device_code` grant。正确处理 pending、slow_down、取消、过期以及瞬时网络错误。
3. 将 WorkOS 返回的 `accessToken`、`refreshToken` POST 到 `https://api.cline.bot/api/v1/auth/register`，保存产品凭据，不向普通状态接口返回 token。
4. 通过 `/api/v1/auth/refresh` 发送 `{ "refreshToken": "...", "grantType": "refresh_token" }` 完成续期，防止并发刷新覆盖新凭据。
5. 使用 `Authorization: Bearer workos:<token>` 和产品请求头调用 `/api/v1/chat/completions`。源码采用 SSE，并将思考内容放在 `delta.reasoning`，需要检查现有代理是否透传或归一该字段。
6. 通过 `/api/v1/models` 与 `/api/v1/ai/cline/recommended-models` 获取动态目录。免费资格由服务端决定，不硬编码永久免费的模型名称。

源码的设备轮询 `slow_down` 每次增加一秒；移植时应明确采用上游兼容策略还是标准 OAuth 设备授权至少增加五秒的策略，并进行测试，不能无审查地复制。

## 原生 Java 可行性与验证边界

上述纯 HTTP 授权、JSON、PKCE、JWT 和 HMAC 都能用 Java 实现。Cline 不需要额外本地端口，适合远程部署；需要 loopback 回调的渠道则必须考虑浏览器与服务端是否位于同一机器。测试服务应使用独立端口，避免占用现有 8318。

Qoder 是主要例外：登录流程容易分离，但完整推理依赖 `qoder-auth-wasm.wasm` 与 wasm-bindgen 桥，直接移植需要适配 WASM 运行时或重新实现协议；仅实现登录不能宣称完成该渠道。还应单独核查随包二进制的来源与分发权利，不能仅依据 TypeScript 包的 MIT 声明推断所有外部资产。

本次只读取了源码和说明，没有执行真实账号授权、领取积分、消耗推理额度或读取本地用户 token。源码中的历史实测注释是项目作者记录，不能作为本次独立实时验证。完成集成后，应分别验证设备授权状态机、凭据刷新、管理接口鉴权、非流式/流式代理和错误响应；真实上游可用性还需要有效账号完成验证。

## 来源与许可证

OFM 主项目 `LICENSE` 为 MIT，版权声明为 `Copyright (c) 2026 Ebony-Vinyl`。

账号包的 `vendor/channel-pack/LICENSE` 为 MIT，版权声明为 `Copyright (c) 2026 Jet`。`vendor/channel-pack/NOTICE.md` 记录来源为 [dsh-codearts-auth](https://gitee.com/iJetLi/deepseek-harness-codearts)，吸收的提交为 `345f0a07b22713c0ae189ca7d8b97ec4f64626c6`。

若复制或翻译其中实质性代码，应随项目保留对应版权、MIT 许可全文和来源说明。许可证允许代码复用，但不能据此推断上游账号的额度、可用模型或第三方服务访问规则。
