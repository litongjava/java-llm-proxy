package nexus.io.llm.proxy.service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;

/** TRAE local-token authorization and SOLO request conversion. */
public class TraeService {
  public String apiBaseUrl() { return setting("api-base-url", "https://trae-api-cn.mchost.guru"); }
  private String oauthBase() { return setting("oauth-base-url", "https://api.trae.com.cn"); }
  private String setting(String key, String fallback) { return EnvUtils.get("proxy.oauth.trae." + key, fallback); }
  private String version() { return setting("client-version", "0.1.52"); }
  private String clientId() { return setting("client-id", "en1oxy7wnw8j9n"); }

  public String initialize(OAuthSession session) {
    String machine = UUID.randomUUID().toString().replace("-", "");
    String device = UUID.randomUUID().toString().replace("-", "");
    String trace = device.substring(16);
    session.pluginMetadata = Kv.create().set("machine_id", machine).set("device_id", device).set("login_trace_id", trace);
    Kv query = Kv.create().set("login_version", "1").set("auth_from", "solo").set("login_channel", "native_ide")
        .set("plugin_version", setting("plugin-version", "2.3.62834")).set("auth_type", "local").set("client_id", clientId())
        .set("redirect", "0").set("login_trace_id", trace).set("auth_callback_url", session.spec.redirectUri)
        .set("machine_id", machine).set("device_id", device).set("x_device_id", device).set("x_machine_id", machine)
        .set("x_device_brand", "PC").set("x_device_type", "PC").set("x_os_version", "1.0")
        .set("x_app_version", version()).set("x_app_type", "stable");
    List<String> pairs = new ArrayList<>();
    query.forEach((key, value) -> pairs.add(encode(key.toString()) + "=" + encode(value.toString())));
    return setting("console-base-url", "https://www.trae.cn") + "/authorization?" + String.join("&", pairs);
  }

  public Kv exchange(Kv query, OAuthSession session) throws Exception {
    BusinessException.require(session.pluginMetadata != null && session.pluginMetadata.getStr("login_trace_id") != null
        && session.pluginMetadata.getStr("login_trace_id").equals(query.getStr("login_trace_id")), 400, "INVALID_STATE", "TRAE callback does not match the pending login");
    Kv jwt = queryObject(query.getStr("userJwt"));
    Kv user = queryObject(query.getStr("userInfo"));
    String refresh = first(query.getStr("refreshToken"), jwt.getStr("RefreshToken"));
    String access = first(jwt.getStr("Token"));
    BusinessException.require(refresh != null || access != null, 400, "TRAE_CALLBACK_UNSUPPORTED",
        "TRAE callback requires refreshToken or userJwt.Token; authorization-code callbacks are not supported by the local login flow");
    Kv credential = Kv.create().set(session.pluginMetadata).set("type", "trae");
    credential.remove("login_trace_id");
    if (refresh != null) { credential.set("refresh_token", refresh); credential = refresh(credential); }
    else { credential = normalizeToken(Kv.create().set("Result", Kv.create().set("Token", access)), credential); }
    String uid = string(user.get("UserID"));
    String nickname = user.getStr("ScreenName");
    String enterprise = string(user.get("TenantID"));
    try {
      Kv response = Aop.get(OAuthService.class).post(oauthBase() + "/cloudide/api/v3/trae/GetUserInfo",
          Kv.create().set("ReqSource", "IDE").set("IDEVersion", version()), true, null,
          anonymousHeaders().set("X-Cloudide-Token", credential.get("access_token")));
      OAuthFailureUtils.requireSuccess(response, "TRAE user profile");
      Kv profile = result(response);
      uid = first(string(profile.get("UserID")), profile.getStr("userId"), uid);
      nickname = first(profile.getStr("ScreenName"), nickname);
      enterprise = first(string(profile.get("EnterpriseID")), enterprise);
      if (profile.get("NonPlainTextMobile") != null) { credential.set("phone", profile.get("NonPlainTextMobile")); }
      if (profile.get("NonPlainTextEmail") != null) { credential.set("email", profile.get("NonPlainTextEmail")); }
    } catch (Exception ignored) {
      // Callback identity remains usable when optional profile discovery fails.
    }
    BusinessException.require(uid != null && !uid.isBlank(), 502, "TRAE_IDENTITY_MISSING", "TRAE did not return an account identity");
    return credential.set("uid", uid).set("account_id", uid).set("nickname", nickname).set("label", nickname).set("enterprise_id", enterprise);
  }

