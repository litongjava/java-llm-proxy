# WorkBuddy 国内版接入

OAuth 页面提供独立的“WorkBuddy（国内版）”入口，渠道 ID 为 `workbuddycn`。国际版继续使用 `workbuddy`，CodeBuddy 使用 `buddy`。同一个用户分别授权时，三个渠道保存独立凭据，模型目录缓存也按渠道隔离。

## 产品参数依据

国内版参数依据本机官方 WorkBuddy 安装包的公开产品配置与客户端代码，而不是把国际版域名替换为 CodeBuddy 域名。读取范围限于 `cli/product.json`、应用包的产品元数据及认证、网关实现，没有读取本机用户账号文件。

- 国内服务：`https://www.workbuddy.cn`，登录 `platform=workbuddy`，认证类型为 `account-scoped-external-link`。
- 国际服务：`https://www.workbuddy.ai`，登录 `platform=workbuddy-ai`。
- CodeBuddy：`https://copilot.tencent.com`，登录 `platform=ide`。它不代表 WorkBuddy 国内版。

国内版登录首先请求 `/v2/plugin/auth/state?platform=workbuddy` 获取浏览器授权链接，再轮询 `/v2/plugin/auth/token?state=...`，最后调用 `/v2/plugin/account` 读取授权账号。国内账号接口不使用旧的 `/v2/plugin/login/account?state=...`。

刷新调用 `/v2/plugin/auth/token/refresh`，使用 `X-Auth-Refresh-Source: plugin`，随后重新读取账号信息。推理路径为 `/v2/chat/completions`，国内请求使用 `X-Product: SaaS` 和 WorkBuddy 产品身份。模型从该产品的模型服务发现，不复制国际版模型目录。

## 使用与配置

在 OAuth 页面选择“WorkBuddy（国内版）”，按浏览器页面完成授权。成功后到认证文件查看对应账号，再使用代理模型列表中的实际模型 ID 调用接口。已有国际版及 CodeBuddy 账号不会自动迁移或改写；国内账号应从国内入口重新授权。

默认服务地址无需额外配置。隔离环境测试时可以通过以下配置覆盖国内服务地址，配置通过 `EnvUtils` 读取：

```properties
proxy.oauth.workbuddycn.origin=https://www.workbuddy.cn
```

该配置与 `proxy.oauth.workbuddy.origin`、`proxy.oauth.buddy.origin` 独立。

## 验证边界

2026-10-16 验证通过：143 项 Java 测试、构建说明中的两条构建命令（跳过 GPG）、包含国内版的 10 渠道 HTTP 替身测试，以及前端生产构建和 22 项认证文件/登录状态测试。HTTP 测试校验国内 state/refresh 请求使用 `{}` JSON 正文、`X-User-Id` 身份头、企业模型目录、刷新后账号读取，以及三产品凭据隔离。最终后端测试端口为 11342、11345，未占用 8318。

本地 HTTP 替身用于验证协议、凭据隔离和推理转换，不能证明真实账号当前额度或上游模型权益。浏览器使用可见 Chrome 和 18319 前端模拟环境；国内模拟授权完成后，国内卡片显示独立账号，而国际卡片仍显示无账号。截图工具超时，界面结果以本次 DOM 回读为依据，不附旧截图。
