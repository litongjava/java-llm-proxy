package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.llm.proxy.model.CodeArtsLoginSession;
import nexus.io.llm.proxy.model.CodeArtsTokenResponse;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** CodeArts PKCE/DPoP login and temporary Huawei credentials. */
public class CodeArtsService {
  private final ConcurrentHashMap<String, Set<String>> benefits = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "codearts-login-expiry");
    thread.setDaemon(true);
    return thread;
  });

  public String apiBaseUrl() { return EnvUtils.get("proxy.oauth.codearts.api-base-url", "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2").replaceAll("/+$", ""); }
  public String tokenUrl() { return EnvUtils.get("proxy.oauth.codearts.token-url", "https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens"); }
  public String clientId() { return EnvUtils.get("proxy.oauth.codearts.client-id", "codearts-agent"); }

  public CodeArtsLoginSession start() throws Exception {
    CodeArtsCryptoService crypto = Aop.get(CodeArtsCryptoService.class);
    CodeArtsLoginSession session = new CodeArtsLoginSession();
    session.verifier = crypto.random(48);
    session.privateJwk = crypto.generateJwk();
    session.ticketId = HexFormat.of().formatHex(Base64.getUrlDecoder().decode(crypto.random(32)));
    session.expiresAt = System.currentTimeMillis() + 180000;
    HttpServer server = null;
    for (int attempt = 0; attempt < 20; attempt++) {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      if (server.getAddress().getPort() >= 10000) { break; }
      server.stop(0);
      server = null;
    }
    if (server == null) { throw new IOException("Unable to allocate CodeArts callback port"); }
    session.server = server;
    int port = server.getAddress().getPort();
    session.redirectUri = "http://127.0.0.1:" + port + "/oauth/callback";
    session.authUrl = HttpUrl.get(EnvUtils.get("proxy.oauth.codearts.authorize-url", "https://codearts.huaweicloud.com/portal/authorize")).newBuilder()
        .addQueryParameter("theme", "2").addQueryParameter("locale", "zh-cn").addQueryParameter("uri_scheme", clientId())
        .addQueryParameter("client_id", clientId()).addQueryParameter("port", Integer.toString(port))
        .addQueryParameter("code_challenge", crypto.challenge(session.verifier)).addQueryParameter("code_challenge_method", "SHA-256")
        .addQueryParameter("ticket_id", session.ticketId).addQueryParameter("plugin-name", "snap_AIIDE").addQueryParameter("plugin-version", "5.2.0").build().toString();
    server.createContext("/oauth/callback", exchange -> {
      if (!"/oauth/callback".equals(exchange.getRequestURI().getPath())) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
      if ("OPTIONS".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "https://codearts.huaweicloud.com");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod()) || session.cancelled || System.currentTimeMillis() >= session.expiresAt || !session.exchanging.compareAndSet(false, true)) {
        exchange.sendResponseHeaders(400, -1); exchange.close(); return;
      }
      boolean complete = false;
      try {
        Kv query = query(exchange.getRequestURI().getRawQuery());
        String code = query.getStr("code");
        String secret = query.getStr("secret");
        if (code != null && !code.isBlank()) {
          Kv credential = exchange(session, code);
          synchronized (session) {
            if (session.cancelled) { throw new OAuthRequestException("CodeArts authorization was cancelled"); }
            session.credential = credential;
          }
          complete = true;
        }
        else if (secret != null && !secret.isBlank()) {
          synchronized (session) {
            if (session.cancelled) { throw new OAuthRequestException("CodeArts authorization was cancelled"); }
            session.ticketWorker = Thread.ofVirtual().unstarted(() -> legacyTicket(session, secret));
            session.ticketWorker.start();
          }
          complete = true;
        } else { throw new OAuthRequestException("CodeArts callback omitted the authorization code"); }
        byte[] message = "Authorization received. You can return to the application.".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, message.length);
        exchange.getResponseBody().write(message);
      } catch (Exception failure) {
        // An invalid callback does not consume the PKCE session or disclose upstream diagnostics.
        exchange.sendResponseHeaders(400, -1);
      } finally {
        exchange.close();
        if (!complete) { session.exchanging.set(false); }
        if (session.credential != null) { timers.schedule(session::close, 100, TimeUnit.MILLISECONDS); }
      }
    });
    server.start();
    timers.schedule(session::close, 180, TimeUnit.SECONDS);
    return session;
  }

  public Kv poll(CodeArtsLoginSession session) {
    if (session.credential != null) { return session.credential; }
    BusinessException.require(!session.cancelled, 400, "CODEARTS_LOGIN_CANCELLED", "CodeArts authorization was cancelled or expired");
    BusinessException.require(session.error == null, 502, "CODEARTS_LOGIN_FAILED", session.error == null ? "CodeArts login failed" : session.error);
    BusinessException.require(System.currentTimeMillis() < session.expiresAt, 400, "CODEARTS_LOGIN_EXPIRED", "CodeArts authorization expired");
    return null;
  }

  public Kv exchange(CodeArtsLoginSession session, String code) throws Exception {
    Kv form;
    Kv key;
    synchronized (session) {
      BusinessException.require(!session.cancelled && System.currentTimeMillis() < session.expiresAt, 400, "CODEARTS_LOGIN_EXPIRED", "CodeArts authorization expired or was cancelled");
      key = session.privateJwk;
      form = Kv.create().set("client_id", clientId()).set("code", code).set("code_verifier", session.verifier)
          .set("grant_type", "authorization_code").set("redirect_uri", session.redirectUri);
    }
    Kv result = token(form, key, Kv.create().set("code_verifier", form.get("code_verifier")).set("dpop_private_key_jwk", key));
    BusinessException.require(!session.cancelled, 400, "CODEARTS_LOGIN_CANCELLED", "CodeArts authorization was cancelled");
    return result;
  }

  public Kv refresh(Kv credential) throws Exception {
    Kv form = Kv.create().set("client_id", clientId()).set("code_verifier", required(credential.getStr("code_verifier")))
        .set("grant_type", "refresh_token").set("refresh_token", required(credential.getStr("refresh_token")));
    return token(form, ProxyJson.object(credential.get("dpop_private_key_jwk")), Kv.create().set(credential));
  }

  private Kv token(Kv values, Kv key, Kv previous) throws Exception {
    FormBody.Builder form = new FormBody.Builder();
    values.forEach((name, value) -> form.add(name.toString(), value.toString()));
    Request request = new Request.Builder().url(tokenUrl()).post(form.build())
        .header("DPoP", Aop.get(CodeArtsCryptoService.class).dpop(key, "POST", tokenUrl())).build();
    Kv payload = read(request);
    CodeArtsTokenResponse token = JsonUtils.parse(JsonUtils.toJson(payload), CodeArtsTokenResponse.class);
    BusinessException.require(token.error == null && token.error_code == null && token.credentials != null, 502,
        "CODEARTS_TOKEN_FAILED", "CodeArts token exchange was rejected");
    CodeArtsTokenResponse.Credentials credentials = token.credentials;
    previous.set("type", "codearts").set("access_key_id", required(credentials.access_key_id))
        .set("secret_access_key", required(credentials.secret_access_key)).set("security_token", required(credentials.security_token))
        .set("last_refresh", Instant.now().toString());
    previous.remove("expired");
    previous.remove("expires_at");
    if (credentials.expiration != null && !credentials.expiration.isBlank()) {
      String expiry = Instant.parse(credentials.expiration).toString();
      previous.set("expired", expiry).set("expires_at", expiry);
    }
    if (token.refresh_token != null && !token.refresh_token.isBlank()) { previous.set("refresh_token", token.refresh_token); }
    return previous;
  }

  private void legacyTicket(CodeArtsLoginSession session, String secret) {
    try {
      String endpoint = EnvUtils.get("proxy.oauth.codearts.ticket-url", "https://snap-access.cn-north-4.myhuaweicloud.com/snap-manager/v1/login/ticket");
      String url = HttpUrl.get(endpoint).newBuilder().addQueryParameter("ticket_id", session.ticketId).addQueryParameter("secret", secret).build().toString();
      while (!session.cancelled && System.currentTimeMillis() < session.expiresAt) {
        try {
          Kv result = read(new Request.Builder().url(url).header("plugin-name", "snap_AIIDE").header("plugin-version", "5.2.0").build());
          Kv data = ProxyJson.object(result.get("credential"));
          Kv alternate = ProxyJson.object(result.get("result"));
          String access = ProxyJson.text(data, "access", alternate.getStr("accessKeyId"));
          String sk = ProxyJson.text(data, "secret", alternate.getStr("secretAccessKey"));
          String st = ProxyJson.text(data, "securitytoken", ProxyJson.text(data, "securityToken", alternate.getStr("securityToken")));
          if (access != null && sk != null && st != null && !access.isBlank() && !sk.isBlank() && !st.isBlank()) {
            Kv credential = Kv.create().set("type", "codearts").set("access_key_id", access).set("secret_access_key", sk).set("security_token", st);
            String expiry = ProxyJson.text(data, "expires_at", ProxyJson.text(data, "expiresAt", ProxyJson.text(alternate, "expiration", alternate.getStr("expiresAt"))));
            if (expiry != null) { credential.set("expired", Instant.parse(expiry).toString()).set("expires_at", expiry); }
            for (String field : List.of("domain_id", "user_id", "user_name")) { if (result.get(field) != null) { credential.set(field, result.get(field)); } }
            // Legacy tickets have no refresh material; reauthorization is required after expiration.
            synchronized (session) {
              if (session.cancelled) { return; }
              session.credential = credential;
            }
            return;
          }
        } catch (Exception transientFailure) { }
        Thread.sleep(1000);
      }
      session.error = "CodeArts ticket authorization expired";
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      session.error = "CodeArts authorization was interrupted";
    } finally { session.close(); }
  }

  public List<Kv> models(Kv credential) throws Exception {
    LinkedHashMap<String, Kv> models = new LinkedHashMap<>();
    Set<String> benefit = ConcurrentHashMap.newKeySet();
    Exception failure = null;
    boolean received = false;
    for (boolean gateway : List.of(true, false)) {
      try {
        String url = gateway ? EnvUtils.get("proxy.oauth.codearts.gateway-models-url", "https://opengw.developer.huaweicloud.com/api/v1/gateway/config")
            : EnvUtils.get("proxy.oauth.codearts.builtin-models-url", "https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin");
        Kv signed = Aop.get(CodeArtsCryptoService.class).sign(credential, "GET", url, new byte[0], Kv.create());
        Request.Builder request = new Request.Builder().url(url);
        signed.forEach((key, value) -> request.header(key.toString(), value.toString()));
        if (!gateway) { request.header("Content-Type", "application/json").header("Agent-Type", "PromptCenter").header("X-Language", "zh-cn"); }
        Kv response = read(request.build());
        Object entries = gateway ? ProxyJson.object(response.get("result")).get("models") : response.get("builtinModels");
        BusinessException.require(entries instanceof List<?>, 502, "CODEARTS_MODEL_RESPONSE", "CodeArts model response omitted its catalog");
        for (Object raw : ProxyJson.list(entries)) {
          Kv model = ProxyJson.object(raw);
          String rawId = model.getStr("model_id");
          if (rawId == null || rawId.isBlank()) { continue; }
          String id = rawId.replaceFirst("-[0-9]{4}$", "");
          if (gateway && id.equals(rawId)) { benefit.add(id); }
          models.putIfAbsent(id, Kv.create().set("id", id).set("object", "model").set("name", ProxyJson.text(model, "model_name", id)).set("metadata", model));
        }
        if (gateway) { benefits.put(benefitKey(credential), Set.copyOf(benefit)); }
        received = true;
      } catch (Exception error) { failure = error; }
    }
    if (!received && failure != null) { throw failure; }
    return new ArrayList<>(models.values());
  }

  public boolean benefit(Kv credential, String model) {
    Set<String> cached = benefits.get(benefitKey(credential));
    return List.of("glm-5.3-flash", "deepseek-v4.1-flash").contains(model) || (cached != null && cached.contains(model));
  }

  private String benefitKey(Kv credential) {
    Kv jwk = ProxyJson.object(credential.get("dpop_private_key_jwk"));
    if (jwk.getStr("x") != null && jwk.getStr("y") != null) { return jwk.getStr("x") + "." + jwk.getStr("y"); }
    return required(credential.getStr("access_key_id"));
  }

  private Kv read(Request request) throws Exception {
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    try (Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 30), request)) {
      if (!response.isSuccessful() || response.body() == null) { throw new OAuthRequestException("CodeArts request failed (HTTP " + response.code() + ")"); }
      byte[] bytes = response.body().byteStream().readNBytes(2 * 1024 * 1024 + 1);
      if (bytes.length > 2 * 1024 * 1024) { throw new OAuthRequestException("CodeArts response exceeds the size limit"); }
      try { return ProxyJson.parse(new String(bytes, StandardCharsets.UTF_8)); }
      catch (RuntimeException error) { throw new OAuthRequestException("CodeArts returned invalid JSON"); }
    }
  }

  private Kv query(String query) {
    Kv values = Kv.create();
    if (query == null || query.length() > 8192) { return values; }
    for (String pair : query.split("&")) {
      String[] parts = pair.split("=", 2);
      if (parts.length == 2) { values.set(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8)); }
    }
    return values;
  }
  private String required(String value) {
    BusinessException.require(value != null && !value.isBlank(), 502, "CODEARTS_CREDENTIAL_INVALID", "CodeArts credential is missing a required field");
    return value;
  }
}
