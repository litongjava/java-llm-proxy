package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

public class ClineServiceTest {
  private String previousConfig;
  private Path config;

  @Before
  public void isolateConfiguration() throws Exception {
    previousConfig = EnvUtils.get("proxy.config");
    config = Files.createTempFile("cline-test-", ".yaml");
    Files.writeString(config, "proxy-url: direct\nplugins: {enabled: false}\n");
    EnvUtils.set("proxy.config", config.toString());
  }

  @After
  public void restoreConfiguration() throws Exception {
    EnvUtils.set("proxy.config", previousConfig);
    Files.deleteIfExists(config);
  }

  @Test
  public void registrationAndRotationUseCamelCaseAndKeepPrefix() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<String> title = new AtomicReference<>();
    server.createContext("/auth", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      title.set(exchange.getRequestHeaders().getFirst("X-Title"));
      byte[] bytes = """
          {"success":true,"data":{"accessToken":"workos:new-token","refreshToken":"rotated-token",
           "expiresAt":"2030-01-01T00:00:00Z","userInfo":{"clineUserId":"user-test","email":"test@example.invalid"}}}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.start();
    String oldRegister = EnvUtils.get("proxy.oauth.cline.register-url");
    String oldRefresh = EnvUtils.get("proxy.oauth.cline.refresh-url");
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth";
      EnvUtils.set("proxy.oauth.cline.register-url", url);
      EnvUtils.set("proxy.oauth.cline.refresh-url", url);
      ClineService service = new ClineService();
      Kv registered = service.register(Kv.create().set("access_token", "workos-test").set("refresh_token", "workos-refresh"));
      assertEquals("workos-test", ProxyJson.parse(body.get()).getStr("accessToken"));
      assertFalse(body.get().contains("access_token"));
      assertEquals("Cline", title.get());
      assertEquals("workos:new-token", registered.getStr("access_token"));
      assertEquals("test@example.invalid", registered.getStr("email"));
      assertEquals("2030-01-01T00:00:00Z", registered.getStr("expired"));
      Kv refreshed = service.refresh(registered);
      assertEquals("refresh_token", ProxyJson.parse(body.get()).getStr("grantType"));
      assertEquals("rotated-token", ProxyJson.parse(body.get()).getStr("refreshToken"));
      assertEquals("workos:new-token", refreshed.getStr("access_token"));
    } finally {
      EnvUtils.set("proxy.oauth.cline.register-url", oldRegister);
      EnvUtils.set("proxy.oauth.cline.refresh-url", oldRefresh);
      server.stop(0);
    }
  }

  @Test
  public void rejectsMissingOrUnprefixedTokensWithoutModifyingCredential() {
    ClineService service = new ClineService();
    for (String json : List.of("{}", "{\"success\":false}", "{\"accessToken\":\"bare-jwt\"}")) {
      Kv credential = Kv.create().set("access_token", "workos:original");
      try {
        service.normalizeToken(ProxyJson.parse(json), credential);
        fail("Expected invalid Cline tokens to be rejected");
      } catch (BusinessException expected) {
        assertNotNull(expected.getCode());
      }
      assertEquals("workos:original", credential.getStr("access_token"));
    }
  }

  @Test
  public void modelDiscoveryMergesIndependentEndpointsAndDoesNotInventModels() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> auth = new AtomicReference<>();
    server.createContext("/ai/cline/recommended-models", exchange -> {
      assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = """
          {"free":[{"id":"stealth/test-free","name":"Test Free"}],
           "recommended":[{"id":"vendor/paid"}],"clinePass":[{"id":"cline-pass/paid"}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.createContext("/models", exchange -> {
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = """
          {"data":[{"id":"vendor/paid"},{"id":"vendor/free:free"}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.start();
    String oldBase = EnvUtils.get("proxy.oauth.cline.api-base-url");
    try {
      EnvUtils.set("proxy.oauth.cline.api-base-url", "http://127.0.0.1:" + server.getAddress().getPort());
      ClineService service = new ClineService();
      List<Kv> models = service.models(Kv.create().set("access_token", "workos:test"));
      assertEquals("Bearer workos:test", auth.get());
      assertEquals(4, models.size());
      assertEquals(Boolean.TRUE, models.get(0).getBoolean("is_free"));
      assertEquals(Boolean.FALSE, models.get(1).getBoolean("is_free"));
      assertEquals(Boolean.FALSE, models.get(2).getBoolean("is_free"));
      assertEquals(Boolean.TRUE, models.get(3).getBoolean("is_free"));
      models.get(0).set("id", "mutated");
      assertEquals("stealth/test-free", service.models(Kv.create().set("access_token", "workos:test")).get(0).getStr("id"));
      assertTrue(service.mergeModels(Kv.create(), Kv.create()).isEmpty());
    } finally {
      EnvUtils.set("proxy.oauth.cline.api-base-url", oldBase);
      server.stop(0);
    }
  }
}
