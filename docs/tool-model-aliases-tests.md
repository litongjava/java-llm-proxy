# 工具别名与用量统计测试记录

验证日期：2026-10-14。修改前基线：`f0d0f1d`。测试针对本地代码和测试服务，未向真实模型服务发送请求，也未修改用户已有客户端配置。

| 验证项 | 结果 |
| --- | --- |
| Java 单元测试 | 20 通过，0 失败 |
| Java 安装与 production 打包 | 通过，跳过 GPG 验证 |
| 打包后 HTTP 集成测试 | 通过：认证、工具模型列表、独立路由、JSON/SSE 转换、真实模型与缓存用量 |
| TypeScript 检查 | 通过 |
| Vite production 构建 | 通过 |
| Rust 修改前完整测试 | 849 通过，27 失败，10 忽略 |
| Rust 修改后完整测试 | 851 通过，27 失败，10 忽略 |
| 可见浏览器验证 | 工具别名隔离与 DeepSeek 请求详情断言通过 |

Rust 失败用例名称集合与基线完全相同，没有新增失败。新增的两项测试覆盖工具配置投影、凭据不复制、独立保存、服务写入失败和本地提交失败后的恢复。

已有失败集中在旧别名保存测试服务的请求方法预期、两项 Claude Desktop 模板预期、一项配置清理预期和默认端口预期。因此不能将完整 Rust 测试描述为全部通过。

Java clean 阶段遇到被已有进程占用的日志文件，安装构建使用 `-Dmaven.clean.failOnError=false`；之后单元测试、production 打包和打包产物的集成测试均成功。最新 Java 内核已复制到桌面资源目录，尚未生成桌面安装包。

可复现命令（先按本机环境设置 Java 21）：

```bash
mvn test -Dgpg.skip
mvn clean install -DskipTests -Dgpg.skip -Dmaven.clean.failOnError=false
mvn package -DskipTests -Dgpg.skip -Pproduction
python scripts/test-tool-aliases.py --java java
node frontend/scripts/prepare-core.mjs
```

前端目录执行：

```bash
npm run check
npm run build
```

Rust 目录执行：

```bash
cargo test --no-default-features
```

浏览器使用 deepseek-browser-use 的有界面 Chrome，先在 Vite 测试页中检查页面文本，再保存视口截图。只读断言返回：

```json
{
  "passed": true,
  "model": "deepseek-flash",
  "provider": "deepseek-1",
  "cache": "96.15%60K0",
  "fixture": true
}
```

其中缓存单元格依次包含命中率、缓存读取与缓存创建值。截图及配置说明见 [工具独立模型别名与请求统计](tool-model-aliases.md)。
