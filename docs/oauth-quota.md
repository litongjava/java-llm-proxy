# OAuth 额度查询

Quota Lookup 现在列出 OAuth 页面所有已启用的内置渠道账号；Loomy 短信账号也可查询。OAuth 登录与额度查询是独立能力，授权成功不保证上游返回额度。

原有 Claude、Codex、Kimi、xAI、Antigravity、Devin 查询保持原实现。新增查询参考 `dsh-our-free-model/vendor/channel-pack/src` 的只读接口：

| 渠道 | 接口 | 展示口径 |
| --- | --- | --- |
| CodeBuddy / WorkBuddy 国际版、国内版 | `POST /v2/billing/meter/get-user-resource` | 有效包的 `CycleCapacityRemainPrecise`，回退 `CycleCapacityRemain`；排除 Status=3、已过 ExpiredTime 或 DeductionEndTime 的包 |
| Cline | `GET /api/v1/users/me/plan/usage-limits` | 订阅窗口剩余百分比、重置时间；不推测余额货币换算 |
| MiniMax | `GET /minimax-cloud/api/v1/credit/details` | `details[].remaining_amount` 汇总 |
| LobsterAI | `GET /api/user/profile-summary` | `totalCreditsRemaining` 及 `creditItems`，包含活动积分 |
| Raccoon | `GET /api/web/points/v1/balance` | `available_points` 及奖励、每日、会员、充值明细 |
| TRAE | `POST /trae/api/v2/pay/ide_user_ent_usage` | 资源包上限减已用量；按账号派生客户端身份 |
| Qoder / Qoder CN | `GET /sash/api/v2/me/usage` | 套餐、资源包、专用包；企业版无数字时显示不可用 |
| ZCode | `GET /api/v1/zcode-plan/billing/balance` | 已领取额度桶，保留 Token 等单位；空桶不代表没有可领取活动 |
| CodeArts | `GET /snap-manager/v1/statistics/plugin` | 签名查询积分指标，总额不与分项重复相加 |
| Loomy | `GET /points/records?pageNo=1&pageSize=1&recordType=all` | 永久积分、每日余额与可用总额 |
| Muse / Meta | 无已接入的可靠接口 | 明确显示暂不支持，账号不再被隐藏 |

新增渠道由管理鉴权保护的 `POST /v8/management/credentials/quota` 查询。请求只传 `authIndex`，服务端解析账号、刷新凭据并生成签名/请求头，前端不读取 token、AK/SK。禁用账号不能调用。该接口不执行签到、领取、扣费或额度重置。

WorkBuddy 国内版使用本项目独立的 `www.workbuddy.cn` 配置与账号身份；原版积分实现的实测覆盖 CodeBuddy 和 WorkBuddy 国际版，国内版的该端点仍需真实账号验证。其他新增渠道也完成了模拟响应及请求构造验证，不宣称已经逐渠道真实账号联调。上游拒绝、响应缺字段会显示查询失败，不显示为零。

前后端都需更新后生效。已有授权文件不需要重新添加。
