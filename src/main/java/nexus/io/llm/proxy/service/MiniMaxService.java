package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.MiniMaxOAuthResponse;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.HttpUrl;

/** Native MiniMax Code CN device login and authenticated model discovery. */
public class MiniMaxService {
  private record Catalog(long expiresAt, List<Kv> models) { }
  private final ConcurrentHashMap<String, Catalog> catalogs = new ConcurrentHashMap<>();

  public OAuthProviderSpec spec() {
    return new OAuthProviderSpec("minimax", config("client-id", "mcode-public"), null, null,
        config("token-url", "https://account.minimax.cn/oauth2/token"),
        config("device-url", "https://account.minimax.cn/oauth2/device/code"),
        config("scope", "agent.default"), null, false);
  }

  public String apiBaseUrl() {
    return config("api-base-url", "https://agent.minimax.cn/mavis/api/v1/llm/v1").replaceAll("/+$", "");
  }

  private String config(String key, String fallback) {
    return EnvUtils.get("proxy.oauth.minimax." + key, fallback);
  }

  protected Kv post(String url, Kv parameters) throws Exception {
    return Aop.get(OAuthService.class).post(url, parameters, false, null);
  }

  public void start(OAuthSession session, OAuthStartResult result) throws Exception {
    session.spec = spec();
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    session.verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(session.verifier.getBytes(StandardCharsets.US_ASCII)));
    Kv response = post(session.spec.deviceUrl, Kv.create().set("client_id", session.spec.clientId)
        .set("scope", session.spec.scope).set("audience", config("audience", "agent-backend"))
        .set("code_challenge", challenge).set("code_challenge_method", "S256"));
    OAuthFailureUtils.requireSuccess(response, "MiniMax device authorization");
    MiniMaxOAuthResponse device = parse(response);
    requireText(device.device_code, "device code");
    requireText(device.user_code, "user code");
    String uri = nonblank(device.verification_uri) ? device.verification_uri : device.verification_url;
    requireText(uri, "verification URI");
    BusinessException.require(device.expires_in != null && device.expires_in > 0, 502,
        "MINIMAX_DEVICE_RESPONSE", "MiniMax device authorization returned invalid expiration");
    session.deviceCode = device.device_code;
    result.url = nonblank(device.verification_uri_complete) ? device.verification_uri_complete : uri;
    result.user_code = device.user_code;
    result.flow = "device";
    result.expires_in = (int) Math.min(1800, device.expires_in);
    session.expiresAt = System.currentTimeMillis() + result.expires_in * 1000L;
    session.interval = device.interval != null && device.interval > 0 ? Math.min(60, device.interval) : 5;
    session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
  }

  /** Returns a validated credential only after authorization; null means waiting or failed. */
  public Kv poll(OAuthSession session) {
    synchronized (session) {
      if (!"wait".equals(session.status)) {
        return null;
      }
      if (System.currentTimeMillis() >= session.expiresAt) {
        fail(session, "MiniMax device authorization expired. Start a new sign-in.");
        return null;
      }
      if (System.currentTimeMillis() < session.nextPoll) {
        return null;
      }
      session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
      try {
        Kv response = post(session.spec.tokenUrl, Kv.create().set("client_id", session.spec.clientId)
            .set("device_code", session.deviceCode).set("code_verifier", session.verifier)
            .set("grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
        MiniMaxOAuthResponse token = parse(response);
        long status = ProxyJson.number(response, "http_status", 200);
        boolean success = status >= 200 && status < 300;
        if ((success && "pending".equals(token.status)) || "authorization_pending".equals(token.error)) {
          return null;
        }
        if ((success && "slow_down".equals(token.status)) || "slow_down".equals(token.error) || status == 429 || status >= 500) {
          session.interval = Math.min(60, session.interval + 5);
          session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
          return null;
        }
        if (success && List.of("denied", "access_denied", "expired", "expired_token").contains(token.status == null ? "" : token.status)) {
          fail(session, "MiniMax device authorization was denied or expired. Start a new sign-in.");
          return null;
        }
        return normalizeToken(response, Kv.create());
      } catch (Exception error) {
        fail(session, OAuthFailureUtils.message("MiniMax device login", error));
        return null;
      }
    }
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

  public Kv refresh(Kv credential) throws Exception {
    String refresh = credential.getStr("refresh_token");
    requireText(refresh, "refresh token");
    OAuthProviderSpec spec = spec();
    Kv response = post(spec.tokenUrl, Kv.create().set("client_id", spec.clientId).set("scope", spec.scope)
        .set("audience", config("audience", "agent-backend")).set("grant_type", "refresh_token").set("refresh_token", refresh));
    return normalizeToken(response, credential);
  }

  public Kv normalizeToken(Kv response, Kv previous) {
    OAuthFailureUtils.requireSuccess(response, "MiniMax token request");
    MiniMaxOAuthResponse token = parse(response);
    requireText(token.access_token, "access token");
    String refresh = nonblank(token.refresh_token) ? token.refresh_token : previous.getStr("refresh_token");
    requireText(refresh, "refresh token");
    BusinessException.require("bearer".equalsIgnoreCase(token.token_type), 502, "MINIMAX_TOKEN_RESPONSE", "MiniMax token type must be Bearer");
    BusinessException.require(token.expires_in != null && token.expires_in > 0 && token.expires_in <= 315360000,
        502, "MINIMAX_TOKEN_RESPONSE", "MiniMax token expiration is invalid");
    List<String> scopes = token.scope == null ? List.of() : List.of(token.scope.trim().split("\\s+"));
    BusinessException.require(scopes.contains(spec().scope), 502, "MINIMAX_TOKEN_RESPONSE", "MiniMax token scope is missing required access");
    // MiniMax access tokens are opaque; expires_in is authoritative.
    return Kv.create().set(previous).set("type", "minimax").set("access_token", token.access_token)
        .set("refresh_token", refresh).set("token_type", "Bearer").set("scope", token.scope)
        .set("expired", Instant.now().plusSeconds(token.expires_in).toString()).set("last_refresh", Instant.now().toString());
  }

  private MiniMaxOAuthResponse parse(Kv value) {
    return JsonUtils.parse(JsonUtils.toJson(value), MiniMaxOAuthResponse.class);
  }

  private boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  private void requireText(String value, String field) {
    BusinessException.require(nonblank(value), 502, "MINIMAX_TOKEN_RESPONSE", "MiniMax response is missing " + field);
  }

  public List<Kv> models(Kv credential) throws Exception {
    if (credential == null || !nonblank(credential.getStr("access_token"))) {
      return List.of();
    }
    String url = HttpUrl.get(config("models-url", "https://agent.minimax.cn/mavis/api/v1/models")).newBuilder()
        .setQueryParameter("region", config("region", "cn")).setQueryParameter("buildEnv", config("build-env", "prod")).build().toString();
    String key = url + "|" + credential.getStr("access_token");
    long now = System.currentTimeMillis();
    Catalog cached = catalogs.get(key);
    if (cached != null && cached.expiresAt > now) {
      return copy(cached.models);
    }
    Kv payload = Aop.get(OAuthService.class).getJson(url, credential.getStr("access_token"));
    Kv base = ProxyJson.object(payload.get("base_resp"));
    BusinessException.require(ProxyJson.number(base, "status_code", 0) == 0, 502, "MINIMAX_MODELS_FAILED", "MiniMax model discovery was rejected");
    List<Kv> models = parseModels(payload);
    catalogs.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    if (catalogs.size() < 256) {
      catalogs.put(key, new Catalog(now + 60000, models));
    }
    return copy(models);
  }

  private List<Kv> copy(List<Kv> models) {
    List<Kv> result = new ArrayList<>();
    for (Kv model : models) {
      result.add(ProxyJson.parse(JsonUtils.toJson(model)));
    }
    return result;
  }

  public List<Kv> parseModels(Kv payload) {
    List<Kv> result = new ArrayList<>();
    for (Object item : ProxyJson.list(payload.get("providers"))) {
      Kv provider = ProxyJson.object(item);
      if (!"minimax".equals(provider.getStr("providerId"))) {
        continue;
      }
      Kv config = ProxyJson.object(provider.get("config"));
      Kv models = ProxyJson.object(config.get("models"));
      for (Object key : models.keySet()) {
        String id = key.toString();
        Kv raw = ProxyJson.object(models.get(key));
        Kv model = Kv.create().set("id", id).set("name", ProxyJson.text(raw, "name", id)).set("object", "model").set("owned_by", "minimax");
        Kv limit = ProxyJson.object(raw.get("limit"));
        long window = 0;
        Object options = raw.get("context_window_options");
        if (options == null) {
          options = raw.get("contextWindowOptions");
        }
        if (options == null) {
          options = raw.get("context_options");
        }
        for (Object value : ProxyJson.list(options)) {
          window = Math.max(window, positiveInteger(value));
        }
        if (window == 0) {
          window = positiveInteger(limit.get("context"));
        }
        if (window > 0) {
          model.set("context_length", window);
        }
        long output = positiveInteger(limit.get("output"));
        if (output > 0) {
          model.set("max_output_tokens", output);
        }
        model.set("supports_image", ProxyJson.list(ProxyJson.object(raw.get("modalities")).get("input")).contains("image"));
        Kv thinking = ProxyJson.object(raw.get("thinking"));
        List<?> efforts = ProxyJson.list(raw.get("effort_options") == null ? thinking.get("effortOptions") : raw.get("effort_options"));
        if (!efforts.isEmpty()) {
          model.set("effort_options", efforts);
          String defaultEffort = ProxyJson.text(raw, "default_effort", thinking.getStr("defaultEffort"));
          if (efforts.contains(defaultEffort)) {
            model.set("default_effort", defaultEffort);
          }
        }
        String mode = ProxyJson.object(raw.get("thinking_config")).getStr("mode");
        if (mode != null) {
          model.set("thinking_mode", mode);
        }
        result.add(model);
      }
      List<?> order = ProxyJson.list(config.get("model_order"));
      result.sort(Comparator.comparingInt(model -> {
        int index = order.indexOf(model.getStr("id"));
        return index < 0 ? Integer.MAX_VALUE : index;
      }));
      break;
    }
    return result;
  }

  private long positiveInteger(Object value) {
    if (value instanceof Number number) {
      double decimal = number.doubleValue();
      if (Double.isFinite(decimal) && decimal > 0 && decimal <= Integer.MAX_VALUE && decimal == Math.rint(decimal)) {
        return number.longValue();
      }
    }
    return 0;
  }
}
