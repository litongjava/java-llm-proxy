package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.CodeArtsLoginSession;
import nexus.io.llm.proxy.model.OAuthCallbackInput;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import okhttp3.HttpUrl;
import org.junit.Test;

public class QoderServiceTest {
  private OAuthSession session(QoderService service, String provider) throws Exception {
    OAuthSession session = new OAuthSession();
    session.spec = service.spec(provider);
    service.start(session, new OAuthStartResult());
    session.nextPoll = 0;
    return session;
  }

  @Test
  public void regionsAndPkceUseNativeContract() throws Exception {
    QoderService service = new QoderService();
    assertEquals("https://api2.qoder.sh", service.apiBaseUrl("qoder"));
    assertEquals("https://gateway.qoder.com.cn", service.apiBaseUrl("qodercn"));
    for (String provider : List.of("qoder", "qodercn")) {
      OAuthSession session = session(service, provider);
      OAuthStartResult result = new OAuthStartResult();
      service.start(session, result);
      HttpUrl url = HttpUrl.get(result.url);
      assertEquals(provider.equals("qoder") ? "qoder.com" : "qoder.cn", url.host());
      assertEquals(session.spec.clientId, url.queryParameter("client_id"));
      assertEquals(session.qoder.machineId, url.queryParameter("machine_id"));
      assertEquals(session.qoder.nonce, url.queryParameter("nonce"));
      assertEquals("S256", url.queryParameter("challenge_method"));
      assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
          .digest(session.verifier.getBytes(StandardCharsets.US_ASCII))), url.queryParameter("challenge"));
      assertEquals("device", result.flow);
    }
  }

  private static class Stub extends QoderService {
    Kv response = Kv.create().set("http_status", 404);
    boolean networkFailure;
    int calls;
    String lastUrl;
    @Override protected Kv get(String url, String bearer) throws Exception {
      calls++;
      lastUrl = url;
      if (networkFailure) { throw new IOException("offline"); }
      return response;
    }
  }

  @Test
  public void pollDistinguishesPendingTerminalAndBoundedNetworkFailures() throws Exception {
    Stub service = new Stub();
    OAuthSession pending = session(service, "qoder");
    String verifier = pending.verifier;
    assertNull(service.poll(pending));
    assertEquals(verifier, HttpUrl.get(service.lastUrl).queryParameter("verifier"));
    assertEquals(pending.qoder.nonce, HttpUrl.get(service.lastUrl).queryParameter("nonce"));
    service.poll(pending);
    assertEquals(1, service.calls);
    pending.nextPoll = 0;
    service.response = Kv.create().set("http_status", 200);
    assertNull(service.poll(pending));
    assertEquals("wait", pending.status);
    for (int status : List.of(401, 403)) {
      OAuthSession denied = session(service, "qodercn");
      service.response = Kv.create().set("http_status", status);
      assertNull(service.poll(denied));
      assertEquals("error", denied.status);
      assertNull(denied.verifier);
    }
    OAuthSession malformed = session(service, "qoder");
    service.response = Kv.create().set("token", "test-token");
    assertNull(service.poll(malformed));
    assertEquals("error", malformed.status);
    OAuthSession expired = session(service, "qoder");
    expired.expiresAt = 1;
    assertNull(service.poll(expired));
    assertNull(expired.qoder);
    OAuthSession offline = session(service, "qoder");
    service.networkFailure = true;
    for (int index = 0; index < 5; index++) {
      offline.nextPoll = 0;
      assertNull(service.poll(offline));
      assertEquals(index < 4 ? "wait" : "error", offline.status);
    }
    assertNull(offline.qoder);
  }

  @Test
  public void localPollAndRefreshPreserveMachineAndUserAcrossRegions() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> machine = new AtomicReference<>();
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String result;
      if (path.endsWith("/poll")) {
        result = """
            {"token":"test-token","refresh_token":"test-refresh","user_id":"test-user","expires_at":"2030-01-01T00:00:00Z"}
            """;
      } else if (path.endsWith("/refresh")) {
        Kv body = ProxyJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        machine.set(body.getStr("machine_id"));
        assertEquals("test-refresh", body.getStr("refresh_token"));
        assertEquals("qoder/1.0.0", exchange.getRequestHeaders().getFirst("User-Agent"));
        result = """
            {"device_token":"rotated-token","refreshToken":"rotated-refresh","expires_at":1893456000}
            """;
      } else {
        assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
        result = "{\"name\":\"Test user\"}";
      }
      byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.start();
    Path config = Files.createTempFile("qoder-test-", ".yaml");
    Files.writeString(config, "proxy-url: direct\nplugins: {enabled: false}\n");
    Kv saved = Kv.create();
    for (String key : List.of("proxy.config", "proxy.oauth.qoder.open-api-url", "proxy.oauth.qodercn.open-api-url")) {
      saved.set(key, EnvUtils.get(key));
    }
    try {
      EnvUtils.set("proxy.config", config.toString());
      QoderService service = new QoderService();
      for (String provider : List.of("qoder", "qodercn")) {
        EnvUtils.set("proxy.oauth." + provider + ".open-api-url", "http://127.0.0.1:" + server.getAddress().getPort());
        OAuthSession session = session(service, provider);
        Kv credential = service.poll(session);
        assertNotNull(credential);
        assertEquals("test-user", credential.getStr("uid"));
        assertEquals("Test user", credential.getStr("nickname"));
        Kv refreshed = service.refresh(provider, credential);
        assertEquals(session.qoder.machineId, machine.get());
        assertEquals("test-user", refreshed.getStr("uid"));
        assertEquals("rotated-token", refreshed.getStr("security_oauth_token"));
        assertEquals("rotated-refresh", refreshed.getStr("refresh_token"));
        assertEquals("2030-01-01T00:00:00Z", refreshed.getStr("expired"));
        Kv millis = service.normalizeToken(provider, Kv.create().set("access_token", "test")
            .set("expiresAt", 1893456000000L), refreshed);
        assertEquals(Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(), ((Number) millis.get("expire_time")).longValue());
        Kv noExpiry = service.normalizeToken(provider, Kv.create().set("token", "test"), millis);
        assertNull(noExpiry.get("expired"));
        assertNull(noExpiry.get("expire_time"));
      }
    } finally {
      saved.forEach((key, value) -> EnvUtils.set(key.toString(), value == null ? null : value.toString()));
      server.stop(0);
      Files.deleteIfExists(config);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cancelAndExpirationReleaseNativeSessionSecrets() throws Exception {
    OAuthService oauth = new OAuthService();
    Field field = OAuthService.class.getDeclaredField("sessions");
    field.setAccessible(true);
    ConcurrentHashMap<String, OAuthSession> sessions = (ConcurrentHashMap<String, OAuthSession>) field.get(oauth);
    OAuthSession qoder = session(new QoderService(), "qoder");
    qoder.state = "qoder-test";
    sessions.put(qoder.state, qoder);
    OAuthCallbackInput callback = new OAuthCallbackInput();
    callback.state = qoder.state;
    callback.code = "must-not-exchange";
    try {
      oauth.callback(callback);
      fail("Device authorization must not accept an authorization-code callback");
    } catch (BusinessException expected) {
      assertEquals("wait", qoder.status);
    }
    oauth.cancel(qoder.state);
    assertNull(qoder.qoder);
    assertNull(qoder.verifier);
    OAuthSession expired = new OAuthSession();
    expired.expiresAt = 1;
    expired.codearts = new CodeArtsLoginSession();
    expired.codearts.verifier = "secret";
    expired.codearts.privateJwk = Kv.create().set("d", "private");
    sessions.put("expired", expired);
    Method cleanup = OAuthService.class.getDeclaredMethod("expireSessions");
    cleanup.setAccessible(true);
    cleanup.invoke(oauth);
    assertTrue(expired.codearts.cancelled);
    assertNull(expired.codearts.verifier);
    assertNull(expired.codearts.privateJwk);
    assertFalse(sessions.containsKey("expired"));
  }
}