  public Kv refresh(Kv credential) throws Exception {
    String refresh = credential.getStr("refresh_token");
    BusinessException.require(refresh != null && !refresh.isBlank(), 400, "NO_REFRESH_TOKEN", "TRAE credential has no refresh token");
    Kv body = Kv.create().set("ClientID", clientId()).set("RefreshToken", refresh).set("ClientSecret", "-").set("UserID", "");
    Kv response = Aop.get(OAuthService.class).post(oauthBase() + "/cloudide/api/v3/trae/oauth/ExchangeToken", body, true, null, anonymousHeaders());
    return normalizeToken(response, Kv.create().set(credential));
  }

  public Kv normalizeToken(Kv response, Kv credential) {
    OAuthFailureUtils.requireSuccess(response, "TRAE token exchange");
    Kv token = result(response);
    String access = first(token.getStr("Token"), token.getStr("token"), token.getStr("accessToken"));
    BusinessException.require(access != null, 502, "TRAE_TOKEN_FAILED", "TRAE did not return an access token");
    String refresh = first(token.getStr("RefreshToken"), token.getStr("refreshToken"));
    credential.set("type", "trae").set("access_token", access).set("last_refresh", Instant.now().toString());
    if (refresh != null) { credential.set("refresh_token", refresh); }
    long expiry = ProxyJson.number(token, "TokenExpireAt", ProxyJson.number(token, "tokenExpireAt", 0));
    if (expiry > 100000000000L) { expiry /= 1000; }
    long duration = ProxyJson.number(token, "TokenExpireDuration", ProxyJson.number(token, "tokenExpireDuration", 0));
    if (expiry <= 0 && duration > 0) { expiry = Instant.now().getEpochSecond() + duration; }
    if (expiry <= 0) {
      try {
        Kv claims = ProxyJson.parse(new String(Base64.getUrlDecoder().decode(access.split("\\.")[1]), StandardCharsets.UTF_8));
        expiry = ProxyJson.number(claims, "exp", 0);
      } catch (RuntimeException ignored) { /* Optional JWT expiration metadata. */ }
    }
    credential.remove("expired");
    credential.remove("expires_at");
    if (expiry > 0) { credential.set("expired", Instant.ofEpochSecond(expiry).toString()); }
    return credential;
  }

  private Kv anonymousHeaders() { return Kv.create().set("Accept", "application/json").set("User-Agent", "Trae/" + version()); }

  public Kv headers(Kv credential, boolean stream) {
    String token = credential.getStr("access_token");
    return anonymousHeaders().set("Accept", stream ? "text/event-stream" : "application/json")
        .set("Authorization", "Cloud-IDE-JWT " + token).set("X-Cloudide-Token", token).set("X-Ide-Token", token)
        .set("X-Uid", credential.get("uid")).set("X-App-Id", setting("app-id", "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8"))
        .set("X-App-Version", "default").set("X-Ide-Version", version()).set("X-Ide-Version-Code", setting("version-code", "20260811"))
        .set("X-App-Version-Code", setting("version-code", "20260811")).set("X-Ide-Version-Type", "stable")
        .set("X-Device-Type", "macos").set("X-OS-Version", setting("os-version", "macOS 15.7.4"))
        .set("X-Device-Brand", "Apple").set("Request-Traffic-Type", "prod")
        .set("X-Machine-Id", credential.get("machine_id")).set("X-Device-Id", credential.get("device_id"));
  }

  public Kv payload(Kv input) {
    return payload(input, Kv.create());
  }

  public Kv payload(Kv input, Kv credential) {
    Kv body = ProxyJson.parse(JsonUtils.toJson(input));
    String model = ProxyJson.text(body, "model", "glm-5.2").split("__", 2)[0];
    String channel = setting("channel", "solo_work_lite");
    for (Object value : ProxyJson.list(credential.get("models"))) {
      Kv definition = ProxyJson.object(value);
      if (model.equals(definition.getStr("id")) && definition.getStr("channel") != null) { channel = definition.getStr("channel"); break; }
    }
    body.set("stream", true).set("function", channel).set("model", model).set("config_name", model);
    List<Kv> messages = new ArrayList<>();
    for (Object value : ProxyJson.list(body.get("messages"))) {
      Kv message = ProxyJson.object(value);
      if (message.get("content") instanceof String content) { message.set("content", List.of(Kv.create().set("type", "text").set("text", content))); }
      if ("assistant".equals(message.getStr("role")) && message.containsKey("tool_calls")) {
        List<Kv> calls = new ArrayList<>();
        for (Object item : ProxyJson.list(message.get("tool_calls"))) {
          Kv call = ProxyJson.object(item);
          if (call.get("function") != null) { call.set("function_call", call.remove("function")); }
          String name = ProxyJson.object(call.get("function_call")).getStr("name");
          if (name != null && !name.isBlank()) { calls.add(call); }
        }
        if (calls.isEmpty()) { message.remove("tool_calls"); } else { message.set("tool_calls", calls); }
      }
      messages.add(message);
    }
    body.set("messages", messages);
    Object choice = body.get("tool_choice");
    String mode = choice instanceof String text ? text : ProxyJson.object(choice).getStr("type");
    if ("none".equalsIgnoreCase(mode)) { body.remove("tool_choice"); body.remove("tools"); body.remove("functions"); }
    else if ("function".equals(mode)) {
      Kv selected = ProxyJson.object(choice);
      body.set("tool_choice", ProxyJson.text(ProxyJson.object(selected.get("function")), "name", ProxyJson.text(selected, "name", "auto")));
    } else if ("auto".equals(mode) || "required".equals(mode)) { body.set("tool_choice", mode); }
    else if (!(choice instanceof String)) { body.remove("tool_choice"); }
    List<Kv> tools = new ArrayList<>();
    for (Object item : ProxyJson.list(body.get("tools"))) {
      Kv tool = ProxyJson.object(item);
      Kv function = ProxyJson.object(tool.get("function"));
      if (function.isEmpty()) { continue; }
      Object parameters = function.get("parameters");
      if (parameters != null && !(parameters instanceof String)) { function.set("parameters", JsonUtils.toJson(parameters)); }
      tool.set("function", function); tools.add(tool);
    }
    if (body.containsKey("tools")) { body.set("tools", tools); }
    return body;
  }

