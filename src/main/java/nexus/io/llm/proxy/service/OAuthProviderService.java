package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;

public class OAuthProviderService {
  public OAuthProviderSpec get(String provider) throws Exception {
    OAuthProviderSpec spec = switch (provider) {
    case "codex" -> new OAuthProviderSpec(provider, "app_EMoamEEZ73f0CkXaXp7hrann", null,
        "https://auth.openai.com/oauth/authorize", "https://auth.openai.com/oauth/token", null,
        "openid email profile offline_access", "http://localhost:1455/auth/callback", false);
    case "claude" -> new OAuthProviderSpec(provider, "9d1c250a-e61b-44d9-88ed-5944d1962f5e", null,
        "https://claude.ai/oauth/authorize", "https://platform.claude.com/v1/oauth/token", null,
        "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload",
        "http://localhost:54545/callback", true);
    case "antigravity" -> new OAuthProviderSpec(provider, null, null, "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token", null,
        "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email "
            + "https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog "
            + "https://www.googleapis.com/auth/experimentsandconfigs",
        "http://localhost:51121/oauth-callback", false);
    case "kimi", "kimi-ai" -> {
      String host = "kimi-ai".equals(provider) ? "https://auth.kimi.ai" : "https://auth.kimi.com";
      yield new OAuthProviderSpec(provider, "17e5f671-d194-4dfb-9706-5516cb48c098", null, null,
          host + "/api/oauth/token", host + "/api/oauth/device_authorization", "", null, false);
    }
    case "meta" ->
      new OAuthProviderSpec(provider, "1031625952748946", null, null, "https://auth.meta.com/oidc/device/token/",
          "https://auth.meta.com/oidc/device/authorization/", "", null, false);
    case "xai" -> new OAuthProviderSpec(provider, "b1a00492-073a-47ea-816f-4c329264a828", null, null,
        "https://auth.x.ai/oauth/token", "https://auth.x.ai/oauth/device/code",
        "openid profile email offline_access grok-cli:access api:access", null, false);
    case "devin" -> new OAuthProviderSpec(provider, "", null, "https://app.devin.ai/auth/cli/continue",
        "https://api.devin.ai/auth/cli/token", null, "", "http://localhost:18320/callback", true);
    default -> null;
    };
    BusinessException.require(spec != null, 404, "NOT_FOUND", "provider_not_found");
    String prefix = "proxy.oauth." + provider + ".";
    spec.clientId = EnvUtils.get(prefix + "client-id", spec.clientId);
    spec.clientSecret = EnvUtils.get(prefix + "client-secret", spec.clientSecret);
    if ("antigravity".equals(provider)) {
      BusinessException.require(spec.clientId != null && !spec.clientId.isBlank()
          && spec.clientSecret != null && !spec.clientSecret.isBlank(), 500, "OAUTH_NOT_CONFIGURED",
          "Configure proxy.oauth.antigravity.client-id and proxy.oauth.antigravity.client-secret in secrets.txt");
    }
    spec.authorizeUrl = EnvUtils.get(prefix + "authorize-url", spec.authorizeUrl);
    spec.tokenUrl = EnvUtils.get(prefix + "token-url", spec.tokenUrl);
    spec.deviceUrl = EnvUtils.get(prefix + "device-url", spec.deviceUrl);
    spec.redirectUri = EnvUtils.get(prefix + "redirect-uri", spec.redirectUri);
    spec.scope = EnvUtils.get(prefix + "scope", spec.scope);
    if ("xai".equals(provider) && EnvUtils.get(prefix + "token-url") == null) {
      Kv discovery = Aop.get(OAuthService.class)
          .getJson(EnvUtils.get(prefix + "discovery-url", "https://auth.x.ai/.well-known/openid-configuration"), null);
      spec.deviceUrl = discovery.getStr("device_authorization_endpoint");
      spec.tokenUrl = discovery.getStr("token_endpoint");
    }
    return spec;
  }
}
