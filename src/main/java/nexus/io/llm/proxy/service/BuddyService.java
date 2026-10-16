package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.llm.proxy.model.BuddyLoginSession;
import nexus.io.llm.proxy.model.BuddyTokenResponse;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Tencent external-link-v2 authorization, derived from the MIT channel-pack protocol. */
public class BuddyService {
  private record Reply(int status, Kv body) { }

  public static boolean supports(String provider) {
    return "buddy".equals(provider) || "workbuddy".equals(provider) || "workbuddycn".equals(provider);
  }

  private boolean work(String product) {
    return "workbuddy".equals(product) || "workbuddycn".equals(product);
  }

  private String version(String product) {
    return "workbuddycn".equals(product) ? "5.8.1" : "5.5.2";
  }

  public String provider(String provider) {
    BusinessException.require(supports(provider) || "codebuddy".equals(provider), 400, "BUDDY_PROVIDER", "Unsupported Buddy product");
    return "codebuddy".equals(provider) ? "buddy" : provider;
  }

  public String origin(String provider) {
    String product = provider(provider);
    String fallback = switch (product) {
      case "workbuddy" -> "https://www.workbuddy.ai";
      case "workbuddycn" -> "https://www.workbuddy.cn";
      default -> "https://copilot.tencent.com";
    };
    return EnvUtils.get("proxy.oauth." + product + ".origin", fallback).replaceAll("/+$", "");
  }

  public String apiBaseUrl(String provider) { return origin(provider) + "/v2"; }

  public Kv headers(String provider, Kv credential, String model) {
    String product = provider(provider);
    boolean work = work(product);
    boolean cn = "workbuddycn".equals(product);
    String ua = cn ? "WorkBuddy/5.8.1 WorkBuddy/5.8.1" : work ? "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2" : "CodeBuddyIDE/1.106.1";
    if (work && !cn && model != null && List.of("glm-", "hy", "kimi-", "minimax-").stream().anyMatch(model.toLowerCase(Locale.ROOT)::startsWith)) {
      ua = "WorkBuddy/5.5.2 WorkBuddy/5.5.2 CLI/5.5.2";
    }
    Kv headers = Kv.create().set("X-Domain", URI.create(origin(provider)).getHost()).set("User-Agent", ua)
        .set("X-Product-Code", work ? "workbuddy" : "codebuddy").set("X-Product", "SaaS");
    if (credential != null) {
      if (credential.getStr("access_token") != null) { headers.set("Authorization", "Bearer " + credential.getStr("access_token")); }
      if (cn && credential.getStr("domain") != null && !credential.getStr("domain").isBlank()) { headers.set("X-Domain", credential.getStr("domain")); }
      if (cn && credential.getStr("user_id") != null && !credential.getStr("user_id").isBlank()) { headers.set("X-User-Id", credential.getStr("user_id")); }
      String enterprise = credential.getStr("enterprise_id");
      if (enterprise != null && !enterprise.isBlank()) { headers.set("X-Enterprise-Id", enterprise).set("X-Tenant-Id", enterprise); }
    }
    if (model != null) {
      String name = work ? "WorkBuddy" : "CodeBuddy";
      headers.set("X-Product", cn ? "SaaS" : name).set("X-IDE-Name", name).set("X-IDE-Type", name)
          .set("X-IDE-Version", work ? version(product) : "1.106.1").set("X-Agent-Purpose", "conversation").set("Accept", "text/event-stream");
      if (cn) { headers.set("X-Private-Data", "true"); }
    }
    return headers;
  }

  public BuddyLoginSession start(String provider) throws Exception {
    String product = provider(provider);
    Kv headers = headers(product, null, null).set("X-No-Authorization", "true").set("X-No-User-Id", "true")
        .set("X-No-Enterprise-Id", "true").set("X-No-Department-Info", "true");
    String platform = switch (product) {
      case "workbuddy" -> "workbuddy-ai";
      case "workbuddycn" -> "workbuddy";
      default -> "ide";
    };
    Reply reply = request(product, "/v2/plugin/auth/state?platform=" + platform, true, headers);
    Kv data = success(reply);
    BuddyLoginSession session = new BuddyLoginSession();
    session.provider = product;
    session.state = required(data.getStr("state"));
    session.authUrl = required(data.getStr("authUrl"));
    HttpUrl url = HttpUrl.parse(session.authUrl);
    BusinessException.require(url != null && url.isHttps(), 502, "BUDDY_LOGIN_URL", "Buddy returned an invalid login URL");
    if (work(product)) {
      session.authUrl = url.newBuilder().setQueryParameter("version", version(product)).setQueryParameter("loginSessionId", UUID.randomUUID().toString()).build().toString();
    }
    session.expiresAt = System.currentTimeMillis() + 300000;
    session.nextPoll = System.currentTimeMillis() + 1000;
    return session;
  }

