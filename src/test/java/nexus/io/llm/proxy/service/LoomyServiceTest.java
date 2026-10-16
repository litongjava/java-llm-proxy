package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.LoomySmsInput;
import nexus.io.llm.proxy.model.LoomySmsResult;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;

public class LoomyServiceTest {
  @Test
  public void signatureMatchesIndependentHmacFixture() throws Exception {
    String previousId = EnvUtils.get("proxy.oauth.loomy.access-key-id");
    String previousSecret = EnvUtils.get("proxy.oauth.loomy.access-key-secret");
    try {
      EnvUtils.set("proxy.oauth.loomy.access-key-id", "test-id");
      EnvUtils.set("proxy.oauth.loomy.access-key-secret", "test-secret");
      Kv headers = new LoomyService().signingHeaders("/login/phone/sendMsgCode", "{}", "Fri, 16 Oct 2026 00:00:00 GMT", "nonce");
      assertEquals("mZFLkyvTelC5g8XnyQrpOw==", headers.getStr("Content-MD5"));
      assertEquals("account test-id:bU5NdEk8B4fA7T8tEglozn2RDWw=", headers.getStr("Authorization"));
    } finally {
      EnvUtils.set("proxy.oauth.loomy.access-key-id", previousId);
      EnvUtils.set("proxy.oauth.loomy.access-key-secret", previousSecret);
    }
  }

  @Test
  public void smsLoginUsesSignedBodyAndProducesNonRefreshableCredential() throws Exception {
    AtomicReference<Kv> sent = new AtomicReference<>();
    AtomicReference<String> signature = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/login/phone/", exchange -> {
      sent.set(ProxyJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      signature.set(exchange.getRequestHeaders().getFirst("Authorization"));
      String data = exchange.getRequestURI().getPath().endsWith("sendMsgCode")
          ? "{\"msgid\":\"message-id\"}" : "{\"session\":\"access-session\",\"userid\":\"account\"}";
      byte[] response = ("{\"code\":\"000000\",\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      try (exchange) { exchange.getResponseBody().write(response); }
    });
    server.start();
    String previous = EnvUtils.get("proxy.oauth.loomy.account-base-url");
    try {
      EnvUtils.set("proxy.oauth.loomy.account-base-url", "http://127.0.0.1:" + server.getAddress().getPort());
      LoomyService service = new LoomyService();
      LoomySmsInput input = new LoomySmsInput();
      input.phone = "13800000000";
      LoomySmsResult message = service.sendSms(input);
      assertEquals("message-id", message.msgid);
      assertEquals(300, ProxyJson.number(ProxyJson.object(sent.get().get("param")), "expire", 0));
      input.msgid = message.msgid;
      input.code = "123456";
      Kv credential = service.login(input);
      assertEquals("access-session", credential.getStr("access_token"));
      assertEquals("account", credential.getStr("account_id"));
      assertEquals(Boolean.FALSE, credential.getBoolean("refreshable"));
      assertFalse(credential.containsKey("refresh_token"));
      assertTrue(Instant.parse(credential.getStr("expired")).isAfter(Instant.now()));
      assertEquals("123456", ProxyJson.object(sent.get().get("param")).getStr("mcode"));
      assertTrue(signature.get().startsWith("account "));
    } finally {
      server.stop(0);
      EnvUtils.set("proxy.oauth.loomy.account-base-url", previous);
    }
  }

  @Test(expected = BusinessException.class)
  public void refreshDoesNotFabricateANewSession() {
    new LoomyService().refresh(Kv.create().set("access_token", "session"));
  }
}
