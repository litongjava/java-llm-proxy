package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.llm.proxy.model.QoderLoginSession;
import nexus.io.llm.proxy.model.QoderTokenResponse;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** Native Qoder/QoderCN PKCE control plane; inference is delegated to the WASM transport. */
public class QoderService {
  private String config(String provider, String key, String fallback) {
    return EnvUtils.get("proxy.oauth." + provider + "." + key, fallback);
  }

  private boolean cn(String provider) {
    BusinessException.require("qoder".equals(provider) || "qodercn".equals(provider), 400, "QODER_PROVIDER", "Unknown Qoder region");
    return "qodercn".equals(provider);
  }

  private String openApi(String provider) {
    return config(provider, "open-api-url", cn(provider) ? "https://openapi.qoder.com.cn" : "https://openapi.qoder.sh").replaceAll("/+$", "");
  }

  public String apiBaseUrl(String provider) {
    return config(provider, "api-base-url", cn(provider) ? "https://gateway.qoder.com.cn" : "https://api2.qoder.sh").replaceAll("/+$", "");
  }

  public OAuthProviderSpec spec(String provider) {
    String authBase = cn(provider) ? "https://qoder.cn" : "https://qoder.com";
    String client = cn(provider) ? "732aef47-9cf2-46a2-95fe-4cebb5d0d1fa" : "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb";
    return new OAuthProviderSpec(provider, config(provider, "client-id", client), null,
        config(provider, "authorize-url", authBase + "/device/selectAccounts"),
        config(provider, "poll-url", openApi(provider) + "/api/v1/deviceToken/poll"), null, "", null, false);
  }

