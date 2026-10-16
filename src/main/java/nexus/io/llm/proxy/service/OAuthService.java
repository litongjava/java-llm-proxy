package nexus.io.llm.proxy.service;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.OAuthCallbackInput;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OAuthService {
  private static final Logger LOG = LoggerFactory.getLogger(OAuthService.class);
  private final ConcurrentHashMap<String, OAuthSession> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, HttpServer> callbacks = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "oauth-session-cleaner"); thread.setDaemon(true); return thread;
  });

  public OAuthService() {
    cleaner.scheduleAtFixedRate(this::expireSessions, 60, 60, TimeUnit.SECONDS);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      cleaner.shutdownNow();
      sessions.values().forEach(session -> { if (session.codearts != null) { session.codearts.close(); } });
      callbacks.values().forEach(server -> server.stop(0));
      callbackExecutor.close();
    }, "oauth-shutdown"));
  }

  private synchronized void expireSessions() {
    long now = System.currentTimeMillis();
    sessions.values().stream().filter(session -> session.expiresAt < now && session.codearts != null).forEach(session -> session.codearts.close());
    sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    for (Integer port : callbacks.keySet()) {
      boolean active = sessions.values().stream().anyMatch(session -> session.spec != null && session.spec.redirectUri != null
          && URI.create(session.spec.redirectUri).getPort() == port);
      if (!active) { HttpServer server = callbacks.remove(port); if (server != null) { server.stop(0); } }
    }
  }

  public OAuthStartResult start(String provider) throws Exception {
    ManagementParameterValidator.identifier(provider);
    long now = System.currentTimeMillis();
    expireSessions();
    BusinessException.require(sessions.size() < 256, 429, "TOO_MANY_SESSIONS", "Too many OAuth sessions");
    NativePlugin plugin = Aop.get(PluginService.class).provider(provider, false);
    if (plugin != null) {
      Kv started = plugin.call("auth.login.start", Kv.create().set("Provider", provider).set("BaseURL", "http://127.0.0.1:" + EnvUtils.getInt("server.port", 8318))
          .set("Host", Kv.create()).set("Metadata", Kv.create()));
      OAuthSession session = new OAuthSession();
      session.state = ProxyJson.text(started, "State", randomToken());
      BusinessException.require(!sessions.containsKey(session.state), 409, "STATE_CONFLICT", "Plugin returned an existing OAuth state");
      session.pluginId = plugin.id;
      session.pluginProvider = provider;
      session.pluginState = started.getStr("State");
      session.pluginMetadata = ProxyJson.object(started.get("Metadata"));
      session.expiresAt = now + 900000;
      String expires = started.getStr("ExpiresAt");
      if (expires != null && !expires.startsWith("0001")) { session.expiresAt = Math.min(session.expiresAt, Instant.parse(expires).toEpochMilli()); }
      sessions.put(session.state, session);
      OAuthStartResult result = new OAuthStartResult();
      result.state = session.state;
      result.url = started.getStr("URL");
      result.flow = "plugin";
      result.expires_in = (int) ((session.expiresAt - now) / 1000);
      result.user_code = session.pluginMetadata.getStr("user_code");
      return result;
    }
    OAuthProviderSpec spec = Aop.get(OAuthProviderService.class).get(provider);
    OAuthSession session = new OAuthSession();
    session.spec = spec;
    session.state = randomToken();
    session.verifier = randomToken();
    session.expiresAt = now + 15 * 60 * 1000;
    OAuthStartResult result = new OAuthStartResult();
    result.state = session.state;
    result.expires_in = 900;
    if ("qoder".equals(provider) || "qodercn".equals(provider)) {
      Aop.get(QoderService.class).start(session, result);
    } else if ("minimax".equals(provider)) {
      Aop.get(MiniMaxService.class).start(session, result);
    } else if ("codearts".equals(provider)) {
      session.codearts = Aop.get(CodeArtsService.class).start();
      session.expiresAt = session.codearts.expiresAt;
      result.url = session.codearts.authUrl;
      result.flow = "authorization_code";
      result.expires_in = (int) ((session.expiresAt - now) / 1000);
    } else if ("raccoon".equals(provider)) {
      Aop.get(RaccoonService.class).start(session, result);
    } else if ("zcode".equals(provider)) {
      Aop.get(ZCodeService.class).start(session, result);
    } else if ("trae".equals(provider)) {
      startCallbackServer(spec.redirectUri);
      result.url = Aop.get(TraeService.class).initialize(session);
      result.flow = "authorization_code";
    } else if ("buddy".equals(provider) || "workbuddy".equals(provider)) {
      session.buddy = Aop.get(BuddyService.class).start(provider);
      session.expiresAt = session.buddy.expiresAt;
      result.url = session.buddy.authUrl;
      result.flow = "device";
      result.expires_in = (int) ((session.expiresAt - now) / 1000);
    } else if ("lobsterai".equals(provider)) {
      startCallbackServer(spec.redirectUri);
      result.url = Aop.get(LobsterAiService.class).initialize(session);
      result.flow = "authorization_code";
    } else if (spec.deviceUrl != null) {
      Kv parameters = Kv.create().set("client_id", spec.clientId).set("scope", spec.scope);
      if ("cline".equals(provider)) {
        parameters.remove("scope");
      }
      if ("kimi".equals(provider) || "kimi-ai".equals(provider)) {
        parameters.set("code_challenge", challenge(session.verifier)).set("code_challenge_method", "S256");
      }
      Kv response = post(spec.deviceUrl, parameters, false, null);
      requireTokenResponse(response);
      session.deviceCode = response.getStr("device_code");
      BusinessException.require(session.deviceCode != null, 502, "INVALID_DEVICE_RESPONSE", "Device authorization did not return a code");
      result.url = ProxyJson.text(response, "verification_uri_complete",
          ProxyJson.text(response, "verification_uri", response.getStr("verification_url")));
      result.user_code = response.getStr("user_code");
      result.flow = "device";
      result.expires_in = (int) Math.min(1800, ProxyJson.number(response, "expires_in", 900));
      session.expiresAt = now + result.expires_in * 1000L;
      session.interval = (int) Math.min(60, Math.max(1, ProxyJson.number(response, "interval", 5)));
      if ("cline".equals(provider)) {
        BusinessException.require(session.deviceCode != null && !session.deviceCode.isBlank()
            && result.user_code != null && !result.user_code.isBlank() && result.url != null && !result.url.isBlank(),
            502, "INVALID_DEVICE_RESPONSE", "Cline device authorization returned incomplete fields");
        session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
      }
    } else {
      startCallbackServer(spec.redirectUri);
      Kv query = Kv.create().set("client_id", spec.clientId).set("response_type", "code")
          .set("redirect_uri", spec.redirectUri).set("scope", spec.scope).set("state", session.state)
          .set("code_challenge", challenge(session.verifier)).set("code_challenge_method", "S256");
      if ("codex".equals(provider)) {
        query.set("prompt", "login").set("id_token_add_organizations", true).set("codex_cli_simplified_flow", true);
      } else if ("claude".equals(provider)) {
        query.set("code", "true");
      } else if ("antigravity".equals(provider)) {
        query.set("access_type", "offline").set("prompt", "consent");
      } else if ("devin".equals(provider)) {
        query.remove("client_id");
        query.remove("response_type");
        query.remove("scope");
        query.set("prompt", "select_account");
      }
      result.url = spec.authorizeUrl + "?" + encode(query);
      result.flow = "authorization_code";
    }
    sessions.put(session.state, session);
    return result;
  }

  public Kv status(String state) throws Exception {
    OAuthSession session = session(state);
    synchronized (session) {
      if ("wait".equals(session.status) && session.codearts != null) {
        try {
          Kv credential = Aop.get(CodeArtsService.class).poll(session.codearts);
          if (credential != null) { complete(session, credential); session.codearts.close(); }
        } catch (Exception error) {
          session.status = "error";
          session.error = OAuthFailureUtils.message("CodeArts login", error);
          session.codearts.close();
        }
      }
      if ("wait".equals(session.status) && session.buddy != null) {
        try {
          Kv token = Aop.get(BuddyService.class).poll(session.buddy);
          if (token != null) { complete(session, token); session.buddy = null; }
        } catch (Exception error) {
          session.status = "error";
          session.error = OAuthFailureUtils.message("Buddy login", error);
          session.buddy = null;
        }
      }
      if ("wait".equals(session.status) && session.qoder != null) {
        Kv token = Aop.get(QoderService.class).poll(session);
        if (token != null) {
          try {
            complete(session, token);
            session.qoder = null;
          } catch (Exception error) {
            session.status = "error";
            session.error = OAuthFailureUtils.message("Qoder account setup", error);
            session.qoder = null;
            session.verifier = null;
          }
        }
      }
      if ("wait".equals(session.status) && session.spec != null && "minimax".equals(session.spec.provider)) {
        Kv token = Aop.get(MiniMaxService.class).poll(session);
        if (token != null) { complete(session, token); }
      }
      if ("wait".equals(session.status) && session.spec != null && "raccoon".equals(session.spec.provider)) {
        Kv token = Aop.get(RaccoonService.class).poll(session);
        if (token != null) { complete(session, token); }
      }
      if ("wait".equals(session.status) && session.spec != null && "zcode".equals(session.spec.provider)) {
        Kv token = Aop.get(ZCodeService.class).poll(session);
        if (token != null) { complete(session, token); }
      }
      if ("wait".equals(session.status) && session.pluginId != null) {
        Kv result = Aop.get(PluginService.class).require(session.pluginId).call("auth.login.poll",
            Kv.create().set("Provider", session.pluginProvider).set("State", session.pluginState).set("Metadata", session.pluginMetadata).set("Host", Kv.create()));
        String status = result.getStr("Status");
        if ("success".equals(status)) {
          for (Object auth : ProxyJson.list(result.get("Auths"))) { savePluginAuth(ProxyJson.object(auth)); }
          Kv auth = ProxyJson.object(result.get("Auth"));
          if (!auth.isEmpty()) { savePluginAuth(auth); }
          session.status = "ok";
        } else if ("error".equals(status)) {
          session.status = "error";
          session.error = result.getStr("Message");
        }
      }
      if ("wait".equals(session.status) && session.deviceCode != null && System.currentTimeMillis() >= session.nextPoll
          && "cline".equals(session.spec.provider)) {
        pollCline(session);
      }
      if ("wait".equals(session.status) && session.deviceCode != null && !"cline".equals(session.spec.provider)
          && !"minimax".equals(session.spec.provider)
          && !"raccoon".equals(session.spec.provider)
          && !"zcode".equals(session.spec.provider)
          && System.currentTimeMillis() >= session.nextPoll) {
        session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
        Kv parameters = Kv.create().set("client_id", session.spec.clientId).set("device_code", session.deviceCode)
            .set("grant_type", "urn:ietf:params:oauth:grant-type:device_code").set("code_verifier", session.verifier);
        Kv result = post(session.spec.tokenUrl, parameters, session.spec.jsonToken, null);
        String error = result.getStr("error");
        if ("slow_down".equals(error)) {
          session.interval += 5;
        } else if (error != null && !"authorization_pending".equals(error)) {
          session.status = "error";
          session.error = error;
        } else if (result.getStr("access_token") != null) {
          complete(session, result);
        }
      }
      Kv result = Kv.create().set("status", session.status);
      if (session.error != null) {
        result.set("error", session.error);
      }
      return result;
    }
  }

  private void pollCline(OAuthSession session) {
    session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
    try {
      Kv result = post(session.spec.tokenUrl, Kv.create().set("client_id", session.spec.clientId)
          .set("device_code", session.deviceCode).set("grant_type", "urn:ietf:params:oauth:grant-type:device_code"), false, null);
      String error = result.getStr("error");
      if ("authorization_pending".equals(error)) {
        return;
      }
      long status = ProxyJson.number(result, "http_status", 200);
      if ("slow_down".equals(error) || status == 429 || status >= 500) {
        session.interval = Math.min(60, session.interval + 5);
        session.nextPoll = System.currentTimeMillis() + session.interval * 1000L;
        return;
      }
      requireTokenResponse(result);
      Kv token = Aop.get(ClineService.class).register(result);
      complete(session, token);
    } catch (Exception error) {
      session.status = "error";
      session.error = OAuthFailureUtils.message("Cline device login", error);
      session.deviceCode = null;
      session.verifier = null;
    }
  }

  public Kv cancel(String state) {
    OAuthSession session = session(state);
    synchronized (session) {
      BusinessException.require(!"ok".equals(session.status), 409, "COMPLETED", "OAuth session already completed");
      session.status = "error";
      session.error = "cancelled";
      if (session.codearts != null) { session.codearts.close(); session.codearts = null; }
      session.buddy = null;
      session.qoder = null;
      session.verifier = null;
      session.deviceCode = null;
      return Kv.create().set("status", "ok");
    }
  }

  // This transport must never inherit an upstream proxy or follow callback redirects.
  private static final OkHttpClient CALLBACK_CLIENT = new OkHttpClient.Builder()
      .proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
      .connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
      .callTimeout(65, TimeUnit.SECONDS).build();

  public Kv forwardCallback(OAuthCallbackInput input) throws Exception {
    BusinessException.require(input != null && input.redirect_url != null && !input.redirect_url.isBlank()
        && input.redirect_url.length() <= 32768, 400, "INVALID_CALLBACK", "A complete callback URL is required");
    URI supplied;
    try { supplied = URI.create(input.redirect_url.trim()); }
    catch (IllegalArgumentException error) { throw new BusinessException(400, "INVALID_CALLBACK", "Invalid callback URL"); }
    BusinessException.require("http".equals(supplied.getScheme()) && supplied.getUserInfo() == null
        && supplied.getFragment() == null && ("127.0.0.1".equals(supplied.getHost()) || "localhost".equals(supplied.getHost())),
        400, "INVALID_CALLBACK", "Callback URL must use HTTP loopback");
    Kv parameters = query(supplied.getRawQuery());
    OAuthSession pending = parameters.getStr("state") == null && parameters.getStr("login_trace_id") != null
        ? traeSession(parameters.getStr("login_trace_id")) : session(parameters.getStr("state"));
    synchronized (pending) {
      BusinessException.require("wait".equals(pending.status), 409, "CONSUMED", "OAuth session is no longer waiting");
      // Plugins own their callback transports. Deliver to the existing session handler,
      // which passes the URL to that plugin without making an arbitrary HTTP request.
      if (pending.pluginId != null) {
        OAuthCallbackInput forwarded = new OAuthCallbackInput();
        forwarded.redirect_url = supplied.toString();
        return callback(forwarded);
      }
      BusinessException.require(pending.spec != null && pending.spec.redirectUri != null && pending.deviceCode == null
          && pending.qoder == null, 400, "INVALID_CALLBACK", "This session does not use a local HTTP callback");
      URI expected = URI.create(pending.spec.redirectUri);
      BusinessException.require("http".equals(expected.getScheme())
          && ("127.0.0.1".equals(expected.getHost()) || "localhost".equals(expected.getHost()))
          && expected.getPort() == supplied.getPort() && expected.getRawPath().equals(supplied.getRawPath())
          && callbacks.containsKey(expected.getPort()), 400, "INVALID_CALLBACK", "URL does not match the waiting OAuth callback");
    }
    // Do not hold the session lock while the HTTP callback acquires it.
    String target = "http://127.0.0.1:" + supplied.getPort() + supplied.getRawPath() + "?" + supplied.getRawQuery();
    try (Response response = CALLBACK_CLIENT.newCall(new Request.Builder().url(target).get().build()).execute()) {
      BusinessException.require(response.isSuccessful(), 400, "CALLBACK_FAILED", "OAuth callback failed; check the authorization status");
      return Kv.create().set("status", "ok").set("http_status", response.code());
    } catch (java.io.IOException error) {
      throw new BusinessException(502, "CALLBACK_UNAVAILABLE", "Local callback could not be reached or timed out; check authorization status before retrying");
    }
  }

  public Kv callback(OAuthCallbackInput input) throws Exception {
    BusinessException.require(input != null, 400, "INVALID_CALLBACK", "OAuth callback is required");
    Kv callbackParameters = Kv.create();
    if (input.redirect_url != null && !input.redirect_url.isBlank()) {
      URI redirect = URI.create(input.redirect_url);
      Kv query = query(redirect.getRawQuery());
      callbackParameters = query;
      input.code = query.getStr("code");
      input.state = query.getStr("state");
      input.error = query.getStr("error");
      if (input.state == null && query.getStr("login_trace_id") != null) {
        input.state = traeSession(query.getStr("login_trace_id")).state;
      }
    }
    OAuthSession session = session(input.state);
    synchronized (session) {
      if (session.pluginId != null) {
        BusinessException.require("wait".equals(session.status), 409, "CONSUMED", "OAuth session cannot accept this callback");
        session.pluginMetadata.set("code", input.code).set("redirect_url", input.redirect_url).set("error", input.error);
        return status(session.state);
      }
      BusinessException.require(input.provider == null || input.provider.equals(session.spec.provider), 400, "INVALID_STATE", "OAuth provider mismatch");
      BusinessException.require("wait".equals(session.status) && session.deviceCode == null && session.qoder == null,
          409, "CONSUMED", "OAuth session cannot accept this callback");
      if (input.error != null) {
        session.status = "error";
        session.error = input.error;
        return Kv.create().set("status", "error").set("error", input.error);
      }
      if ("trae".equals(session.spec.provider)) {
        session.status = "exchanging";
        try {
          complete(session, Aop.get(TraeService.class).exchange(callbackParameters, session));
          return Kv.create().set("status", "ok");
        } catch (Exception error) {
          session.status = "error";
          session.error = OAuthFailureUtils.message("TRAE login", error);
          throw new OAuthRequestException(session.error);
        }
      }
      BusinessException.require(input.code != null && !input.code.isBlank(), 400, "MISSING_CODE", "OAuth code is required");
      Kv parameters = Kv.create().set("grant_type", "authorization_code").set("client_id", session.spec.clientId)
          .set("code", input.code.split("#", 2)[0]).set("redirect_uri", session.spec.redirectUri)
          .set("code_verifier", session.verifier);
      if (session.spec.clientSecret != null) {
        parameters.set("client_secret", session.spec.clientSecret);
      }
      if ("claude".equals(session.spec.provider)) {
        parameters.set("state", session.state);
      }
      if ("devin".equals(session.spec.provider)) {
        parameters = Kv.create().set("code", input.code).set("code_verifier", session.verifier);
      }
      session.status = "exchanging";
      String stage = "Token exchange";
      try {
        Kv result = "lobsterai".equals(session.spec.provider)
            ? Aop.get(LobsterAiService.class).exchange(input.code, session)
            : post(session.spec.tokenUrl, parameters, session.spec.jsonToken, null);
        requireTokenResponse(result);
        if ("devin".equals(session.spec.provider)) {
          String token = result.getStr("token");
          if (token != null && !token.startsWith("devin-session-token$")) {
            token = "devin-session-token$" + token;
          }
          result.set("access_token", token);
        }
        stage = "Account setup";
        complete(session, result);
        return Kv.create().set("status", "ok");
      } catch (Exception error) {
        session.status = "error";
        session.error = OAuthFailureUtils.message(stage, error);
        session.verifier = null;
        LOG.warn("OAuth login failed: provider={}, stage={}, type={}, reason={}",
            session.spec.provider, stage, error.getClass().getSimpleName(), session.error);
        throw new OAuthRequestException(session.error);
      }
    }
  }

  public Kv refreshCredential(Kv credential) throws Exception {
    String provider = credential.getStr("type");
    if ("qoder".equals(provider) || "qodercn".equals(provider)) { return Aop.get(QoderService.class).refresh(provider, credential); }
    if ("minimax".equals(provider)) { return Aop.get(MiniMaxService.class).refresh(credential); }
    if ("buddy".equals(provider) || "workbuddy".equals(provider)) { return Aop.get(BuddyService.class).refresh(provider, credential); }
    if ("lobsterai".equals(provider)) { return Aop.get(LobsterAiService.class).refresh(credential); }
    if ("trae".equals(provider)) { return Aop.get(TraeService.class).refresh(credential); }
    if ("raccoon".equals(provider)) { return Aop.get(RaccoonService.class).refresh(credential); }
    if ("codearts".equals(provider)) { return Aop.get(CodeArtsService.class).refresh(credential); }
    if ("loomy".equals(provider)) { return Aop.get(LoomyService.class).refresh(credential); }
    if ("zcode".equals(provider)) { return Aop.get(ZCodeService.class).refresh(credential); }
    NativePlugin plugin = Aop.get(PluginService.class).provider(provider, false);
    if (plugin != null) {
      Kv result = plugin.call("auth.refresh", Kv.create().set("AuthProvider", provider).set("StorageJSON", PluginHostCallbacks.encode(JsonUtils.toJson(credential).getBytes(StandardCharsets.UTF_8)))
          .set("Metadata", credential).set("Attributes", Kv.create()).set("Host", Kv.create()));
      return pluginCredential(ProxyJson.object(result.get("Auth")));
    }
    if ("cline".equals(provider)) {
      return Aop.get(ClineService.class).refresh(credential);
    }
    if ("vertex".equals(provider)) {
      return Aop.get(VertexCredentialService.class).refresh(credential);
    }
    if ("meta".equals(provider) && credential.getStr("device_access_token") != null && credential.getStr("refresh_token") == null) {
      Kv key = post(EnvUtils.get("proxy.oauth.meta.key-url", "https://api.meta.ai/muse-code/key"), Kv.create(), true, credential.getStr("device_access_token"));
      requireTokenResponse(key);
      BusinessException.require(key.getStr("api_key") != null, 502, "NO_API_KEY", "Meta did not return an API key");
      credential.set("access_token", key.get("api_key")).set("api_key", key.get("api_key"))
          .set("expired", Instant.now().plusSeconds(ProxyJson.number(key, "expires_in", 3600)).toString()).set("last_refresh", Instant.now().toString());
      return credential;
    }
    String refreshToken = credential.getStr("refresh_token");
    BusinessException.require(refreshToken != null && !refreshToken.isBlank(), 400, "NO_REFRESH_TOKEN", "Credential has no refresh token");
    OAuthProviderSpec spec = Aop.get(OAuthProviderService.class).get(provider);
    Kv parameters = Kv.create().set("grant_type", "refresh_token").set("client_id", spec.clientId).set("refresh_token", refreshToken);
    if (spec.clientSecret != null) {
      parameters.set("client_secret", spec.clientSecret);
    }
    Kv result = post(spec.tokenUrl, parameters, spec.jsonToken, null);
    requireTokenResponse(result);
    BusinessException.require(result.getStr("access_token") != null, 502, "NO_ACCESS_TOKEN", "Token endpoint returned no access token");
    mergeToken(credential, result);
    if ("meta".equals(provider)) {
      credential.set("device_access_token", result.get("access_token"));
      Kv key = post(EnvUtils.get("proxy.oauth.meta.key-url", "https://api.meta.ai/muse-code/key"), Kv.create(), true, result.getStr("access_token"));
      requireTokenResponse(key);
      BusinessException.require(key.getStr("api_key") != null, 502, "NO_API_KEY", "Meta did not return an API key");
      credential.set("access_token", key.get("api_key")).set("api_key", key.get("api_key"));
    }
    return credential;
  }

  private void complete(OAuthSession session, Kv token) throws Exception {
    BusinessException.require(token.getStr("access_token") != null || ("codearts".equals(session.spec.provider)
        && token.getStr("access_key_id") != null && token.getStr("secret_access_key") != null && token.getStr("security_token") != null),
        502, "NO_ACCESS_TOKEN", "Token endpoint returned no access credential");
    Kv credential = Kv.create().set("type", session.spec.provider);
    mergeToken(credential, token);
    String idToken = token.getStr("id_token");
    if (idToken != null) {
      String[] segments = idToken.split("\\.");
      if (segments.length == 3) {
        try {
          Kv claims = ProxyJson.parse(new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8));
          credential.set("email", claims.get("email"));
          credential.set("account_id", ProxyJson.object(claims.get("https://api.openai.com/auth")).get("chatgpt_account_id"));
        } catch (RuntimeException ignored) {
          // Claims are display metadata; authorization only uses the token endpoint result.
        }
      }
    }
    if ("meta".equals(session.spec.provider)) {
      String url = EnvUtils.get("proxy.oauth.meta.key-url", "https://api.meta.ai/muse-code/key");
      Kv key = post(url, Kv.create(), true, token.getStr("access_token"));
      requireTokenResponse(key);
      BusinessException.require(key.getStr("api_key") != null, 502, "NO_API_KEY", "Meta did not return an API key");
      credential.set("device_access_token", credential.get("access_token"));
      credential.set("access_token", key.get("api_key")).set("api_key", key.get("api_key"))
          .set("base_url", key.get("base_url")).set("email", key.get("user_email"));
    }
    Aop.get(OAuthAccountService.class).enrich(credential);
    session.credentialName = Aop.get(CredentialService.class).save(null, credential);
    session.status = "ok";
    session.verifier = null;
    session.deviceCode = null;
  }

  private void mergeToken(Kv credential, Kv token) {
    token.forEach((key, value) -> {
      if (value != null && !"http_status".equals(key) && !"error".equals(key)) {
        credential.put(key, value);
      }
    });
    long expires = ProxyJson.number(token, "expires_in", 0);
    if (expires > 0) {
      credential.set("expired", Instant.now().plusSeconds(expires).toString());
    }
    credential.set("last_refresh", Instant.now().toString());
  }

  private Kv pluginCredential(Kv auth) {
    Kv credential = ProxyJson.parse(PluginHostCallbacks.text(auth.get("StorageJSON")));
    if (auth.get("Provider") != null) { credential.set("type", auth.get("Provider")); }
    return credential;
  }

  public String savePluginAuth(Kv auth) throws Exception {
    Kv credential = pluginCredential(auth);
    return Aop.get(CredentialService.class).save(auth.getStr("FileName"), credential);
  }

  private OAuthSession session(String state) {
    OAuthSession session = state == null ? null : sessions.get(state);
    BusinessException.require(session != null, 404, "INVALID_STATE", "Unknown OAuth state");
    BusinessException.require(System.currentTimeMillis() < session.expiresAt, 410, "EXPIRED", "OAuth session expired");
    return session;
  }

  private OAuthSession traeSession(String trace) {
    OAuthSession matched = sessions.values().stream().filter(candidate -> candidate.spec != null
        && "trae".equals(candidate.spec.provider) && candidate.pluginMetadata != null
        && trace.equals(candidate.pluginMetadata.getStr("login_trace_id"))).findFirst().orElse(null);
    BusinessException.require(matched != null, 400, "INVALID_STATE", "Unknown TRAE login trace");
    return session(matched.state);
  }

  private OkHttpClient client() {
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    return transport.client(transport.configuredProxy(), 30);
  }

  public Kv getJson(String url, String bearer) throws Exception {
    ManagementParameterValidator.httpUrl(url);
    Request.Builder builder = new Request.Builder().url(url).get();
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    try (Response response = UniChatClient.execute(client(), builder.build())) {
      if (!response.isSuccessful()) {
        throw new OAuthRequestException("OAuth profile request failed (HTTP " + response.code() + "). Check account access and the outbound proxy.");
      }
      return ProxyJson.parse(response.body().string());
    }
  }

  public Kv post(String url, Kv parameters, boolean json, String bearer) throws Exception {
    return post(url, parameters, json, bearer, Kv.create());
  }

  public Kv post(String url, Kv parameters, boolean json, String bearer, Kv headers) throws Exception {
    ManagementParameterValidator.httpUrl(url);
    RequestBody body;
    if (json) {
      body = RequestBody.create(JsonUtils.toJson(parameters), MediaType.get("application/json"));
    } else {
      FormBody.Builder form = new FormBody.Builder();
      parameters.forEach((key, value) -> {
        if (value != null) {
          form.add(key.toString(), value.toString());
        }
      });
      body = form.build();
    }
    Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "LLMProxy/1.0").post(body);
    headers.forEach((name, value) -> builder.header(name.toString(), value.toString()));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    try (Response response = UniChatClient.execute(client(), builder.build())) {
      Kv result;
      try {
        result = ProxyJson.parse(response.body().string());
      } catch (RuntimeException error) {
        throw new OAuthRequestException("OAuth endpoint returned invalid JSON (HTTP " + response.code() + "). Check the outbound proxy.");
      }
      result.set("http_status", response.code());
      return result;
    }
  }

  private void requireTokenResponse(Kv response) {
    OAuthFailureUtils.requireSuccess(response, "OAuth token request");
  }

  private synchronized void startCallbackServer(String redirectUri) throws Exception {
    URI uri = URI.create(redirectUri);
    BusinessException.require("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()), 400, "INVALID_REDIRECT", "OAuth callback must use loopback");
    if (callbacks.containsKey(uri.getPort())) {
      return;
    }
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", uri.getPort()), 0);
    server.setExecutor(callbackExecutor);
    server.createContext("/", exchange -> {
      int status = 200;
      String message = "Authorization completed. You may close this window.";
      try {
        BusinessException.require("GET".equals(exchange.getRequestMethod()), 405, "METHOD_NOT_ALLOWED", "GET is required");
        Kv parameters = query(exchange.getRequestURI().getRawQuery());
        OAuthSession pending = parameters.getStr("state") == null && parameters.getStr("login_trace_id") != null
            ? traeSession(parameters.getStr("login_trace_id")) : session(parameters.getStr("state"));
        URI expected = URI.create(pending.spec.redirectUri);
        BusinessException.require(expected.getPort() == uri.getPort() && expected.getPath().equals(exchange.getRequestURI().getPath()),
            400, "CALLBACK_PATH", "Callback path does not match this OAuth session");
        OAuthCallbackInput input = new OAuthCallbackInput();
        input.state = parameters.getStr("state");
        input.code = parameters.getStr("code");
        input.error = parameters.getStr("error");
        if ("trae".equals(pending.spec.provider)) {
          input.redirect_url = pending.spec.redirectUri + "?" + exchange.getRequestURI().getRawQuery();
        }
        Kv result = callback(input);
        if (!"ok".equals(result.getStr("status"))) {
          status = 400;
          message = "Authorization was declined. Return to the desktop application.";
        }
      } catch (Exception error) {
        status = 400;
        message = "Authorization could not be completed. Return to the desktop application.";
      }
      byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, bytes.length);
      try (exchange) {
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    callbacks.put(uri.getPort(), server);
  }

  public static Kv query(String raw) {
    Kv result = Kv.create();
    if (raw != null) {
      for (String item : raw.split("&")) {
        String[] pair = item.split("=", 2);
        result.set(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
      }
    }
    return result;
  }

  private String encode(Kv parameters) {
    StringBuilder query = new StringBuilder();
    parameters.forEach((key, value) -> {
      if (value != null) {
        if (!query.isEmpty()) {
          query.append('&');
        }
        query.append(URLEncoder.encode(key.toString(), StandardCharsets.UTF_8)).append('=')
            .append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
      }
    });
    return query.toString();
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String challenge(String verifier) throws Exception {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  }
}
