# ZCode channel selection

ZCode login creates a `start-plan` credential by default. It does not automatically switch to a paid subscription, retrieve cloud API keys, or spend subscription quota after a failed request.

To select a channel explicitly, send an authenticated management request:

```http
POST /v8/management/zcode/channel
Authorization: Bearer <management-token>
Content-Type: application/json

{"name":"zcode-account.json","channel":"coding-plan"}
```

Selecting `coding-plan` reads an existing cloud key named `zcode-api-key` through the authenticated customer/project, key-list, and key-copy endpoints. It does not create a key or purchase a subscription. If no eligible project or key exists, the request fails and the credential keeps its previous channel. Create or configure the subscription key in the provider console before trying again.

The response uses `RespBodyVo`; its data contains only `name`, `channel`, and `codingPlanKeyAvailable`. Tokens and API keys are never returned by this endpoint. Selecting `start-plan` uses the same request with `"channel":"start-plan"` and makes no cloud key request. The service updates the credential base URL and removes stale model overrides when changing channels.

An account field editor can inspect `zcode_channel`. Use the dedicated operation to change it: directly editing only that field cannot obtain an absent coding-plan key and may retain a base URL from the other channel.

## Model visibility

Model descriptions and capabilities come from the remote catalog. A global catalog does not establish channel entitlement. The supplied source contains no actual `providerRules` response schema, so routing currently applies its verified **2026-10-03 channel mapping** as a conservative filter:

- `start-plan`: `glm-5.3-flash`, `glm-5.2`, `glm-5-turbo`.
- `coding-plan`: `glm-5.3`, `glm-5.3-flash`.

A model must also appear in the current remote catalog. No missing or removed model is added by this mapping. Results identify this restriction with `channel_support_source: source-snapshot-2026-10-03`. A newly released model stays hidden until its channel support is verified and the mapping updated. Availability in a catalog does not guarantee sufficient account quota.

## Login and verification

The platform JWT has no supported refresh endpoint. When it expires, `start-plan` needs a new interactive login. A coding-plan API key is a separate credential and does not refresh that JWT.

Start-plan can require an interactive captcha or reject a request under the provider's account-access policy. The proxy preserves the caller's system instructions and does not impersonate the official agent to avoid those checks. Code `3007` reports `ZCODE_CAPTCHA_REQUIRED`; code `3012` reports `ZCODE_ACCESS_RESTRICTED`. Neither condition should trigger automatic HTTP retries or credential switching.

When the provider requests verification, a user must complete it and supply the newly obtained, single-use proof with both headers on the intended inference request:

```http
x-aliyun-captcha-verify-param: <user-completed-proof>
x-aliyun-captcha-verify-region: <matching-provider-region>
```

`ZCodeService.captchaConfig` reads the current region, prefix, and scene ID from the provider configuration for a future interactive UI. The implementation does not automate captcha solving. A proof cannot override an account-access restriction; use the official client or contact the provider when `3012` persists.