  /** Performs one bounded poll; callers schedule the next attempt instead of blocking a request thread. */
  public Kv poll(BuddyLoginSession session) throws Exception {
    synchronized (session) {
      if (session.credential != null) { return session.credential; }
      long now = System.currentTimeMillis();
      BusinessException.require(now < session.expiresAt, 400, "BUDDY_LOGIN_EXPIRED", "Buddy authorization expired");
      if (now < session.nextPoll) { return null; }
      session.nextPoll = now + 1000;
      String query = "?state=" + URLEncoder.encode(session.state, StandardCharsets.UTF_8);
      try {
        if (session.token == null) {
          Reply token = request(session.provider, "/v2/plugin/auth/token" + query, false, headers(session.provider, null, null).set("X-No-Authorization", "true"));
          if (pending(token, 11217)) { return null; }
          session.token = normalize(session.provider, success(token), Kv.create());
        }
        String accountPath = "workbuddycn".equals(session.provider) ? "/v2/plugin/account" : "/v2/plugin/login/account" + query;
        Reply account = request(session.provider, accountPath, false,
            headers(session.provider, session.token, null).set("X-No-User-Id", "true").set("X-No-Enterprise-Id", "true")
                .set("X-Domain", ProxyJson.text(session.token, "domain", URI.create(origin(session.provider)).getHost())));
        if (pending(account, 12151)) { return null; }
        Kv data = success(account);
        Kv credential = Kv.create().set(session.token).set("user_id", required(data.getStr("uid")))
            .set("account_id", data.getStr("uid")).set("account_type", ProxyJson.text(data, "type", "personal"));
        for (String[] field : List.of(new String[] {"nickname", "nickname"}, new String[] {"enterpriseId", "enterprise_id"})) {
          if (data.get(field[0]) != null) { credential.set(field[1], data.get(field[0])); }
        }
        session.credential = credential;
        session.token = null;
        return credential;
      } catch (IOException transientFailure) {
        return null;
      }
    }
  }

  public Kv refresh(String provider, Kv credential) throws Exception {
    String product = provider(provider);
    Kv headers = headers(product, credential, null).set("X-Refresh-Token", required(credential.getStr("refresh_token")))
        .set("X-Auth-Refresh-Source", "workbuddycn".equals(product) ? "plugin" : "ide-main");
    Reply reply = request(provider, "/v2/plugin/auth/token/refresh", true, headers);
    Kv refreshed = normalize(product, success(reply), Kv.create().set(credential));
    if ("workbuddycn".equals(product)) {
      Kv account = success(request(product, "/v2/plugin/account", false, headers(product, refreshed, null)));
      refreshed.set("user_id", required(account.getStr("uid"))).set("account_id", account.getStr("uid"))
          .set("account_type", ProxyJson.text(account, "type", "personal"));
      refreshed.remove("enterprise_id");
      if (account.get("enterpriseId") != null) { refreshed.set("enterprise_id", account.get("enterpriseId")); }
      if (account.get("nickname") != null) { refreshed.set("nickname", account.get("nickname")); }
    }
    return refreshed;
  }

  public Kv normalize(String provider, Kv data, Kv previous) {
    BuddyTokenResponse token = JsonUtils.parse(JsonUtils.toJson(data), BuddyTokenResponse.class);
    previous.set("type", provider(provider)).set("access_token", required(token.accessToken)).set("last_refresh", Instant.now().toString());
    if (token.refreshToken != null && !token.refreshToken.isBlank()) { previous.set("refresh_token", token.refreshToken); }
    if (token.domain != null && !token.domain.isBlank()) { previous.set("domain", token.domain); }
    if (token.scope != null) { previous.set("scope", token.scope); }
    previous.set("token_type", token.tokenType == null ? "Bearer" : token.tokenType);
    previous.remove("expired");
    Instant expiry = expiry(token.expiresAt, token.expiresIn, token.accessToken);
    if (expiry != null) { previous.set("expired", expiry.toString()).set("expires_at", expiry.toString()); }
    else { previous.remove("expires_at"); }
    Instant refreshExpiry = expiry(token.refreshExpiresAt, token.refreshExpiresIn, null);
    if (refreshExpiry != null) { previous.set("refresh_expires_at", refreshExpiry.toString()); }
    return previous;
  }

