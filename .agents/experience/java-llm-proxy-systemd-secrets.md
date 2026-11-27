---
name: java-llm-proxy-systemd-secrets
description: java-llm-proxy 作为 systemd 服务时，带点和连字符的配置键（如 proxy.management-token）不能通过环境变量注入，应放进 WorkingDirectory 下的 secrets.txt。
---

# java-llm-proxy systemd 部署的密钥配置方式

## Symptom

以 systemd 服务启动 java-llm-proxy 后，Web 控制台输入管理密钥始终 401；若尝试用
`Environment=`/`EnvironmentFile=` 设置 `proxy.management-token` 或 `proxy.api-key`，
键名中的点和连字符会被 systemd/shell 拒绝或读不到。

## Cause

`nexus.io.tio.utils.environment.EnvUtils` 的查找顺序是 appMap → 命令行 `--key=value` →
System.getProperty → System.getenv(原样键) → System.getenv(点转下划线并大写)。
`proxy.management-token` 转换后是 `PROXY_MANAGEMENT-TOKEN`，仍带连字符，不是合法环境变量名；
命令行参数又会把密钥暴露在 `ps` 中。

## Fix

把带点键写入进程工作目录下的 `secrets.txt`（Java properties 格式）。`EnvUtils.configuration()`
会依次加载 `~/.env`、`~/secrets.txt`、classpath `app.properties`、工作目录 `app.properties`，
最后以工作目录的 `.env`、`secrets.txt`、`my.txt` 覆盖。systemd unit 设置
`WorkingDirectory=/opt/java-llm-proxy`，并在该目录放 0600、属主为服务用户的 `secrets.txt`：

```
proxy.management-token=<token>
proxy.api-key=<key>
```

非密钥配置（监听地址、端口、数据目录）直接作为 `ExecStart` 参数传入即可。

## Verification

`systemctl is-enabled` 为 enabled、`is-active` 为 active，`ss -ltn` 显示 `*:8318`；
`GET /api/settings` 无 token 返回 401、带 `Authorization: Bearer <管理密钥>` 返回 200；
`/api/webui/usage/overview` 返回 200；`/v1/models` 带客户端密钥 200、错误密钥 401。
