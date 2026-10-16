# DSC 接入

DSC 源码位于 `D:\code\project\project-harven\dsc`，基于 CX 的独立 Codex CLI 启动器。它复用已安装的 Codex CLI，专门保留独立的 DeepSeek 模型设置；不是 DeepSeek Harness（`dsh`）。

- 命令：`%USERPROFILE%\.dsc\bin\dsc.cmd`
- 配置：`%USERPROFILE%\.dsc\config.toml`
- 登录凭据：`%USERPROFILE%\.dsc\auth.json`
- 自定义目录：`DSC_HOME`，CLI 与桌面应用应设置一致。
- 工具专属代理地址：`http://127.0.0.1:8317/tools/dsc/v1`（端口按实际配置调整）。

DSC 与 Codex 共用会话、技能、插件、指令和普通设置，但模型、推理设置、上下文设置、命名 profile 中的模型配置、模型目录、连接与凭据独立。正常 Codex/CX 使用不受 DSC 模型选择影响。共享会话中的上下文会发送给当前选择的模型提供商。

## 手动配置

先在本项目配置 DeepSeek 上游及可用模型，然后打开新终端：

```powershell
dsc --dsc-config --model deepseek-chat --base-url http://127.0.0.1:8317/tools/dsc/v1
$env:DSC_API_KEY = '你的本地代理 API Key'
dsc
```

示例模型 `deepseek-chat` 需要替换为代理实际公开的模型 ID。`DSC_API_KEY` 是本项目的访问密钥，不是要求把 DeepSeek 上游密钥复制到 DSC。该环境变量仅作用于当前终端及其子进程；`--api-key-env NAME` 可指定其他变量名。

命令备份并更新 DSC 自己的配置，清除会覆盖手动模型的当前 profile 和模型目录选择；不会输出密钥或改写 Codex/CX 凭据。也可直接编辑 DSC 的 `config.toml`。

DSC 使用 Codex 的 Responses 协议，应连接本项目的协议适配接口或其他 Responses 兼容服务。不能直接把 Chat Completions-only 地址当成 Responses 地址。

## 桌面界面

更新桌面应用及 Java 后端后，在“智能体配置 → DSC”选择模型并应用。支持 CLI 检测、启动、模型目录生成、配置检查、默认模板、备份恢复及关闭接入。模型别名使用独立的 `dsc` 作用域。手动配置与 GUI 配置是两种入口，应用 GUI 配置会切换至 GUI 管理的提供商。

验证覆盖：启动器临时目录测试、手动模型和凭据隔离、桌面配置应用/更新/关闭/模板测试、前端构建与 Java 编译。本机已安装启动器并验证 `dsc --version`；没有发送真实 DeepSeek 推理请求。
