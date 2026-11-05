# Codex 原生接口

客户端统一使用 `http://127.0.0.1:8318/tools/codex/v1` 作为 Base URL。代理管理 OAuth 登录、刷新、模型选择和上游连接，客户端只持有代理访问密钥。

## 对话与压缩

- `POST /tools/codex/v1/responses`：Responses 请求发送到 Responses 上游，不经过 Chat Completions 或 Anthropic 的通用协议转换。仍应用配置的路由、Payload 规则及 OAuth 参数兼容处理。
- `POST /tools/codex/v1/responses/compact`：返回 `response.compaction` 和完整 `output` 数组。

Codex OAuth 使用 `/responses` 中的原生 `compaction_trigger` 完成压缩，代理把结果适配为独立压缩接口的返回格式。此流程不使用普通提示词摘要。其他支持独立压缩接口的 Responses 提供商继续使用各自的 `/responses/compact`。

调用方必须完整保存并重放返回的 `output`，包括加密压缩项和其他原生项目。代理在后续 Astra 请求中完整保留含压缩项的输入，不对其 reasoning 项做历史清理。响应保留上游 usage；压缩不是零 token 操作。请求输入需要符合所选模型的窗口限制。

## 原生搜索与普通 JSON 响应

Responses 请求可携带 `tools: [{"type":"web_search"}]` 和 `include: ["web_search_call.action.sources"]`。代理将字符串形式的 `input` 规范化为用户消息数组，便于客户端接入 Codex OAuth。

客户端选择 `stream: false` 时，代理将上游事件流聚合为普通 JSON。如果完成事件的 `output` 为空，使用已完成的 `response.output_item.done` 项，按 `output_index` 排序、去重，完整保留搜索来源、文本引用、函数调用及其他原生结果。完成事件已包含输出时优先保留该输出，usage 保持上游值。流式客户端继续接收原始事件；失败或没有完成事件的流不会被转换成成功响应。

## 图片

- `POST /tools/codex/v1/images/generations`
- `POST /tools/codex/v1/images/edits`

Codex 图片模型为 `gpt-image-2`，独立于对话模型。代理使用已配置的 Codex OAuth 凭据和账号请求头转发图片 JSON，保留参考图片顺序及扩展参数，不套用对话输入转换。原有 API Key 提供商的 multipart 图片编辑继续可用。

接口注册、模型目录可见和账号具备图片访问权限是不同环节。实际可用性由上游响应决定；失败状态保留，不能据此宣称已生成图片。

## 模型所属方与来源

`GET /tools/codex/v1/models` 的 `owned_by` 表示模型所属方，优先使用模型目录中的真实元数据。Codex GPT 模型为 `openai`，Claude 为 `anthropic`，Gemini 为 `google`。仅兼容 OpenAI 协议的自定义服务不会因此被标成 OpenAI。

`sources` 补充提供商类型、实际模型 ID 和所属方，例如 `{"provider":"codex","model":"gpt-6-astra","owned_by":"openai"}`。同一名称有多个来源时聚合记录，不公开 OAuth 凭据文件名、账号邮箱或访问令牌。无法确定所属方时返回 `unknown`；不同所属方共享同一别名时返回 `multiple`，并在 `owners` 中保留各方。

## 验证

`scripts/codex_native_test.py` 启动隔离代理和本地 OAuth 上游，验证实际 HTTP 路由、完整压缩窗口的继续请求、模型归属及图片 JSON。搜索覆盖 `/v1/responses` 与 `/tools/codex/v1/responses`，分别组合字符串或数组输入、流式或普通响应，共八种场景；检查搜索参数、来源、文本引用、用量，以及完成项乱序和重复时的聚合结果。`scripts/native_endpoint_test.py` 验证原有 multipart 和其他原生端点。它们使用本地测试数据，不代表真实上游权限验证。
