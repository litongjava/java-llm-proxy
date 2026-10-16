package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.BuddyLoginSession;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class BuddyServiceTest {
  @Test
  public void performsThreeProductLoginPhasesRefreshAndMergedDiscovery() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger tokens = new AtomicInteger();
    AtomicInteger accounts = new AtomicInteger();
    AtomicReference<String> platform = new AtomicReference<>();
    AtomicReference<String> refreshHeader = new AtomicReference<>();
    AtomicReference<String> refreshSource = new AtomicReference<>();
    AtomicReference<String> activeProduct = new AtomicReference<>();
    AtomicReference<String> modelPath = new AtomicReference<>();
    Path config = Files.createTempFile("buddy-test-", ".yaml");
    Files.writeString(config, "proxy-url: direct\nplugins: {enabled: false}\n");
    String previousConfig = EnvUtils.get("proxy.config");
    EnvUtils.set("proxy.config", config.toString());
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      if ("POST".equals(exchange.getRequestMethod())) {
        String posted = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("workbuddycn".equals(activeProduct.get())) {
          assertEquals("{}", posted);
          assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/json"));
        } else { assertEquals("", posted); }
      }
      Kv body = Kv.create();
      if (path.endsWith("/auth/state")) {
        platform.set(exchange.getRequestURI().getQuery());
        body.set("data", Kv.create().set("state", "upstream-state").set("authUrl", "https://www.workbuddy.ai/login?state=upstream-state"));
      } else if (path.endsWith("/auth/token")) {
        if (tokens.getAndIncrement() == 0) { body.set("code", 11217); }
        else { body.set("data", Kv.create().set("accessToken", "access-one").set("refreshToken", "refresh-one").set("expiresIn", 3600)); }
      } else if (path.endsWith("/account")) {
        if ("workbuddycn".equals(activeProduct.get())) {
          assertEquals("/v2/plugin/account", path);
          assertNull(exchange.getRequestURI().getQuery());
        } else { assertEquals("/v2/plugin/login/account", path); }
        if (accounts.getAndIncrement() == 0) { body.set("code", 12151); }
        else { body.set("data", Kv.create().set("uid", "user-one").set("enterpriseId", "tenant-one").set("nickname", "Test")); }
      } else if (path.endsWith("/token/refresh")) {
        assertEquals("workbuddycn".equals(activeProduct.get()) ? "user-one" : null, exchange.getRequestHeaders().getFirst("X-User-Id"));
        refreshHeader.set(exchange.getRequestHeaders().getFirst("X-Refresh-Token"));
        refreshSource.set(exchange.getRequestHeaders().getFirst("X-Auth-Refresh-Source"));
        body.set("data", Kv.create().set("accessToken", "access-two").set("refreshToken", "refresh-two").set("expiresIn", 7200));
      } else if (path.endsWith("/models")) {
        modelPath.set(path);
        body.set("data", Kv.create().set("models", List.of(Kv.create().set("id", "model-a").set("name", "Scoped A"))));
      } else if (path.equals("/v3/config")) {
        body.set("data", Kv.create().set("models", List.of(Kv.create().set("id", "model-a").set("name", "Config A"), Kv.create().set("id", "model-b"))));
      }
      byte[] wire = JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, wire.length);
      exchange.getResponseBody().write(wire);
      exchange.close();
    });
    server.start();
    try {
      for (String product : List.of("buddy", "workbuddy", "workbuddycn")) {
        activeProduct.set(product);
        String key = "proxy.oauth." + product + ".origin";
        String previous = EnvUtils.get(key);
        EnvUtils.set(key, "http://127.0.0.1:" + server.getAddress().getPort());
        tokens.set(0);
        accounts.set(0);
        try {
          BuddyService service = new BuddyService();
          BuddyLoginSession session = service.start(product);
          assertEquals("platform=" + (product.equals("buddy") ? "ide" : product.equals("workbuddycn") ? "workbuddy" : "workbuddy-ai"), platform.get());
          assertEquals(!product.equals("buddy"), session.authUrl.contains("loginSessionId="));
          if (product.equals("workbuddycn")) { assertTrue(session.authUrl.contains("version=5.8.1")); }
          session.nextPoll = 0;
          assertNull(service.poll(session));
          session.nextPoll = 0;
          assertNull(service.poll(session));
          assertEquals("access-one", session.token.getStr("access_token"));
          session.nextPoll = 0;
          Kv credential = service.poll(session);
          assertEquals(product, credential.getStr("type"));
          assertEquals("user-one", credential.getStr("account_id"));
          assertEquals("tenant-one", credential.getStr("enterprise_id"));
          assertTrue(Instant.parse(credential.getStr("expired")).isAfter(Instant.now()));
          assertNull(session.token);
          Kv refreshed = service.refresh(product, credential);
          assertEquals(product, refreshed.getStr("type"));
          assertEquals(product.equals("workbuddycn") ? "plugin" : "ide-main", refreshSource.get());
          assertEquals(product.equals("workbuddycn") ? 3 : 2, accounts.get());
          assertEquals("refresh-one", refreshHeader.get());
          assertEquals("refresh-two", refreshed.getStr("refresh_token"));
          assertEquals("user-one", refreshed.getStr("account_id"));
          List<Kv> models = service.models(product, refreshed);
          assertEquals("/console/enterprises/" + (product.equals("workbuddycn") ? "tenant-one" : "personal") + "/models", modelPath.get());
          assertEquals(2, models.size());
          assertEquals("Scoped A", models.get(0).getStr("name"));
        } finally { EnvUtils.set(key, previous); }
      }
    } finally { server.stop(0); EnvUtils.set("proxy.config", previousConfig); Files.deleteIfExists(config); }
  }

  @Test
  public void filtersNonChatMetadataAndUsesProductIdentity() {
    BuddyService service = new BuddyService();
    Kv data = Kv.create().set("models", List.of(Kv.create().set("id", "image").set("tags", List.of("text-to-image")),
        Kv.create().set("id", "tiny").set("maxOutputTokens", 128), Kv.create().set("id", "chat")))
        .set("agents", List.of(Kv.create().set("name", "craft").set("models", List.of("image", "tiny", "auto", "additional"))));
    assertEquals(List.of("chat", "additional"), service.parseModels(data).stream().map(model -> model.getStr("id")).toList());
    Kv headers = service.headers("workbuddy", Kv.create().set("domain", "stale.example"), "glm-test");
    assertEquals("www.workbuddy.ai", headers.getStr("X-Domain"));
    assertEquals("WorkBuddy", headers.getStr("X-Product"));
    assertEquals("WorkBuddy/5.5.2 WorkBuddy/5.5.2 CLI/5.5.2", headers.getStr("User-Agent"));
  }

  @Test
  public void keepsThreeOriginsCredentialsAndInferenceIdentitiesSeparate() throws Exception {
    BuddyService service = new BuddyService();
    NativeChannelService channels = new NativeChannelService();
    List<String> products = List.of("buddy", "workbuddy", "workbuddycn");
    List<String> origins = List.of("https://copilot.tencent.com", "https://www.workbuddy.ai", "https://www.workbuddy.cn");
    for (int index = 0; index < products.size(); index++) {
      String product = products.get(index);
      assertEquals(origins.get(index), service.origin(product));
      assertEquals(origins.get(index) + "/v2", channels.baseUrl(product));
      assertTrue(channels.supports(product));
      assertEquals(product, new OAuthProviderService().get(product).provider);
      Kv credential = service.normalize(product, Kv.create().set("accessToken", product + "-token"), Kv.create());
      assertEquals(product, credential.getStr("type"));
      Provider provider = new Provider();
      provider.authType = "oauth";
      provider.upstreamType = product;
      provider.metadata = credential.set("domain", "account-domain.example").set("enterprise_id", "tenant").set("user_id", "user-one");
      Request.Builder request = new Request.Builder().url(origins.get(index) + "/v2/chat/completions")
          .post(RequestBody.create("{\"model\":\"glm-test\"}", MediaType.get("application/json")));
      new OAuthTransportService().headers(provider, request);
      Request built = request.build();
      assertEquals("Bearer " + product + "-token", built.header("Authorization"));
      assertEquals("tenant", built.header("X-Tenant-Id"));
      if (product.equals("workbuddycn")) {
        assertEquals("account-domain.example", built.header("X-Domain"));
        assertEquals("user-one", built.header("X-User-Id"));
        assertEquals("SaaS", built.header("X-Product"));
        assertEquals("WorkBuddy", built.header("X-IDE-Name"));
        assertEquals("5.8.1", built.header("X-IDE-Version"));
        assertEquals("true", built.header("X-Private-Data"));
        assertEquals("WorkBuddy/5.8.1 WorkBuddy/5.8.1", built.header("User-Agent"));
      } else {
        assertEquals(product.equals("buddy") ? "copilot.tencent.com" : "www.workbuddy.ai", built.header("X-Domain"));
        assertNull(built.header("X-User-Id"));
      }
    }
    String previous = EnvUtils.get("proxy.oauth.workbuddycn.origin");
    try {
      EnvUtils.set("proxy.oauth.workbuddycn.origin", "http://127.0.0.1:18764");
      assertEquals("http://127.0.0.1:18764", service.origin("workbuddycn"));
      assertEquals("https://www.workbuddy.ai", service.origin("workbuddy"));
      assertEquals("https://copilot.tencent.com", service.origin("buddy"));
    } finally { EnvUtils.set("proxy.oauth.workbuddycn.origin", previous); }
  }
}
