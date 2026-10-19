package nexus.io.llm.proxy.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.LobsterAiTokenResponse;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.Request;
import okhttp3.Response;

/** Native Youdao desktop authorization and OpenAI-compatible inference. */
public class LobsterAiService {
  private volatile String cachedVersion;
  private volatile long versionExpires;

  public String apiBaseUrl() {
    return EnvUtils.get("proxy.oauth.lobsterai.api-base-url", "https://lobsterai-server.youdao.com").replaceAll("/+$",
        "");
  }

  public String initialize(OAuthSession session) {
    session.pluginMetadata = Kv.create().set("uuid", UUID.randomUUID().toString()).set("first_keyfrom",
        Long.toString(System.currentTimeMillis()));
    return EnvUtils.get("proxy.oauth.lobsterai.portal-base-url", "https://lobsterai.youdao.com").replaceAll("/+$", "")
        + "/portal#/login?source=electron&redirect_uri=" + encode(session.spec.redirectUri) + "&state="
        + encode(session.state);
  }

  public Kv exchange(String code, OAuthSession session) throws Exception {
    Kv metadata = session.pluginMetadata;
    BusinessException.require(metadata != null && metadata.getStr("uuid") != null, 400, "INVALID_SESSION",
        "LobsterAI login session is missing");
    Kv credential = Kv.create().set(metadata).set("latest_keyfrom", Long.toString(System.currentTimeMillis()));
    Kv body = keyfrom(credential).set("authCode", code);
    return normalizeToken(post("/api/auth/exchange", body), credential, true);
  }

  public Kv refresh(Kv credential) throws Exception {
    BusinessException.require(
        credential.getStr("refresh_token") != null && !credential.getStr("refresh_token").isBlank(), 400,
        "NO_REFRESH_TOKEN", "LobsterAI credential has no refresh token");
    return normalizeToken(
        post("/api/auth/refresh", keyfrom(credential).set("refreshToken", credential.get("refresh_token"))),
        Kv.create().set(credential), false);
  }

  private Kv keyfrom(Kv credential) {
    Kv body = Kv.create().set("firstKeyfrom", ProxyJson.text(credential, "first_keyfrom", ""))
        .set("latestKeyfrom", ProxyJson.text(credential, "latest_keyfrom", "")).set("version", clientVersion());
    if (credential.getStr("uuid") != null) {
      body.set("uuid", credential.get("uuid"));
    }
    if (credential.getStr("user_id") != null) {
      body.set("userId", credential.get("user_id"));
    }
    return body;
  }

  private Kv post(String path, Kv body) throws Exception {
    return Aop.get(OAuthService.class).post(apiBaseUrl() + path, body, true, null, anonymousHeaders());
  }