  public void start(OAuthSession session, OAuthStartResult result) throws Exception {
    QoderLoginSession pending = new QoderLoginSession();
    pending.nonce = UUID.randomUUID().toString();
    pending.machineId = UUID.randomUUID().toString();
    session.qoder = pending;
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    session.verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(session.verifier.getBytes(StandardCharsets.US_ASCII)));
    session.expiresAt = System.currentTimeMillis() + 300000;
    session.interval = 1;
    session.nextPoll = System.currentTimeMillis() + 1000;
    result.url = HttpUrl.get(session.spec.authorizeUrl).newBuilder().setQueryParameter("challenge", challenge)
        .setQueryParameter("challenge_method", "S256").setQueryParameter("nonce", pending.nonce)
        .setQueryParameter("machine_id", pending.machineId).setQueryParameter("client_id", session.spec.clientId).build().toString();
    result.flow = "device";
    result.expires_in = 300;
  }

  protected Kv get(String url, String bearer) throws Exception {
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    Request.Builder request = new Request.Builder().url(url).header("Accept", "application/json");
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    try (Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 20), request.build())) {
      if (response.code() == 404) {
        return Kv.create().set("http_status", 404);
      }
      Kv payload = ProxyJson.parse(response.body().string());
      payload.set("http_status", response.code());
      return payload;
    }
  }

  public Kv poll(OAuthSession session) {
    synchronized (session) {
      if (!"wait".equals(session.status) || session.qoder == null) {
        return null;
      }
      if (System.currentTimeMillis() >= session.expiresAt) {
        fail(session, "Qoder authorization expired. Start a new sign-in.");
        return null;
      }
      if (System.currentTimeMillis() < session.nextPoll) {
        return null;
      }
      session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
      try {
        String url = HttpUrl.get(session.spec.tokenUrl).newBuilder().setQueryParameter("nonce", session.qoder.nonce)
            .setQueryParameter("verifier", session.verifier).setQueryParameter("challenge_method", "S256").build().toString();
        Kv response = get(url, null);
        session.qoder.consecutiveFailures = 0;
        if (ProxyJson.number(response, "http_status", 200) == 404) {
          return null;
        }
        OAuthFailureUtils.requireSuccess(response, "Qoder device authorization");
        QoderTokenResponse token = JsonUtils.parse(JsonUtils.toJson(response), QoderTokenResponse.class);
        if (!nonblank(access(token))) {
          return null;
        }
        Kv credential = normalizeToken(session.spec.provider, response, Kv.create().set("machine_id", session.qoder.machineId));
        try {
          Kv info = get(config(session.spec.provider, "profile-url", openApi(session.spec.provider) + "/api/v1/userinfo"), credential.getStr("access_token"));
          if (ProxyJson.number(info, "http_status", 200) == 200 && nonblank(info.getStr("name"))) {
            credential.set("nickname", info.getStr("name"));
          }
        } catch (Exception ignored) {
          // Optional display metadata must not invalidate an authorized account.
        }
        return credential;
      } catch (IOException error) {
        session.qoder.consecutiveFailures++;
        if (session.qoder.consecutiveFailures >= 5) {
          fail(session, "Qoder login service is unavailable. Start a new sign-in.");
        } else {
          session.interval = Math.min(10, session.interval + 1);
          session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
        }
        return null;
      } catch (Exception error) {
        fail(session, OAuthFailureUtils.message("Qoder login", error));
        return null;
      }
    }
  }

  private void fail(OAuthSession session, String message) {
    session.status = "error";
    session.error = message;
    session.verifier = null;
    session.qoder = null;
  }

  public Kv refresh(String provider, Kv credential) throws Exception {
    BusinessException.require(nonblank(credential.getStr("refresh_token")) && nonblank(credential.getStr("machine_id")),
        400, "QODER_REFRESH_FIELDS", "Qoder refresh requires its refresh token and original machine ID");
    Kv response = Aop.get(OAuthService.class).post(config(provider, "refresh-url", openApi(provider) + "/api/v1/deviceToken/refresh"),
        Kv.create().set("refresh_token", credential.getStr("refresh_token")).set("machine_id", credential.getStr("machine_id")),
        true, null, Kv.create().set("User-Agent", "qoder/1.0.0"));
    return normalizeToken(provider, response, credential);
  }

  public Kv normalizeToken(String provider, Kv response, Kv previous) {
    cn(provider);
    OAuthFailureUtils.requireSuccess(response, "Qoder token request");
    QoderTokenResponse token = JsonUtils.parse(JsonUtils.toJson(response), QoderTokenResponse.class);
    String access = access(token);
    String uid = first(token.user_id, token.userId, previous.getStr("uid"));
    BusinessException.require(nonblank(access) && nonblank(uid) && nonblank(previous.getStr("machine_id")),
        502, "QODER_TOKEN_FIELDS", "Qoder credential is missing access token, user ID or machine ID");
    Kv result = Kv.create().set(previous).set("type", provider).set("access_token", access).set("security_oauth_token", access)
        .set("uid", uid).set("account_id", uid).set("last_refresh", Instant.now().toString());
    String refresh = first(token.refresh_token, token.refreshToken, previous.getStr("refresh_token"));
    if (refresh != null) {
      result.set("refresh_token", refresh);
    }
    String name = first(token.user_name, token.userName, previous.getStr("nickname"));
    if (name != null) {
      result.set("nickname", name);
    }
    Instant expiration = timestamp(response.get("expires_at") == null ? response.get("expiresAt") : response.get("expires_at"));
    result.remove("expired");
    if (expiration != null) {
      result.set("expired", expiration.toString()).set("expire_time", expiration.toEpochMilli());
    } else {
      result.remove("expire_time");
    }
    Instant refreshExpiration = timestamp(response.get("refresh_token_expires_at") == null
        ? response.get("refreshTokenExpiresAt") : response.get("refresh_token_expires_at"));
    if (refreshExpiration != null) {
      result.set("refresh_token_expire_time", refreshExpiration.toEpochMilli());
    }
    return result;
  }

  private String access(QoderTokenResponse token) {
    return first(token.token, token.device_token, token.access_token);
  }

  private String first(String... values) {
    for (String value : values) {
      if (nonblank(value)) {
        return value;
      }
    }
    return null;
  }

  private boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  private Instant timestamp(Object value) {
    try {
      if (value instanceof Number number) {
        long time = number.longValue();
        if (time > 0) {
          return time < 1000000000000L ? Instant.ofEpochSecond(time) : Instant.ofEpochMilli(time);
        }
      }
      if (value instanceof String text && !text.isBlank()) {
        return Instant.parse(text);
      }
    } catch (RuntimeException ignored) {
      // An absent or unparseable timestamp must not become a false epoch-zero expiration.
    }
    return null;
  }
}
