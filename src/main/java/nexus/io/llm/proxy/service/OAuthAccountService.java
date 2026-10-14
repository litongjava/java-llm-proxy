package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;

public class OAuthAccountService {
  public void enrich(Kv credential) throws Exception {
    if (!"antigravity".equals(credential.getStr("type"))) { return; }
    OAuthService oauth = Aop.get(OAuthService.class);
    String token = credential.getStr("access_token");
    Kv user = oauth.getJson(EnvUtils.get("proxy.oauth.antigravity.profile-url", "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"), token);
    credential.set("email", user.get("email"));
    String control = EnvUtils.get("proxy.oauth.antigravity.control-url", "https://cloudcode-pa.googleapis.com/v1internal");
    Kv response = oauth.post(control + ":loadCodeAssist", Kv.create().set("metadata", Kv.create().set("ideType", "ANTIGRAVITY")), true, token);
    String project = project(response);
    String tier = "free-tier";
    for (Object value : ProxyJson.list(response.get("allowedTiers"))) {
      Kv candidate = ProxyJson.object(value);
      if (Boolean.TRUE.equals(candidate.getBoolean("isDefault"))) { tier = candidate.getStr("id"); }
    }
    for (int attempt = 0; project == null && attempt < 5; attempt++) {
      Kv onboarding = oauth.post(control + ":onboardUser", Kv.create().set("tier_id", tier)
          .set("metadata", Kv.create().set("ide_type", "ANTIGRAVITY").set("ide_name", "antigravity")), true, token);
      if (Boolean.TRUE.equals(onboarding.getBoolean("done"))) { project = project(ProxyJson.object(onboarding.get("response"))); }
      if (project == null) { Thread.sleep(1000); }
    }
    BusinessException.require(project != null, 502, "PROJECT_NOT_FOUND", "Google authorization did not provide an active project");
    credential.set("project_id", project);
  }
  private String project(Kv input) {
    for (String key : new String[]{"cloudaicompanionProject", "projectId", "project"}) {
      Object value = input.get(key);
      if (value instanceof String text && !text.isBlank()) { return text; }
      String id = ProxyJson.object(value).getStr("id");
      if (id != null && !id.isBlank()) { return id; }
    }
    return null;
  }
}