  public List<Kv> models(Kv credential) throws Exception {
    List<String> channels = List.of(setting("channels", "solo_agent,solo_work_lite,solo_agent_remote").split(","));
    Kv body = Kv.create().set("functions", List.of("ui_builder_v2", "solo_coder", "chat_v3", "solo_builder",
        "builder_v3", "builder", "chat", "inline_chat", "git_ai", "custom_agent_generation", "utils",
        "code_reviewer", "code_review_summary", "solo_agent", "solo_agent_remote", "solo_work_remote",
        "solo_agent_lite", "solo_work_lite", "solo_design_lite", "solo_design_remote", "multimodal", "system_diagnosis")).set("agent_type", "")
        .set("current_config_info", Kv.create().set("config_name", "").set("is_custom_model", false))
        .set("mode_type", 0).set("access_type", 0).set("ab_force_vids", "").set("ab_autotest_advanced_mode", 0).set("show_custom_model", true);
    Kv response = Aop.get(OAuthService.class).post(apiBaseUrl() + "/api/ide/v1/batch_get_detail_param", body, true, null, headers(credential, false));
    OAuthFailureUtils.requireSuccess(response, "TRAE model discovery");
    List<Kv> models = new ArrayList<>();
    List<?> groups = ProxyJson.list(response.get(response.containsKey("function_configs") ? "function_configs" : "FunctionConfigs"));
    for (String channel : channels) {
      for (Object groupValue : groups) {
        Kv group = ProxyJson.object(groupValue);
        if (!channel.equals(first(group.getStr("function"), group.getStr("Function")))) { continue; }
        for (Object value : ProxyJson.list(group.get(group.containsKey("config_info_list") ? "config_info_list" : "ConfigInfoList"))) {
          Kv model = ProxyJson.object(value);
          String id = first(model.getStr("config_name"), model.getStr("ConfigName"));
          String usage = first(model.getStr("usage"), model.getStr("Usage"));
          if (id == null || (usage != null && !"chat_completion".equals(usage))
              || Boolean.FALSE.equals(model.getBoolean("config_switch")) || Boolean.FALSE.equals(model.getBoolean("ConfigSwitch"))
              || Boolean.TRUE.equals(model.getBoolean("is_invisible_to_user")) || Boolean.TRUE.equals(model.getBoolean("IsInvisibleToUser"))) { continue; }
          if (models.stream().anyMatch(existing -> id.equals(existing.getStr("id")))) { continue; }
          models.add(Kv.create().set(model).set("id", id).set("channel", channel)
              .set("name", ProxyJson.text(ProxyJson.object(model.get("display_config")), "display_name", id)));
        }
      }
    }
    return models;
  }

  private Kv result(Kv response) { return ProxyJson.object(response.get(response.containsKey("Result") ? "Result" : "result")); }
  private String first(String... values) { for (String value : values) { if (value != null && !value.isBlank()) { return value; } } return null; }
  private String string(Object value) { return value == null ? null : value.toString(); }
  private Kv queryObject(String text) {
    if (text == null) { return Kv.create(); }
    try { return ProxyJson.parse(text); } catch (RuntimeException ignored) {
      try { return ProxyJson.parse(URLDecoder.decode(text, StandardCharsets.UTF_8)); } catch (RuntimeException invalid) { return Kv.create(); }
    }
  }
  private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
