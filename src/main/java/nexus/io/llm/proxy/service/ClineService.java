package nexus.io.llm.proxy.service;

import java.time.Instant;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.llm.proxy.model.ClineTokenResponse;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.Request;
import okhttp3.Response;

/** WorkOS registration, rotating Cline credentials and live model discovery. */
public class ClineService {
  private record Catalog(long expiresAt, List<Kv> models) { }
  private final ConcurrentHashMap<String, Catalog> catalogs = new ConcurrentHashMap<>();

  public String apiBaseUrl() {
    return EnvUtils.get("proxy.oauth.cline.api-base-url", "https://api.cline.bot/api/v1").replaceAll("/+$", "");
  }

  public Kv headers() {
    return Kv.create().set("HTTP-Referer", "https://cline.bot").set("X-Title", "Cline")
        .set("X-IS-MULTIROOT", "false").set("X-CLIENT-TYPE", "cline-sdk");
  }

  public Kv register(Kv workos) throws Exception {
    String access = required(workos.getStr("access_token"), "WorkOS access token");
    String refresh = required(workos.getStr("refresh_token"), "WorkOS refresh token");
    Kv response = Aop.get(OAuthService.class).post(endpoint("register-url", "/auth/register"),
        Kv.create().set("accessToken", access).set("refreshToken", refresh), true, null, headers());
    return normalizeToken(response, Kv.create());
  }

  public Kv refresh(Kv credential) throws Exception {
    String refresh = required(credential.getStr("refresh_token"), "Cline refresh token");
    Kv response = Aop.get(OAuthService.class).post(endpoint("refresh-url", "/auth/refresh"),
        Kv.create().set("refreshToken", refresh).set("grantType", "refresh_token"), true, null, headers());
    return normalizeToken(response, Kv.create().set(credential));
  }

  public Kv normalizeToken(Kv response, Kv credential) {
    OAuthFailureUtils.requireSuccess(response, "Cline token request");
    ClineTokenResponse parsed = JsonUtils.parse(JsonUtils.toJson(response), ClineTokenResponse.class);
    BusinessException.require(!Boolean.FALSE.equals(parsed.success), 502, "CLINE_TOKEN_FAILED", "Cline token request was rejected");
    ClineTokenResponse.Token token = parsed.data;
    String access = required(token == null ? parsed.accessToken : token.accessToken, "Cline access token");
    // The prefix is part of the credential sent to Cline, not a display label.
    BusinessException.require(access.startsWith("workos:") && access.length() > 7, 502,
        "INVALID_CLINE_TOKEN", "Cline token response has an invalid access token format");
    String refresh = token == null ? parsed.refreshToken : token.refreshToken;
    String expiry = token == null ? parsed.expiresAt : token.expiresAt;
    ClineTokenResponse.UserInfo user = token == null ? parsed.userInfo : token.userInfo;
    credential.set("type", "cline").set("access_token", access).set("last_refresh", Instant.now().toString());
    if (refresh != null && !refresh.isBlank()) {
      credential.set("refresh_token", refresh);
    }
    if (expiry != null && !expiry.isBlank()) {
      try {
        credential.set("expired", Instant.parse(expiry).toString());
      } catch (RuntimeException error) {
        throw new OAuthRequestException("Cline token response has an invalid expiry");
      }
    } else {
      // A rotated access token must not retain the previous token's expiration.
      credential.remove("expired");
      String[] segments = access.substring(7).split("\\.");
      if (segments.length == 3) {
        try {
          Kv claims = ProxyJson.parse(new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8));
          long expires = ProxyJson.number(claims, "exp", 0);
          if (expires > 0) {
            credential.set("expired", Instant.ofEpochSecond(expires).toString());
          }
        } catch (RuntimeException ignored) {
          // JWT claims are optional expiration metadata, never authorization evidence.
        }
      }
    }
    if (user != null) {
      if (user.clineUserId != null) {
        credential.set("account_id", user.clineUserId);
      }
      if (user.email != null) {
        credential.set("email", user.email);
      }
    }
    return credential;
  }

  private String required(String value, String field) {
    BusinessException.require(value != null && !value.isBlank(), 502, "MISSING_CLINE_TOKEN", field + " is missing");
    return value;
  }

  private String endpoint(String setting, String path) {
    return EnvUtils.get("proxy.oauth.cline." + setting, apiBaseUrl() + path);
  }

  public List<Kv> models(Kv credential) throws Exception {
    String token = credential == null ? null : credential.getStr("access_token");
    String key = apiBaseUrl() + "|" + (token == null ? "anonymous" : token);
    Catalog cached = catalogs.get(key);
    long now = System.currentTimeMillis();
    if (cached != null && cached.expiresAt > now) {
      return copyModels(cached.models);
    }
    Kv recommended = Kv.create();
    Kv remote = Kv.create();
    Exception failure = null;
    boolean received = false;
    try {
      recommended = get(endpoint("recommended-models-url", "/ai/cline/recommended-models"), null);
      received = true;
    } catch (Exception error) {
      failure = error;
    }
    if (token != null && !token.isBlank()) {
      try {
        remote = get(endpoint("models-url", "/models"), token);
        received = true;
      } catch (Exception error) {
        failure = error;
      }
    }
    if (!received && failure != null) {
      throw failure;
    }
    List<Kv> models = mergeModels(recommended, remote);
    catalogs.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    if (catalogs.size() < 256) {
      catalogs.put(key, new Catalog(now + 60000, models));
    }
    return copyModels(models);
  }

  private List<Kv> copyModels(List<Kv> models) {
    List<Kv> copy = new ArrayList<>();
    for (Kv model : models) {
      copy.add(Kv.create().set(model));
    }
    return copy;
  }

  public List<Kv> mergeModels(Kv recommended, Kv remote) {
    Map<String, Kv> models = new LinkedHashMap<>();
    for (String group : List.of("free", "recommended", "clinePass")) {
      addModels(models, ProxyJson.list(recommended.get(group)), "free".equals(group));
    }
    addModels(models, ProxyJson.list(remote.get("data")), false);
    return new ArrayList<>(models.values());
  }

  private void addModels(Map<String, Kv> models, List<?> entries, boolean free) {
    for (Object entry : entries) {
      Kv source = ProxyJson.object(entry);
      String id = source.getStr("id");
      if (id == null || id.isBlank()) {
        continue;
      }
      Kv model = models.computeIfAbsent(id, ignored -> Kv.create().set("id", id).set("object", "model")
          .set("owned_by", "cline").set("name", ProxyJson.text(source, "name", id))
          .set("description", source.getStr("description"))
          .set("is_free", id.endsWith(":free") || id.startsWith("cline-free/")));
      if (free) {
        model.set("is_free", true);
      }
    }
  }

  private Kv get(String url, String token) throws Exception {
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    Request.Builder request = new Request.Builder().url(url).header("Accept", "application/json");
    headers().forEach((name, value) -> request.header(name.toString(), value.toString()));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    try (Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 20), request.build())) {
      if (!response.isSuccessful()) {
        throw new OAuthRequestException("Cline model discovery failed (HTTP " + response.code() + ")");
      }
      return ProxyJson.parse(response.body().string());
    }
  }
}
