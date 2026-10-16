package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jfinal.kit.Kv;

import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.llm.proxy.model.ZCodeLoginResponse;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ZCode CLI authorization; platform JWT expiration requires a new interactive
 * login.
 */
public class ZCodeService {
  private record Catalog(long expiresAt, List<Kv> models) {
  }

  private final ConcurrentHashMap<String, Catalog> catalogs = new ConcurrentHashMap<>();

  private String config(String key, String fallback) {
    return EnvUtils.get("proxy.oauth.zcode." + key, fallback);
  }

  private String origin() {
    return config("origin", "https://zcode.z.ai").replaceAll("/+$", "");
  }

  public OAuthProviderSpec spec() {
    return new OAuthProviderSpec("zcode", "", null, null, origin() + "/api/v1/oauth/cli/poll", null, "", null, true);
  }

  public String apiBaseUrl() {
    return config("api-base-url", origin() + "/api/v1/zcode-plan/anthropic/v1").replaceAll("/+$", "");
  }

  public String apiBaseUrl(Kv credential) {
    return "coding-plan".equals(credential.getStr("zcode_channel"))
        ? config("coding-api-base-url", "https://api.z.ai/api/anthropic/v1").replaceAll("/+$", "")
        : apiBaseUrl();
  }

  public String accessToken(Kv credential) {
    if ("coding-plan".equals(credential.getStr("zcode_channel"))) {
      String key = credential.getStr("coding_plan_key");
      BusinessException.require(nonblank(key), 400, "ZCODE_CODING_KEY_MISSING",
          "No existing ZCode coding-plan API key is available");
      return key;
    }
    return credential.getStr("access_token");
  }

  public Kv headers(Kv credential) {
    String version = ProxyJson.text(credential, "app_version", config("app-version", "3.14.4"));
    String platform = switch (PluginService.os()) {
    case "windows" -> "win32";
    case "darwin" -> "darwin";
    default -> "linux";
    };
    Kv result = Kv.create().set("User-Agent", "ZCode/" + version).set("HTTP-Referer", origin())
        .set("X-ZCode-App-Version", version).set("X-Release-Channel", "stable").set("X-Client-Language", "zh-CN")
        .set("X-Client-Timezone", EnvUtils.get("TZ", "Asia/Shanghai")).set("X-Platform", platform)
        .set("X-Os-Category", PluginService.os()).set("anthropic-version", "2023-06-01");
    if (nonblank(credential.getStr("device_mid"))) {
      result.set("X-Device-Mid", credential.getStr("device_mid"));
    }
    if ("coding-plan".equals(credential.getStr("zcode_channel"))) {
      result.remove("HTTP-Referer");
    }
    return result;
  }

  public void start(OAuthSession session, OAuthStartResult result) throws Exception {
    session.spec = spec();
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    session.verifier = HexFormat.of().formatHex(random);
    String provider = config("login-provider", "bigmodel");
    BusinessException.require(List.of("bigmodel", "zai").contains(provider), 500, "ZCODE_CONFIG",
        "ZCode login provider must be bigmodel or zai");
    Kv response = Aop.get(OAuthService.class).post(config("init-url", origin() + "/api/v1/oauth/cli/init"),
        Kv.create().set("provider", provider), true, session.verifier, headers(Kv.create()));
    requireSuccess(response);
    ZCodeLoginResponse flow = JsonUtils.parse(JsonUtils.toJson(response.get("data")), ZCodeLoginResponse.class);
    BusinessException.require(
        flow != null && nonblank(flow.flow_id) && nonblank(flow.authorize_url)
            && flow.authorize_url.startsWith("https://"),
        502, "ZCODE_LOGIN_RESPONSE", "ZCode authorization response is incomplete");
    long now = System.currentTimeMillis();
    long expires = flow.expires_at == null ? now + 900000 : flow.expires_at * 1000;
    BusinessException.require(expires > now, 502, "ZCODE_LOGIN_RESPONSE", "ZCode authorization flow already expired");
    session.deviceCode = flow.flow_id;
    session.expiresAt = Math.min(now + 1800000, expires);
    session.interval = flow.poll_interval_sec == null ? 2 : Math.min(60, Math.max(1, flow.poll_interval_sec));
    session.nextPoll = now + session.interval * 1000L;
    result.url = flow.authorize_url;
    result.flow = "device";
    result.expires_in = (int) ((session.expiresAt - now) / 1000);
  }

