package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.Test;

public class MiniMaxServiceTest {
  private static class Stub extends MiniMaxService {
    Kv response;
    int calls;
    @Override
    protected Kv post(String url, Kv parameters) {
      calls++;
      return response;
    }
  }

  private OAuthSession session(MiniMaxService service) {
    OAuthSession session = new OAuthSession();
    session.spec = service.spec();
    session.deviceCode = "fake-device";
    session.verifier = "fake-verifier";
    session.expiresAt = System.currentTimeMillis() + 60000;
    return session;
  }

  @Test
  public void recognizesBothPendingFormsBackoffCancellationAndTerminalFailure() {
    Stub service = new Stub();
    OAuthSession session = session(service);
    service.response = Kv.create().set("http_status", 200).set("status", "pending");
    assertNull(service.poll(session));
    assertEquals("wait", session.status);
    service.poll(session);
    assertEquals(1, service.calls);
    session.nextPoll = 0;
    service.response = Kv.create().set("http_status", 400).set("error", "authorization_pending");
    assertNull(service.poll(session));
    assertEquals("wait", session.status);
    session.nextPoll = 0;
    service.response = Kv.create().set("http_status", 200).set("status", "slow_down");
    service.poll(session);
    assertEquals(10, session.interval);
    assertTrue(session.nextPoll > System.currentTimeMillis() + 9000);
    service.cancel(session);
    service.poll(session);
    assertEquals(3, service.calls);
    assertEquals("cancelled", session.error);
    assertNull(session.verifier);
    for (String status : List.of("denied", "expired", "access_denied", "expired_token", "unknown")) {
      OAuthSession failed = session(service);
      service.response = Kv.create().set("status", status).set("http_status", 200);
      assertNull(service.poll(failed));
      assertEquals("error", failed.status);
      assertNull(failed.deviceCode);
    }
  }

  @Test
  public void validatesScopeOpaqueExpiryAndPreservesRefreshRotationFallback() {
    MiniMaxService service = new MiniMaxService();
    Kv grant = ProxyJson.parse("""
        {"access_token":"mmoat_test","refresh_token":"mmort_test","token_type":"Bearer",
        "expires_in":3600,"scope":"agent.default offline_access"}
        """);
    Kv credential = service.normalizeToken(grant, Kv.create());
    assertEquals("minimax", credential.getStr("type"));
    assertTrue(Instant.parse(credential.getStr("expired")).isAfter(Instant.now().plusSeconds(3500)));
    grant.remove("refresh_token");
    assertEquals("mmort_test", service.normalizeToken(grant, credential).getStr("refresh_token"));
    grant.set("scope", "other");
    try {
      service.normalizeToken(grant, credential);
      fail("Missing scope must fail");
    } catch (BusinessException expected) {
      assertEquals("MINIMAX_TOKEN_RESPONSE", expected.getCode());
    }
  }

  @Test
  public void devicePkcePollingRefreshAndAuthenticatedModelsUseDocumentedWireFormat() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<Kv> request = new AtomicReference<>();
    AtomicReference<String> modelQuery = new AtomicReference<>();
    AtomicReference<String> bearer = new AtomicReference<>();
    server.createContext("/device", exchange -> {
      request.set(OAuthService.query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] bytes = """
          {"device_code":"fake-device","user_code":"ABCD","verification_uri":"https://example.invalid/verify",
          "verification_uri_complete":"https://example.invalid/verify?code=ABCD","expires_in":600,"interval":1}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.createContext("/token", exchange -> {
      request.set(OAuthService.query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] bytes = """
          {"access_token":"mmoat_mock","refresh_token":"mmort_mock","token_type":"Bearer","expires_in":3600,"scope":"agent.default"}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.createContext("/models", exchange -> {
      modelQuery.set(exchange.getRequestURI().getRawQuery());
      bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = """
          {"providers":[{"providerId":"minimax","config":{"model_order":["test-large","test-small"],"models":{
          "test-small":{"name":"Small","limit":{"context":200000,"output":0}},
          "test-large":{"name":"Large","limit":{"context":512000,"output":128000},
          "context_window_options":[512000,1000000],"modalities":{"input":["image"]},
          "effort_options":["low","high"],"default_effort":"high","thinking_config":{"mode":"forced_on"}}}}}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.start();
    Path config = Files.createTempFile("minimax-test-", ".yaml");
    Files.writeString(config, "proxy-url: direct\nplugins: {enabled: false}\n");
    Kv saved = Kv.create();
    for (String name : List.of("proxy.config", "proxy.oauth.minimax.device-url", "proxy.oauth.minimax.token-url", "proxy.oauth.minimax.models-url")) {
      saved.set(name, EnvUtils.get(name));
    }
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      EnvUtils.set("proxy.config", config.toString());
      EnvUtils.set("proxy.oauth.minimax.device-url", base + "/device");
      EnvUtils.set("proxy.oauth.minimax.token-url", base + "/token");
      EnvUtils.set("proxy.oauth.minimax.models-url", base + "/models");
      MiniMaxService service = new MiniMaxService();
      OAuthSession session = session(service);
      OAuthStartResult result = new OAuthStartResult();
      service.start(session, result);
      String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
          .digest(session.verifier.getBytes(StandardCharsets.US_ASCII)));
      assertEquals(challenge, request.get().getStr("code_challenge"));
      assertEquals("S256", request.get().getStr("code_challenge_method"));
      assertEquals("agent-backend", request.get().getStr("audience"));
      assertEquals("device", result.flow);
      assertTrue(result.url.contains("code=ABCD"));
      session.nextPoll = 0;
      Kv credential = service.poll(session);
      assertNotNull(credential);
      assertEquals(session.verifier, request.get().getStr("code_verifier"));
      assertEquals("urn:ietf:params:oauth:grant-type:device_code", request.get().getStr("grant_type"));
      service.refresh(credential);
      assertEquals("refresh_token", request.get().getStr("grant_type"));
      assertEquals("mmort_mock", request.get().getStr("refresh_token"));
      assertEquals("agent.default", request.get().getStr("scope"));
      List<Kv> models = service.models(credential);
      assertEquals("region=cn&buildEnv=prod", modelQuery.get());
      assertEquals("Bearer mmoat_mock", bearer.get());
      assertEquals(2, models.size());
      assertEquals("test-large", models.get(0).getStr("id"));
      assertEquals(1000000L, ProxyJson.number(models.get(0), "context_length", 0));
      assertEquals("high", models.get(0).getStr("default_effort"));
      assertFalse(models.get(1).containsKey("max_output_tokens"));
      assertTrue(service.models(null).isEmpty());
    } finally {
      saved.forEach((key, value) -> EnvUtils.set(key.toString(), value == null ? null : value.toString()));
      server.stop(0);
      Files.deleteIfExists(config);
    }
  }
}
