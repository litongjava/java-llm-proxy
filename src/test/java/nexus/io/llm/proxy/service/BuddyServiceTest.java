package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.BuddyLoginSession;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;

public class BuddyServiceTest {
  @Test
  public void performsBothProductLoginPhasesRefreshAndMergedDiscovery() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger tokens = new AtomicInteger();
    AtomicInteger accounts = new AtomicInteger();
    AtomicReference<String> platform = new AtomicReference<>();
    AtomicReference<String> refreshHeader = new AtomicReference<>();
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      Kv body = Kv.create();
      if (path.endsWith("/auth/state")) {
        platform.set(exchange.getRequestURI().getQuery());
        body.set("data", Kv.create().set("state", "upstream-state").set("authUrl", "https://www.workbuddy.ai/login?state=upstream-state"));
      } else if (path.endsWith("/auth/token")) {
        if (tokens.getAndIncrement() == 0) { body.set("code", 11217); }
        else { body.set("data", Kv.create().set("accessToken", "access-one").set("refreshToken", "refresh-one").set("expiresIn", 3600)); }
      } else if (path.endsWith("/login/account")) {
        if (accounts.getAndIncrement() == 0) { body.set("code", 12151); }
        else { body.set("data", Kv.create().set("uid", "user-one").set("enterpriseId", "tenant-one").set("nickname", "Test")); }
      } else if (path.endsWith("/token/refresh")) {
        refreshHeader.set(exchange.getRequestHeaders().getFirst("X-Refresh-Token"));
        body.set("data", Kv.create().set("accessToken", "access-two").set("refreshToken", "refresh-two").set("expiresIn", 7200));
      } else if (path.endsWith("/personal/models")) {
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
      for (String product : List.of("buddy", "workbuddy")) {
        String key = "proxy.oauth." + product + ".origin";
        String previous = EnvUtils.get(key);
        EnvUtils.set(key, "http://127.0.0.1:" + server.getAddress().getPort());
        tokens.set(0);
        accounts.set(0);
        try {
          BuddyService service = new BuddyService();
          BuddyLoginSession session = service.start(product);
          assertEquals("platform=" + (product.equals("buddy") ? "ide" : "workbuddy-ai"), platform.get());
          assertEquals(product.equals("workbuddy"), session.authUrl.contains("loginSessionId="));
          session.nextPoll = 0;
          assertNull(service.poll(session));
          session.nextPoll = 0;
          assertNull(service.poll(session));
          assertEquals("access-one", session.token.getStr("access_token"));
          session.nextPoll = 0;
          Kv credential = service.poll(session);
          assertEquals("user-one", credential.getStr("account_id"));
          assertEquals("tenant-one", credential.getStr("enterprise_id"));
          assertTrue(Instant.parse(credential.getStr("expired")).isAfter(Instant.now()));
          assertNull(session.token);
          Kv refreshed = service.refresh(product, credential);
          assertEquals("refresh-one", refreshHeader.get());
          assertEquals("refresh-two", refreshed.getStr("refresh_token"));
          assertEquals("user-one", refreshed.getStr("account_id"));
          List<Kv> models = service.models(product, refreshed);
          assertEquals(2, models.size());
          assertEquals("Scoped A", models.get(0).getStr("name"));
        } finally { EnvUtils.set(key, previous); }
      }
    } finally { server.stop(0); }
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
}