  public Kv poll(OAuthSession session) {
    synchronized (session) {
      if (!"wait".equals(session.status)) {
        return null;
      }
      if (System.currentTimeMillis() >= session.expiresAt) {
        fail(session, "ZCode authorization expired. Start a new sign-in.");
        return null;
      }
      if (System.currentTimeMillis() < session.nextPoll) {
        return null;
      }
      session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
      try {
        String url = HttpUrl.get(config("poll-base-url", session.spec.tokenUrl)).newBuilder()
            .addPathSegment(session.deviceCode).build().toString();
        Kv response = get(url, session.verifier, Kv.create());
        long status = ProxyJson.number(response, "http_status", 200);
        if (status == 408 || status == 429 || status >= 500) {
          session.interval = Math.min(60, session.interval + 2);
          session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
          return null;
        }
        requireSuccess(response);
        ZCodeLoginResponse data = JsonUtils.parse(JsonUtils.toJson(response.get("data")), ZCodeLoginResponse.class);
        BusinessException.require(data != null, 502, "ZCODE_LOGIN_RESPONSE", "ZCode returned no login state");
        if ("pending".equals(data.status)) {
          return null;
        }
        BusinessException.require("ready".equals(data.status), 502, "ZCODE_LOGIN_FAILED",
            "ZCode authorization was declined or returned an invalid state");
        return credential(data);
      } catch (IOException error) {
        session.interval = Math.min(60, session.interval + 2);
        session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
        return null;
      } catch (Exception error) {
        fail(session, OAuthFailureUtils.message("ZCode login", error));
        return null;
      }
    }
  }