  private Instant expiry(String absolute, Long relative, String jwt) {
    try {
      if (absolute != null && !absolute.isBlank()) {
        if (absolute.matches("[0-9]+")) {
          long value = Long.parseLong(absolute);
          return value > 1000000000000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        return Instant.parse(absolute);
      }
      if (relative != null && relative > 0) { return Instant.now().plusSeconds(relative); }
      if (jwt != null && jwt.split("\\.").length == 3) {
        Kv claims = ProxyJson.parse(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
        long exp = ProxyJson.number(claims, "exp", 0);
        if (exp > 0) { return Instant.ofEpochSecond(exp); }
      }
    } catch (RuntimeException ignored) { }
    return null;
  }

  public List<Kv> models(String provider, Kv credential) throws Exception {
    LinkedHashMap<String, Kv> models = new LinkedHashMap<>();
    Exception failure = null;
    boolean received = false;
    String enterprise = "workbuddycn".equals(provider) ? ProxyJson.text(credential, "enterprise_id", "personal") : "personal";
    if (enterprise.isBlank()) { enterprise = "personal"; }
    String modelPath = "/console/enterprises/" + URLEncoder.encode(enterprise, StandardCharsets.UTF_8) + "/models";
    for (String path : List.of(modelPath, "/v3/config")) {
      try {
        Kv data = success(request(provider, path, false, headers(provider, credential, null)));
        Set<String> allowed = null;
        if ("workbuddycn".equals(provider)) {
          for (Object raw : ProxyJson.list(data.get("agents"))) {
            Kv agent = ProxyJson.object(raw);
            if ("cli".equals(agent.getStr("name"))) {
              allowed = new HashSet<>();
              for (Object id : ProxyJson.list(agent.get("models"))) {
                if (id instanceof String name) { allowed.add(name); }
              }
              break;
            }
          }
        }
        for (Kv model : parseModels(data)) {
          if (allowed == null || allowed.contains(model.getStr("id"))) { models.putIfAbsent(model.getStr("id"), model); }
        }
        received = true;
      } catch (Exception error) { failure = error; }
    }
    if (!received && failure != null) { throw failure; }
    return new ArrayList<>(models.values());
  }

  public List<Kv> parseModels(Kv data) {
    LinkedHashMap<String, Kv> models = new LinkedHashMap<>();
    Set<String> rejected = new HashSet<>();
    for (Object raw : ProxyJson.list(data.get("models"))) {
      Kv model = ProxyJson.object(raw);
      String id = model.getStr("id");
      if (id == null) { continue; }
      if (!chatModel(id, model)) { rejected.add(id); continue; }
      models.put(id, Kv.create().set("id", id).set("object", "model").set("name", ProxyJson.text(model, "name", id)).set("metadata", model));
    }
    for (Object raw : ProxyJson.list(data.get("agents"))) {
      Kv agent = ProxyJson.object(raw);
      if (!List.of("cli", "craft", "ask", "plan").contains(ProxyJson.text(agent, "name", ""))) { continue; }
      for (Object value : ProxyJson.list(agent.get("models"))) {
        if (value instanceof String id && !rejected.contains(id) && chatModel(id, Kv.create())) { models.putIfAbsent(id, Kv.create().set("id", id).set("object", "model").set("name", id)); }
      }
    }
    return new ArrayList<>(models.values());
  }

  private boolean chatModel(String id, Kv model) {
    long output = ProxyJson.number(model, "maxOutputTokens", 0);
    return !id.isBlank() && !List.of("auto", "default").contains(id) && !id.startsWith("nes-") && !id.startsWith("completion-") && !id.startsWith("codewise-")
        && !Boolean.TRUE.equals(model.get("supportsExtra")) && !(output > 0 && output <= 256) && !ProxyJson.list(model.get("tags")).contains("text-to-image");
  }

  private boolean pending(Reply reply, int code) {
    return ProxyJson.number(reply.body, "code", 0) == code || (reply.status == 200 && !reply.body.containsKey("data") && ProxyJson.number(reply.body, "code", 0) == 0)
        || (reply.status == 200 && reply.body.containsKey("data") && reply.body.get("data") == null && ProxyJson.number(reply.body, "code", 0) == 0);
  }

  private Kv success(Reply reply) {
    BusinessException.require(reply.status >= 200 && reply.status < 300 && ProxyJson.number(reply.body, "code", 0) == 0 && reply.body.get("data") != null,
        502, "BUDDY_REQUEST_FAILED", "Buddy upstream request failed (HTTP " + reply.status + ")");
    return ProxyJson.object(reply.body.get("data"));
  }

  private String required(String value) {
    BusinessException.require(value != null && !value.isBlank(), 502, "BUDDY_RESPONSE_INVALID", "Buddy response is missing a required field");
    return value;
  }

  private Reply request(String provider, String path, boolean post, Kv headers) throws Exception {
    Request.Builder request = new Request.Builder().url(origin(provider) + path).header("Accept", "application/json");
    headers.forEach((name, value) -> request.header(name.toString(), value.toString()));
    if (post) {
      if ("workbuddycn".equals(provider)) { request.post(RequestBody.create("{}", MediaType.get("application/json"))); }
      else { request.post(RequestBody.create(new byte[0], null)); }
    }
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    try (Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 20), request.build())) {
      if (response.body() == null) { throw new OAuthRequestException("Buddy returned an empty response"); }
      byte[] bytes = response.body().byteStream().readNBytes(1024 * 1024 + 1);
      if (bytes.length > 1024 * 1024) { throw new OAuthRequestException("Buddy response exceeds the size limit"); }
      Kv body;
      try { body = ProxyJson.parse(new String(bytes, StandardCharsets.UTF_8)); }
      catch (RuntimeException error) { throw new OAuthRequestException("Buddy returned invalid JSON"); }
      return new Reply(response.code(), body);
    }
  }
}