  public Kv normalizeToken(Kv envelope, Kv credential, boolean initial) throws Exception {
    OAuthFailureUtils.requireSuccess(envelope, "LobsterAI token exchange");
    LobsterAiTokenResponse parsed = JsonUtils.parse(JsonUtils.toJson(envelope), LobsterAiTokenResponse.class);
    BusinessException.require(
        parsed.code != null && parsed.code == 0 && parsed.data != null && parsed.data.accessToken != null
            && !parsed.data.accessToken.isBlank(),
        502, "LOBSTERAI_TOKEN_FAILED", "LobsterAI did not return an access token");
    LobsterAiTokenResponse.Token token = parsed.data;
    credential.set("type", "lobsterai").set("access_token", token.accessToken).set("last_refresh",
        Instant.now().toString());
    if (token.refreshToken != null && !token.refreshToken.isBlank()) {
      credential.set("refresh_token", token.refreshToken);
    }
    credential.remove("expired");
    credential.remove("expires_at");
    if (token.expiresIn != null && token.expiresIn > 0) {
      credential.set("expired", Instant.now().plusSeconds(token.expiresIn).toString());
    } else {
      try {
        String[] parts = token.accessToken.split("\\.");
        Kv claims = ProxyJson.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        long expiry = ProxyJson.number(claims, "exp", 0);
        if (expiry > 0) {
          credential.set("expired", Instant.ofEpochSecond(expiry).toString());
        }
      } catch (RuntimeException ignored) {
        // Claims only provide optional expiration metadata.
      }
    }
    if (initial) {
      LobsterAiTokenResponse.User user = token.user == null ? new LobsterAiTokenResponse.User() : token.user;
      String uid = first(user.id, user.userId, user.yid);
      if (uid == null) {
        uid = HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(token.accessToken.getBytes(StandardCharsets.UTF_8)))
            .substring(0, 16);
      }
      credential.set("uid", uid).set("account_id", uid).set("user_id", first(user.userId, user.yid));
      if (user.nickname != null) {
        credential.set("nickname", user.nickname).set("label", user.nickname);
      }
    }
    return credential;
  }

  private String first(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private Kv anonymousHeaders() {
    return Kv.create().set("Accept", "application/json").set("User-Agent",
        EnvUtils.get("proxy.oauth.lobsterai.user-agent", "LobsterAI/0.1.0"));
  }

  public Kv headers(boolean stream) {
    return anonymousHeaders().set("Accept", stream ? "text/event-stream, application/json" : "application/json")
        .set("X-LobsterAI-Client-Version", clientVersion())
        .set("X-LobsterAI-Client-Capabilities", "kimi-k3-agentic-v1,thinking-level-control-v1");
  }

  public synchronized String clientVersion() {
    String configured = EnvUtils.get("proxy.oauth.lobsterai.client-version");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    if (cachedVersion != null && versionExpires > System.currentTimeMillis()) {
      return cachedVersion;
    }
    try {
      Kv response = Aop.get(OAuthService.class).getJson(EnvUtils.get("proxy.oauth.lobsterai.version-url",
          "https://api-overmind.youdao.com/openapi/get/luna/hardware/lobsterai/prod/update"), null);
      String version = ProxyJson.object(ProxyJson.object(response.get("data")).get("value")).getStr("version");
      if (version != null && version.matches("[0-9]+(?:\\.[0-9]+){1,3}")) {
        cachedVersion = version;
        versionExpires = System.currentTimeMillis() + 12 * 60 * 60 * 1000L;
        return version;
      }
    } catch (Exception ignored) {
      // A transient update-service failure must not prevent authentication.
    }
    return EnvUtils.get("proxy.oauth.lobsterai.fallback-client-version", "2026.9.4");
  }

  public List<Kv> models(Kv credential) throws Exception {
    Request.Builder builder = new Request.Builder().url(apiBaseUrl() + "/api/models/available").header("Authorization",
        "Bearer " + credential.getStr("access_token"));
    headers(false).forEach((key, value) -> builder.header(key.toString(), value.toString()));
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    try (
        Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 30), builder.build())) {
      BusinessException.require(response.isSuccessful() && response.body() != null, 502, "LOBSTERAI_MODELS_FAILED",
          "LobsterAI model discovery failed");
      Kv envelope = ProxyJson.parse(response.body().string());
      BusinessException.require(ProxyJson.number(envelope, "code", -1) == 0, 502, "LOBSTERAI_MODELS_FAILED",
          "LobsterAI model discovery was rejected");
      Object data = envelope.get("data");
      List<?> rows = data instanceof List<?> list ? list : ProxyJson.list(ProxyJson.object(data).get("data"));
      List<Kv> result = new ArrayList<>();
      for (Object row : rows) {
        Kv model = ProxyJson.object(row);
        String id = model.getStr("modelId");
        if (id != null && !id.isBlank()) {
          result.add(Kv.create().set(model).set("id", id).set("name", ProxyJson.text(model, "modelName", id)));
        }
      }
      return result;
    }
  }

  public Kv payload(Kv input) {
    Kv body = Kv.create().set(input).set("stream", true);
    Object choice = body.get("tool_choice");
    if (choice == null || "".equals(choice) || "none".equals(choice)) {
      body.remove("tool_choice");
    }
    body.remove("prompt_cache_key");
    return body;
  }

  public Response response(Response response, boolean clientStream) throws Exception {
    return Aop.get(ClineTransportService.class).response(response, clientStream);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
