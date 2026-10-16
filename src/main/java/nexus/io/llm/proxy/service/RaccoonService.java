package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.llm.proxy.model.RaccoonSmsInput;
import nexus.io.llm.proxy.model.RaccoonTokenResponse;
import nexus.io.llm.proxy.validation.RaccoonParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** Native Raccoon QR login; SMS requires a user-completed Aliyun captcha. */
public class RaccoonService {
  private record Catalog(long expiresAt, List<Kv> models) { }
  private final ConcurrentHashMap<String, Catalog> catalogs = new ConcurrentHashMap<>();

  private String config(String key, String fallback) {
    return EnvUtils.get("proxy.oauth.raccoon." + key, fallback);
  }

  private String site() {
    return config("site-url", "https://xiaohuanxiong.com").replaceAll("/+$", "");
  }

  private String auth(String setting, String path) {
    return config(setting, site() + "/api/web/auth/v1/" + path);
  }

  public String apiBaseUrl() {
    return config("api-base-url", site() + "/api/web/llm/v2").replaceAll("/+$", "");
  }

  public OAuthProviderSpec spec() {
    return new OAuthProviderSpec("raccoon", "", null, null, auth("poll-url", "login_with_qrcode_code"), null, "", null, true);
  }

  public void start(OAuthSession session, OAuthStartResult result) {
    session.spec = spec();
    session.deviceCode = randomHex();
    session.verifier = randomHex();
    session.interval = 2;
    session.nextPoll = System.currentTimeMillis() + 2000;
    session.expiresAt = System.currentTimeMillis() + 300000;
    result.url = HttpUrl.get(config("qr-page-url", site() + "/login/mp")).newBuilder()
        .setQueryParameter("code", session.deviceCode).setQueryParameter("appname", "商汤小浣熊官网").build().toString();
    result.flow = "qr";
    result.expires_in = 300;
  }

  private String randomHex() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  protected Kv post(String url, Kv body) throws Exception {
    return Aop.get(OAuthService.class).post(url, body, true, null);
  }

  public Kv poll(OAuthSession session) {
    synchronized (session) {
      if (!"wait".equals(session.status)) {
        return null;
      }
      if (System.currentTimeMillis() >= session.expiresAt) {
        fail(session, "Raccoon QR authorization expired. Start a new sign-in.");
        return null;
      }
      if (System.currentTimeMillis() < session.nextPoll) {
        return null;
      }
      session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
      try {
        Kv response = post(session.spec.tokenUrl, Kv.create().set("qrcode_code", session.deviceCode));
        long http = ProxyJson.number(response, "http_status", 200);
        if (http == 429 || http >= 500) {
          session.interval = Math.min(30, session.interval + 2);
          session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
          return null;
        }
        requireSuccess(response);
        RaccoonTokenResponse token = JsonUtils.parse(JsonUtils.toJson(response.get("data")), RaccoonTokenResponse.class);
        BusinessException.require(token != null, 502, "RACCOON_RESPONSE", "Raccoon returned no QR status");
        if ("pending".equals(token.status) || "logging".equals(token.status)) {
          return null;
        }
        if ("canceled".equals(token.status) || "expired".equals(token.status)) {
          fail(session, "Raccoon authorization was cancelled or expired. Start a new sign-in.");
          return null;
        }
        BusinessException.require("success".equals(token.status), 502, "RACCOON_RESPONSE", "Raccoon returned an unknown QR status");
        Kv credential = normalizeToken(response, Kv.create().set("device_id", session.verifier));
        return credential;
      } catch (Exception error) {
        fail(session, OAuthFailureUtils.message("Raccoon QR login", error));
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

  private void fail(OAuthSession session, String error) {
    session.status = "error";
    session.error = error;
    session.deviceCode = null;
    session.verifier = null;
  }

  public Kv refresh(Kv credential) throws Exception {
    BusinessException.require(nonblank(credential.getStr("refresh_token")), 400, "NO_REFRESH_TOKEN", "Raccoon requires a new sign-in");
    Kv response = post(auth("refresh-url", "refresh"), Kv.create().set("refresh_token", credential.getStr("refresh_token")));
    if (ProxyJson.number(response, "http_status", 200) == 401 || ProxyJson.number(response, "code", 0) == 200003) {
      throw new OAuthRequestException("Raccoon refresh token expired. Start a new sign-in.");
    }
    return normalizeToken(response, credential);
  }

  public Kv normalizeToken(Kv response, Kv previous) {
    requireSuccess(response);
    RaccoonTokenResponse token = JsonUtils.parse(JsonUtils.toJson(response.get("data")), RaccoonTokenResponse.class);
    BusinessException.require(token != null && nonblank(token.access_token), 502, "RACCOON_RESPONSE", "Raccoon returned no access token");
    Kv credential = Kv.create().set(previous).set("type", "raccoon").set("access_token", token.access_token)
        .set("last_refresh", Instant.now().toString());
    if (nonblank(token.refresh_token)) {
      credential.set("refresh_token", token.refresh_token);
    }
    if (nonblank(token.office_identity)) {
      credential.set("office_identity", token.office_identity);
    }
    credential.remove("expired");
    String[] segments = token.access_token.split("\\.");
    if (segments.length >= 2) {
      try {
        Kv claims = ProxyJson.parse(new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8));
        long exp = ProxyJson.number(claims, "exp", 0);
        if (exp > 0) {
          credential.set("expired", Instant.ofEpochSecond(exp).toString());
        }
      } catch (RuntimeException ignored) {
        // JWT claims are optional expiry metadata; upstream validates authorization.
      }
    }
    if (!nonblank(credential.getStr("device_id"))) {
      credential.set("device_id", randomHex());
    }
    return credential;
  }

  private void requireSuccess(Kv response) {
    OAuthFailureUtils.requireSuccess(response, "Raccoon request");
    BusinessException.require(ProxyJson.number(response, "code", -1) == 0, 502, "RACCOON_RESPONSE", "Raccoon request was rejected");
  }

  private boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  /** No captcha automation: callers must obtain captcha_param through user interaction. */
  public void sendSms(RaccoonSmsInput input) throws Exception {
    RaccoonParameterValidator.sms(input, true);
    Kv response = post(auth("send-sms-url", "send_sms"), Kv.create().set("nation_code", "86")
        .set("phone", encryptPhone(input.phone)).set("captcha_param", input.captcha_param));
    requireSuccess(response);
  }

  public Kv loginSms(RaccoonSmsInput input) throws Exception {
    RaccoonParameterValidator.sms(input, false);
    Kv response = post(auth("sms-login-url", "login_with_sms"), Kv.create().set("nation_code", "86")
        .set("phone", encryptPhone(input.phone)).set("sms_code", input.sms_code));
    return normalizeToken(response, Kv.create());
  }

  public String encryptPhone(String phone) throws Exception {
    byte[] iv = new byte[16];
    new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(config("phone-cipher-secret", "senseraccoon2023").getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(iv));
    byte[] ciphertext = cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8));
    byte[] combined = new byte[iv.length + ciphertext.length];
    System.arraycopy(iv, 0, combined, 0, iv.length);
    System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
    return Base64.getEncoder().encodeToString(combined);
  }

