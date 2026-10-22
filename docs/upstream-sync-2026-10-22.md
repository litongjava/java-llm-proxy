# 2026-10-22 上游更新适配

## 来源与运行边界

- CLIProxyAPI：`355fd10e76b82e03ba6625acc583b900286e4bf2` → `0db19025571684b198a2ef508f99b41c717337e8`。
- EasyCLIProxyAPI：`01e3bedaea23595aaa0c97576b946a1060dbb397` → `6d37229dc95a11dbac5b166fdf74a53ab4487637`。
- 两个参考仓库只进行了 fast-forward pull，源码修改发生在本 Java 项目。
- 未覆盖、升级或重启 `D:\dev_program\LLMPoroxyUI-v1.0.0-Windows-amd64` 中已安装的 App。

## Java 内核

- 管理 API 的 60 秒请求预算同时应用于读取和写入，避免 OkHttp 默认 10 秒读超时提前终止额度查询。网络超时返回 504，连接、DNS、TLS 错误返回 502，提供不包含凭据的错误说明。
- Codex 有 refresh token 时采用 96 小时主动刷新提前量；只有 access token 的导入凭据保持普通到期窗口。刷新成功后才保存凭据。
- OpenAI/Responses/Claude 转换保留显式 `parallel_tool_calls=false` 和指定工具选择。
- 解析 SGLang 的平铺 `reasoning_tokens`，Responses usage 保留嵌套推理 token 数。
- Claude 截断、拒绝和上下文窗口耗尽在普通及流式转换中保留相应结束状态。
- Gemini 工具调用的 `thoughtSignature` 与 OpenAI `extra_content.google.thought_signature` 支持请求、响应和流式往返。
- Gemini schema 清理去掉不支持的元数据；展开本地 JSON Pointer 引用，保护默认值等字面量，限制展开深度及节点数量，避免递归引用无限展开。
- 凭据自身的非空 excluded-models 覆盖全局规则；空列表继承全局，忽略非法元素。凭据列表提供插件额度能力信息。

本项目原有的空文本块过滤、流式截断检测、独立 Claude effort 读取和模型定义查询继续保留。

## React / Tauri

对照更新页面、公共控件、主题及原生集成，包含主题皮肤和系统外观同步、窗口材质及活动状态、插件导航与原生 WebView、凭据和额度交互、代理配置、Codex 模型配置及远程压缩配置、会话工具、版本管理和恢复相关组件。

保留 Java 特有的 POST 配置操作封装、RespBodyVo 解包、JAR 启动与升级路径、多协议供应商、CX/DSC、工具级别名、额外原生额度供应商、请求详情和 originator/session/thread 字段。

补齐迁移所需的 Tauri 2.12 前后端依赖及读取软件版本权限。将本地扩展中的弹窗、通知、选择控件和主题背景接入公共组件；保留本项目品牌。修复插件浏览器预览缺少状态回调及占位样式的问题。

## 不等同于 Go 全功能重写

本次没有在 Java 中新增上游 Go Home 集群重平衡、mDNS、SIWC 绑定服务、Go 文件监听器的 epoch 注册机制，亦未复制上游赞助内容或专属 CI 自动合并流程。这些内容依赖上游专有运行结构。

Go 原生 executor 的完整签名缓存/重放、Claude 搜索缓存、guardian/alpha-search、Vertex partner executor、Kimi/Meta remote-compaction 等不能由前端配置项或通用协议转换等同实现；本次未宣称这些能力已在 Java 重写。插件支持仍取决于实际安装插件的能力和版本，未自动升级任何已安装插件。

## 验证

- Java：212 项单元测试通过，production JAR 构建成功。
- Rust：952 项通过，11 项忽略；在测试中隔离实际 CODEX_HOME，避免读取用户会话目录。
- 前端：932 项通过，32 项跳过；跳过项为需要单独指定上游 Go 测试内核的集成用例。TypeScript 检查通过。
- 协议矩阵：32 组本地真实 HTTP 转换案例通过，另覆盖模型分组、工具别名、会话亲和、凭据故障切换与重试限制。这里的上游是本地测试服务器。
- dsb：对本项目 Vite 浏览器预览检查了额度状态、主题、插件导航。此部分使用模拟数据。
- 原生桌面：在 `target/upstream-sync/native-review` 独立运行本项目 Tauri 测试构建及 Java 内核，端口 18329。确认页面为 `http://tauri.localhost/` 且未启用 browser mock。使用一个现有 Codex access token 实际查询配额，页面首次显示 Pro、周额度剩余 86%；最终 release 产物复验返回 82%，无额度错误。最终版本的原生软件版本权限、多协议接入弹窗打开与取消也通过检查。没有复制 refresh token、触发账号重新授权或轮换已安装账号凭据。

真实查询成功证明该次完整链路可用，并不保证代理或上游网络永不超时。测试进程已停止，临时账号副本已清除，发行包不含账号或个人配置。

## 手动更新

关闭已安装 App 后，先备份其目录。用交付的桌面可执行文件替换 `LLMPoroxyUI.exe`，用 JAR 替换 `cpa-core/java-llm-proxy.jar`。保留原有 `config.toml`、OAuth 凭据、数据库、插件及其他个人配置。此次没有修改用户机器上的已安装版本，也没有发布安装包；源码按用户后续要求提交并推送。

交付文件：`target/release/LLMPoroxyUI-manual-update-2026-10-22-Windows-amd64.zip`（38.2 MiB）。已逐项验证 ZIP 内容、完整性以及 EXE/JAR 与测试产物的 SHA-256 一致性；同目录附有 `.zip.sha256`。
