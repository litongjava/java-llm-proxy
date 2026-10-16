package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.llm.proxy.model.ZCodeLoginResponse;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.Test;

public class ZCodeServiceTest {
  @Test
  public void loginAndExistingCodingKeyChainUseNativeEndpointsWithoutCloudWrites() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> initBearer = new AtomicReference<>();
    AtomicReference<String> pollBearer = new AtomicReference<>();
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String result;
      if (path.equals("/init")) {
        assertEquals("POST", exchange.getRequestMethod());
        initBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
        Kv body = ProxyJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("bigmodel", body.getStr("provider"));
        result = """
            {"code":0,"data":{"flow_id":"test-flow","authorize_url":"https://example.invalid/authorize","expires_at":2000000000,"poll_interval_sec":1}}
            """;
      } else if (path.equals("/poll/test-flow")) {
        assertEquals("GET", exchange.getRequestMethod());
        pollBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
        result = """
            {"code":0,"data":{"status":"ready","token":"jwt-test","user":{"user_id":"user-test","name":"Test"},"bigmodel":{"access_token":"third-party","refresh_token":"not-platform-refresh"}}}
            """;
      } else if (path.equals("/api/biz/customer/getCustomerInfo")) {
        assertEquals("GET", exchange.getRequestMethod());
        assertEquals("Bearer third-party", exchange.getRequestHeaders().getFirst("Authorization"));
        result = """
            {"code":0,"data":{"organizations":[{"organizationId":"other","projects":[{"projectId":"other-project"}]},{"organizationId":"org","organizationName":"\u9ed8\u8ba4\u673a\u6784","projects":[{"projectId":"skip","projectType":"2"},{"projectId":"project","projectType":"1"}]}]}}
            """;
      } else if (path.equals("/api/biz/v1/organization/org/projects/project/api_keys")) {
        assertEquals("GET", exchange.getRequestMethod());
        result = """
            [{"name":"other-key","apiKey":"skip"},{"name":"zcode-api-key","apiKey":"key-test"}]
            """;
      } else if (path.equals("/api/biz/v1/organization/org/projects/project/api_keys/copy/key-test")) {
        assertEquals("GET", exchange.getRequestMethod());
        result = """
            {"secretKey":"secret-test"}
            """;
      } else if (path.equals("/configs")) {
        assertNotNull(exchange.getRequestHeaders().getFirst("X-Device-Mid"));
        result = """
            {"code":0,"data":{"builtinModels":{"0":{"modelId":"GLM-5.3-Flash","contextWindow":1000000,"maxCompletionTokens":128000},"1":{"modelId":"GLM-5.3"},"2":{"modelId":"unknown-new-model"}},"configs":{"captcha":{"region":"cn","prefix":"test","sceneId":"scene"}}}}
            """;
      } else {
        result = "{\"code\":404}";
      }
      byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.start();
    Path config = Files.createTempFile("zcode-test-", ".yaml");
    Files.writeString(config, "proxy-url: direct\nplugins: {enabled: false}\n");
    Kv saved = Kv.create();
    for (String name : List.of("proxy.config", "proxy.oauth.zcode.init-url", "proxy.oauth.zcode.poll-base-url", "proxy.oauth.zcode.biz-origin", "proxy.oauth.zcode.config-url")) {
      saved.set(name, EnvUtils.get(name));
    }
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      EnvUtils.set("proxy.config", config.toString());
      EnvUtils.set("proxy.oauth.zcode.init-url", base + "/init");
      EnvUtils.set("proxy.oauth.zcode.poll-base-url", base + "/poll");
      EnvUtils.set("proxy.oauth.zcode.biz-origin", base);
      EnvUtils.set("proxy.oauth.zcode.config-url", base + "/configs");
      ZCodeService service = new ZCodeService();
      OAuthSession session = new OAuthSession();
      OAuthStartResult result = new OAuthStartResult();
      service.start(session, result);
      assertTrue(initBearer.get().matches("Bearer [a-f0-9]{64}"));
      assertEquals("https://example.invalid/authorize", result.url);
      session.nextPoll = 0;
      Kv credential = service.poll(session);
      assertNotNull(credential);
      assertEquals(initBearer.get(), pollBearer.get());
      assertEquals("jwt-test", credential.getStr("access_token"));
      assertNull(credential.getStr("refresh_token"));
      Kv withKey = service.resolveCodingPlanKey(credential);
      assertEquals("key-test.secret-test", withKey.getStr("coding_plan_key"));
      assertEquals("start-plan", withKey.getStr("zcode_channel"));
      assertEquals("GLM-5.3-Flash", service.models(credential).getFirst().getStr("id"));
      assertEquals(1, service.models(credential).size());
      assertEquals("scene", service.captchaConfig(credential).getStr("sceneId"));
      withKey.set("zcode_channel", "coding-plan");
      assertEquals("key-test.secret-test", service.accessToken(withKey));
      assertEquals("https://api.z.ai/api/anthropic/v1", service.apiBaseUrl(withKey));
      try {
        service.refresh(credential);
        fail("Platform JWT cannot be silently refreshed");
      } catch (OAuthRequestException expected) {
        assertTrue(expected.getMessage().contains("new sign-in"));
      }
    } finally {
      saved.forEach((key, value) -> EnvUtils.set(key.toString(), value == null ? null : value.toString()));
      server.stop(0);
      Files.deleteIfExists(config);
    }
  }

  @Test
  public void pendingBackoffCancelAndIncompleteReadyHaveExplicitStates() {
    class Stub extends ZCodeService {
      Kv response;
      int calls;
      @Override
      protected Kv get(String url, String token, Kv credential) {
        calls++;
        return response;
      }
    }
    Stub service = new Stub();
    OAuthSession session = new OAuthSession();
    session.spec = service.spec();
    session.deviceCode = "flow";
    session.verifier = "secret";
    session.expiresAt = System.currentTimeMillis() + 60000;
    service.response = Kv.create().set("code", 0).set("data", Kv.create().set("status", "pending"));
    assertNull(service.poll(session));
    assertEquals("wait", session.status);
    service.poll(session);
    assertEquals(1, service.calls);
    session.nextPoll = 0;
    service.response = Kv.create().set("http_status", 429);
    service.poll(session);
    assertEquals(7, session.interval);
    session.nextPoll = 0;
    service.response = Kv.create().set("code", 0).set("data", Kv.create().set("status", "ready"));
    assertNull(service.poll(session));
    assertEquals("error", session.status);
    assertNull(session.verifier);
    OAuthSession cancelled = new OAuthSession();
    service.cancel(cancelled);
    assertNull(service.poll(cancelled));
    assertEquals("cancelled", cancelled.error);
  }
}
