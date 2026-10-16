package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.llm.proxy.model.OAuthStartResult;
import nexus.io.llm.proxy.model.RaccoonSmsInput;
import org.junit.Test;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;

public class RaccoonServiceTest {
  private static class Stub extends RaccoonService {
    Kv response;
    int calls;
    @Override
    protected Kv post(String url, Kv parameters) {
      calls++;
      return response;
    }
  }

  @Test
  public void qrCodeUsesRandomSessionAndPollIsBoundedByBackoffCancellationAndExpiry() {
    Stub service = new Stub();
    OAuthSession session = new OAuthSession();
    OAuthStartResult result = new OAuthStartResult();
    service.start(session, result);
    assertEquals("qr", result.flow);
    assertEquals(300, result.expires_in);
    assertTrue(session.deviceCode.matches("[a-f0-9]{32}"));
    assertTrue(result.url.contains("code=" + session.deviceCode));
    assertEquals(32, session.verifier.length());
    for (String status : List.of("pending", "logging")) {
      session.nextPoll = 0;
      service.response = Kv.create().set("code", 0).set("data", Kv.create().set("status", status));
      assertNull(service.poll(session));
      assertEquals("wait", session.status);
    }
    service.poll(session);
    assertEquals(2, service.calls);
    session.nextPoll = 0;
    service.response = Kv.create().set("http_status", 429);
    service.poll(session);
    assertEquals(4, session.interval);
    service.cancel(session);
    session.nextPoll = 0;
    assertNull(service.poll(session));
    assertEquals(3, service.calls);
    assertEquals("cancelled", session.error);
    assertNull(session.deviceCode);
    OAuthSession expired = new OAuthSession();
    service.start(expired, new OAuthStartResult());
    expired.expiresAt = 0;
    service.poll(expired);
    assertEquals("error", expired.status);
    assertEquals(3, service.calls);
  }

  @Test
  public void missingTokenAndCancelledResponseAreTerminal() {
    for (String status : List.of("success", "canceled", "unknown")) {
      Stub service = new Stub();
      OAuthSession session = new OAuthSession();
      service.start(session, new OAuthStartResult());
      session.nextPoll = 0;
      service.response = Kv.create().set("code", 0).set("data", Kv.create().set("status", status));
      assertNull(service.poll(session));
      assertEquals("error", session.status);
      assertNull(session.verifier);
    }
  }

  @Test
  public void nativeWireFlowPreservesRefreshIdentityAndCatalogVisibility() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<Kv> sent = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    String jwt = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":2000000000}".getBytes(StandardCharsets.UTF_8)) + ".signature";
    server.createContext("/auth", exchange -> {
      sent.set(ProxyJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      Kv data = Kv.create().set("status", "success").set("access_token", jwt);
      if (sent.get().get("qrcode_code") != null) {
        data.set("refresh_token", "refresh-test").set("office_identity", "org-test");
      }
      byte[] bytes = JsonUtils.toJson(Kv.create().set("code", 0).set("data", data)).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.createContext("/models", exchange -> {
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      assertEquals("org-test", exchange.getRequestHeaders().getFirst("X-Org-Code"));
      byte[] bytes = """
          {"code":0,"data":{"categories":[{"type":"chat","models":[
          {"name":"test-free","description":"Free","visible":true,"billing_effective_multiplier":0,"params":{"context_window":1000,"max_tokens":100}},
          {"name":"hidden","visible":false},{"name":"test-free"},{"name":"test-paid","billing_effective_multiplier":1}]}]}}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (exchange) { exchange.getResponseBody().write(bytes); }
    });
    server.start();
    Path config = Files.createTempFile("raccoon-test-", ".yaml");
    Files.writeString(config, "proxy-url: direct\nplugins: {enabled: false}\n");
    Kv previous = Kv.create();
    for (String name : List.of("proxy.config", "proxy.oauth.raccoon.poll-url", "proxy.oauth.raccoon.refresh-url", "proxy.oauth.raccoon.models-url")) {
      previous.set(name, EnvUtils.get(name));
    }
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      EnvUtils.set("proxy.config", config.toString());
      EnvUtils.set("proxy.oauth.raccoon.poll-url", base + "/auth");
      EnvUtils.set("proxy.oauth.raccoon.refresh-url", base + "/auth");
      EnvUtils.set("proxy.oauth.raccoon.models-url", base + "/models");
      RaccoonService service = new RaccoonService();
      OAuthSession session = new OAuthSession();
      service.start(session, new OAuthStartResult());
      session.nextPoll = 0;
      Kv credential = service.poll(session);
      assertNotNull(credential);
      assertEquals(session.deviceCode, sent.get().getStr("qrcode_code"));
      assertEquals(session.verifier, credential.getStr("device_id"));
      assertEquals("2033-05-18T03:33:20Z", credential.getStr("expired"));
      Kv refreshed = service.refresh(credential);
      assertEquals("refresh-test", sent.get().getStr("refresh_token"));
      assertEquals("refresh-test", refreshed.getStr("refresh_token"));
      assertEquals("org-test", refreshed.getStr("office_identity"));
      assertEquals(credential.getStr("device_id"), refreshed.getStr("device_id"));
      List<Kv> models = service.models(refreshed);
      assertEquals("Bearer " + jwt, auth.get());
      assertEquals(2, models.size());
      assertEquals(Boolean.TRUE, models.get(0).getBoolean("is_free"));
      assertEquals(Boolean.FALSE, models.get(1).getBoolean("is_free"));
    } finally {
      previous.forEach((key, value) -> EnvUtils.set(key.toString(), value == null ? null : value.toString()));
      server.stop(0);
      Files.deleteIfExists(config);
    }
  }

  @Test
  public void phoneEncryptionMatchesAesCfbAndCaptchaIsRequiredBeforeSending() throws Exception {
    RaccoonService service = new RaccoonService();
    byte[] encrypted = Base64.getDecoder().decode(service.encryptPhone("13800000000"));
    assertEquals(27, encrypted.length);
    Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec("senseraccoon2023".getBytes(StandardCharsets.UTF_8), "AES"),
        new IvParameterSpec(Arrays.copyOf(encrypted, 16)));
    assertEquals("13800000000", new String(cipher.doFinal(Arrays.copyOfRange(encrypted, 16, encrypted.length)), StandardCharsets.UTF_8));
    Stub stub = new Stub();
    RaccoonSmsInput input = new RaccoonSmsInput();
    input.phone = "13800000000";
    try {
      stub.sendSms(input);
      fail("User captcha proof is required");
    } catch (RuntimeException expected) {
      assertEquals(0, stub.calls);
    }
  }
}