  public Kv credential(ZCodeLoginResponse data) {
    Kv user = data.user == null ? Kv.create() : data.user;
    String id = ProxyJson.text(user, "user_id", user.getStr("id"));
    Kv zai = data.zai == null ? Kv.create() : data.zai;
    Kv bigmodel = data.bigmodel == null ? Kv.create() : data.bigmodel;
    String zaiToken = ProxyJson.text(zai, "access_token", zai.getStr("accessToken"));
    String bigmodelToken = ProxyJson.text(bigmodel, "access_token", bigmodel.getStr("accessToken"));
    BusinessException.require(nonblank(data.token) && nonblank(id) && (nonblank(zaiToken) || nonblank(bigmodelToken)),
        502, "ZCODE_LOGIN_RESPONSE", "ZCode login returned incomplete credentials");
    Kv credential = Kv.create().set("type", "zcode").set("access_token", data.token).set("account_id", id)
        .set("nickname", ProxyJson.text(user, "name", ProxyJson.text(user, "email", id)))
        .set("email", user.get("email")).set("device_mid", UUID.randomUUID().toString())
        .set("app_version", config("app-version", "3.14.4")).set("zai_access_token", zaiToken)
        .set("bigmodel_access_token", bigmodelToken).set("zcode_channel", "start-plan")
        .set("last_refresh", Instant.now().toString());
    // Third-party refresh tokens cannot refresh the ZCode JWT and are deliberately
    // not promoted.
    String[] segments = data.token.split("\\.");
    if (segments.length >= 2) {
      try {
        Kv claims = ProxyJson.parse(new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8));
        long exp = ProxyJson.number(claims, "exp", 0);
        if (exp > 0) {
          credential.set("expired", Instant.ofEpochSecond(exp).toString());
        }
      } catch (RuntimeException ignored) {
        // Claims provide expiry metadata only.
      }
    }
    return credential;
  }

  public Kv refresh(Kv credential) {
    throw new OAuthRequestException("ZCode does not expose a platform JWT refresh endpoint. Start a new sign-in.");
  }

  public void cancel(OAuthSession session) {
    synchronized (session) {
      BusinessException.require(!"ok".equals(session.status), 409, "COMPLETED", "OAuth session already completed");
      fail(session, "cancelled");
    }
  }

  private void fail(OAuthSession session, String message) {
    session.status = "error";
    session.error = message;
    session.verifier = null;
    session.deviceCode = null;
  }

  private boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  private void requireSuccess(Kv response) {
    OAuthFailureUtils.requireSuccess(response, "ZCode request");
    BusinessException.require(ProxyJson.number(response, "code", 0) == 0, 502, "ZCODE_RESPONSE",
        "ZCode request was rejected");
  }

  protected Kv get(String url, String token, Kv credential) throws Exception {
    return ProxyJson.object(getValue(url, token, credential));
  }

  protected Object getValue(String url, String token, Kv credential) throws Exception {
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    Request.Builder request = new Request.Builder().url(url).header("Authorization", "Bearer " + token);
    headers(credential).forEach((key, value) -> request.header(key.toString(), value.toString()));
    try (
        Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 20), request.build())) {
      String body = response.body().string();
      if (body.stripLeading().startsWith("[") && response.isSuccessful()) {
        return JsonUtils.parse(body, List.class);
      }
      Kv payload = ProxyJson.parse(body);
      payload.set("http_status", response.code());
      return payload;
    }
  }

  public Kv clientConfig(Kv credential) throws Exception {
    String url = HttpUrl.get(config("config-url", origin() + "/api/v1/client/configs")).newBuilder()
        .setQueryParameter("app_version", ProxyJson.text(credential, "app_version", config("app-version", "3.14.4")))
        .setQueryParameter("platform", "unknown").build().toString();
    Kv payload = get(url, credential.getStr("access_token"), credential);
    requireSuccess(payload);
    return ProxyJson.object(payload.get("data"));
  }

  public Kv captchaConfig(Kv credential) throws Exception {
    return ProxyJson.object(ProxyJson.object(clientConfig(credential).get("configs")).get("captcha"));
  }

  public List<Kv> models(Kv credential) throws Exception {
    if (credential == null || !nonblank(credential.getStr("access_token"))) {
      return List.of();
    }
    String key = apiBaseUrl(credential) + "|" + accessToken(credential);
    long now = System.currentTimeMillis();
    Catalog cached = catalogs.get(key);
    if (cached != null && cached.expiresAt > now) {
      return copy(cached.models);
    }
    List<Kv> result = new ArrayList<>();
    if ("coding-plan".equals(credential.getStr("zcode_channel"))) {
      Kv response = get(apiBaseUrl(credential) + "/models", accessToken(credential), credential);
      requireSuccess(response);
      for (Object value : ProxyJson.list(response.get("data"))) {
        Kv model = ProxyJson.object(value);
        if (channelSupports("coding-plan", model.getStr("id"))) {
          result.add(Kv.create().set(model).set("owned_by", "zcode").set("channel", "coding-plan")
              .set("channel_support_source", "source-snapshot-2026-10-03"));
        }
      }
    } else {
      Object builtin = clientConfig(credential).get("builtinModels");
      List<?> entries = builtin instanceof List<?> list ? list : new ArrayList<>(ProxyJson.object(builtin).values());
      for (Object value : entries) {
        Kv raw = ProxyJson.object(value);
        String id = ProxyJson.text(raw, "modelId", raw.getStr("id"));
        if (!channelSupports("start-plan", id)) {
          continue;
        }
        Kv model = Kv.create().set("id", id).set("name", ProxyJson.text(raw, "name", id)).set("object", "model")
            .set("owned_by", "zcode").set("channel", "start-plan")
            .set("channel_support_source", "source-snapshot-2026-10-03");
        for (String field : List.of("contextWindow", "maxCompletionTokens", "capabilities", "modalities",
            "reasoning")) {
          if (raw.get(field) != null) {
            model.set(field, raw.get(field));
          }
        }
        result.add(model);
      }
    }
    catalogs.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    if (catalogs.size() < 256) {
      catalogs.put(key, new Catalog(now + 60000, result));
    }
    return copy(result);
  }

  /**
   * Conservative routing evidence from the source's verified 2026-10-03 channel
   * map. A global remote model catalog does not establish a subscription's
   * entitlements.
   */
  public boolean channelSupports(String channel, String model) {
    if (model == null) {
      return false;
    }
    String id = model.trim().toLowerCase(Locale.ROOT);
    return switch (channel) {
    case "start-plan" -> List.of("glm-5.3-flash", "glm-5.2", "glm-5-turbo").contains(id);
    case "coding-plan" -> List.of("glm-5.3", "glm-5.3-flash").contains(id);
    default -> false;
    };
  }

  private List<Kv> copy(List<Kv> models) {
    List<Kv> result = new ArrayList<>();
    for (Kv model : models) {
      result.add(ProxyJson.parse(JsonUtils.toJson(model)));
    }
    return result;
  }

  /**
   * Reads an existing named API key; never creates a cloud key or buys a
   * subscription.
   */
  public Kv resolveCodingPlanKey(Kv credential) throws Exception {
    String token = ProxyJson.text(credential, "zai_access_token", credential.getStr("bigmodel_access_token"));
    BusinessException.require(nonblank(token), 400, "ZCODE_OAUTH_TOKEN_MISSING",
        "No third-party OAuth access token is available");
    String origin = config("biz-origin", "https://api.z.ai");
    Kv customer = get(origin + "/api/biz/customer/getCustomerInfo", token, credential);
    requireSuccess(customer);
    Kv selectedOrg = null;
    List<Kv> selectedProjects = List.of();
    for (Object value : ProxyJson.list(ProxyJson.object(customer.get("data")).get("organizations"))) {
      Kv org = ProxyJson.object(value);
      List<Kv> projects = new ArrayList<>();
      for (Object entry : ProxyJson.list(org.get("projects"))) {
        Kv project = ProxyJson.object(entry);
        if (!"2".equals(String.valueOf(project.get("projectType"))) && nonblank(project.getStr("projectId"))) {
          projects.add(project);
        }
      }
      if (nonblank(org.getStr("organizationId")) && !projects.isEmpty() && (selectedOrg == null
          || ProxyJson.text(org, "organizationName", "").contains("\u9ed8\u8ba4\u673a\u6784"))) {
        selectedOrg = org;
        selectedProjects = projects;
      }
    }
    BusinessException.require(selectedOrg != null, 404, "ZCODE_ORGANIZATION_MISSING",
        "No eligible ZCode coding-plan project was found");
    Kv project = selectedProjects.getFirst();
    for (Kv candidate : selectedProjects) {
      if (ProxyJson.text(candidate, "projectName", "").contains("\u9ed8\u8ba4\u9879\u76ee")) {
        project = candidate;
        break;
      }
    }
    String listUrl = HttpUrl.get(origin).newBuilder().addPathSegments("api/biz/v1/organization")
        .addPathSegment(selectedOrg.getStr("organizationId")).addPathSegment("projects")
        .addPathSegment(project.getStr("projectId")).addPathSegment("api_keys").build().toString();
    Object listed = getValue(listUrl, token, credential);
    String apiKey = null;
    for (Object entry : ProxyJson.list(listed)) {
      Kv key = ProxyJson.object(entry);
      if ("zcode-api-key".equals(key.getStr("name")) && nonblank(key.getStr("apiKey"))) {
        apiKey = key.getStr("apiKey");
        break;
      }
    }
    BusinessException.require(apiKey != null, 404, "ZCODE_CODING_KEY_MISSING",
        "No existing zcode-api-key was found; configure the subscription key in the provider console");
    String copyUrl = HttpUrl.get(listUrl).newBuilder().addPathSegment("copy").addPathSegment(apiKey).build().toString();
    Kv copied = get(copyUrl, token, credential);
    requireSuccess(copied);
    String secret = copied.getStr("secretKey");
    BusinessException.require(nonblank(secret), 502, "ZCODE_CODING_KEY_MISSING",
        "ZCode did not return the existing key secret");
    return Kv.create().set(credential).set("coding_plan_key", apiKey + "." + secret);
  }
}
