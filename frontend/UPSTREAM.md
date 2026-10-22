# 前端来源

本目录直接复用 [EasyCLIProxyAPI](https://github.com/router-for-me/EasyCLIProxyAPI) 的 React 页面、样式、国际化、Tauri 原生模块和资源。

初始参考提交：`654731c415ceb8cd0e916e4f862476e81e5d3ea0`。2026-10-22 对照更新到 `6d37229dc95a11dbac5b166fdf74a53ab4487637`，保留本项目的 Java 内核、管理 API 适配、多协议接入、工具级别名和请求详情。保留原 [MIT 许可证](LICENSE)。`assets` 和上游文档中的截图来自参考项目，不代表本项目的验收结果。此次适配和验证范围见 [更新记录](../docs/upstream-sync-2026-10-22.md)。

本项目适配内容：

- Tauri 启动和打包 Java 内核，使用原桌面配置管理流程。
- 原管理 API 调用通过原生层适配 `RespBodyVo`；配置写操作转换为带操作类型的 POST。
- 原 Usage 本地数据库继续负责筛选、统计、价格和分析；Java 提供可确认的持久化采集队列。
- 原 Usage 表格增加请求详情弹窗，支持原始正文、协议识别、提示词与响应格式化及复制。
- 固定兼容的 Tauri 前端依赖，并保留 npm 与 Cargo 锁文件。

桌面自更新与内核更新指向本项目的 GitHub `litongjava/java-llm-proxy` 和 GitCode `ppnt/java-llm-proxy`。内核产物使用 Java JAR，下载后检查摘要与应用入口。打包脚本见 `scripts/package_release.py`，远端发行需要同时提供桌面清单、便携包与内核包。

开发与验收说明见项目根目录的 [readme.md](../readme.md)。