  public Kv headers(Kv credential) {
    String platform = "desktop-" + switch (PluginService.os()) {
      case "windows" -> "windows";
      case "darwin" -> "macos";
      default -> "linux";
    };
    Kv headers = Kv.create().set("Accept", "application/json").set("X-Org-Code", ProxyJson.text(credential, "office_identity", ""))
        .set("X-Raccoon-Language", "zh").set("X-Client-Platform", config("client-platform", platform))
        .set("X-Client-Version", config("client-version", "v1.0.35"));
    if (nonblank(credential.getStr("device_id"))) {
      headers.set("X-Client-Device-ID", credential.getStr("device_id"));
    }
    return headers;
  }

  protected Kv get(String url, Kv credential) throws Exception {
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    Request.Builder request = new Request.Builder().url(url).header("Authorization", "Bearer " + credential.getStr("access_token"));
    headers(credential).forEach((key, value) -> request.header(key.toString(), value.toString()));
    try (Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 30), request.build())) {
      Kv payload = ProxyJson.parse(response.body().string());
      payload.set("http_status", response.code());
      requireSuccess(payload);
      return payload;
    }
  }

  public List<Kv> models(Kv credential) throws Exception {
    if (credential == null || !nonblank(credential.getStr("access_token"))) {
      return List.of();
    }
    String url = config("models-url", apiBaseUrl() + "/model_catalog");
    String key = url + "|" + credential.getStr("access_token") + "|" + credential.getStr("office_identity");
    long now = System.currentTimeMillis();
    Catalog cached = catalogs.get(key);
    if (cached != null && cached.expiresAt > now) {
      return copy(cached.models);
    }
    List<Kv> models = parseModels(get(url, credential));
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
    requireSuccess(payload);
    Map<String, Kv> result = new LinkedHashMap<>();
    for (Object category : ProxyJson.list(ProxyJson.object(payload.get("data")).get("categories"))) {
      Kv group = ProxyJson.object(category);
      if (!"chat".equals(group.getStr("type"))) {
        continue;
      }
      for (Object value : ProxyJson.list(group.get("models"))) {
        Kv raw = ProxyJson.object(value);
        String id = raw.getStr("name");
        if (!nonblank(id) || Boolean.FALSE.equals(raw.getBoolean("visible"))) {
          continue;
        }
        Kv model = Kv.create().set("id", id).set("name", ProxyJson.text(raw, "description", id))
            .set("object", "model").set("owned_by", "raccoon").set("reasoning_efforts", List.of("on", "off"));
        Kv params = ProxyJson.object(raw.get("params"));
        long context = ProxyJson.number(params, "context_window", ProxyJson.number(raw, "context_window", 0));
        long output = ProxyJson.number(params, "max_tokens", 0);
        if (context > 0) {
          model.set("context_length", context);
        }
        if (output > 0) {
          model.set("max_output_tokens", output);
        }
        Object rate = raw.get("billing_effective_multiplier");
        if (rate instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0) {
          model.set("billing_effective_multiplier", rate).set("is_free", number.doubleValue() == 0);
        }
        for (String field : List.of("billing_multiplier", "billing_status", "billing_status_note", "tags")) {
          if (raw.get(field) != null) {
            model.set(field, raw.get(field));
          }
        }
        result.putIfAbsent(id, model);
      }
    }
    return new ArrayList<>(result.values());
  }
}
