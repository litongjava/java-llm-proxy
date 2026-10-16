package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.CodeArtsLoginSession;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.HttpUrl;
import org.junit.Test;

public class CodeArtsServiceTest {
  @Test
  public void cancellationInterruptsLegacyWorkerAndClearsLoginSecrets() throws Exception {
    CodeArtsLoginSession session = new CodeArtsLoginSession();
    session.verifier = "private-verifier";
    session.privateJwk = Kv.create().set("d", "private-scalar");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch ended = new CountDownLatch(1);
    session.ticketWorker = Thread.ofVirtual().start(() -> {
      started.countDown();
      try { Thread.sleep(30000); }
      catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
      finally { ended.countDown(); }
    });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    session.close();
    assertTrue(ended.await(1, TimeUnit.SECONDS));
    assertTrue(session.cancelled);
    assertNull(session.verifier);
    assertNull(session.privateJwk);
  }

  @Test
  public void exchangesRefreshesAndDiscoversSignedModels() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> tokenBody = new AtomicReference<>();
    AtomicReference<String> proof = new AtomicReference<>();
    AtomicReference<String> modelAuthorization = new AtomicReference<>();
    server.createContext("/token", exchange -> {
      tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      proof.set(exchange.getRequestHeaders().getFirst("DPoP"));
      byte[] data = """
          {"credentials":{"access_key_id":"ak-test","secret_access_key":"sk-test","security_token":"st-test","expiration":"2099-01-01T00:00:00Z"},"refresh_token":"refresh-test"}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
    });
    server.createContext("/gateway", exchange -> {
      modelAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] data = """
          {"result":{"models":[{"model_id":"deepseek-v4-flash-0731"},{"model_id":"benefit-new"}]}}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
    });
    server.createContext("/builtin", exchange -> {
      byte[] data = """
          {"builtinModels":[{"model_id":"GLM-5.2","model_name":"GLM"}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
    });
    server.start();
    String prefix = "http://127.0.0.1:" + server.getAddress().getPort();
    Kv previous = Kv.create();
    for (String[] pair : List.of(new String[] {"token-url", "/token"}, new String[] {"gateway-models-url", "/gateway"}, new String[] {"builtin-models-url", "/builtin"})) {
      String key = "proxy.oauth.codearts." + pair[0]; previous.set(key, EnvUtils.get(key)); EnvUtils.set(key, prefix + pair[1]);
    }
    try {
      CodeArtsService service = new CodeArtsService();
      try (CodeArtsLoginSession session = service.start()) {
        HttpUrl auth = HttpUrl.get(session.authUrl);
        assertEquals("SHA-256", auth.queryParameter("code_challenge_method"));
        assertNull(auth.queryParameter("auth_callback_url"));
        assertTrue(Integer.parseInt(auth.queryParameter("port")) >= 10000);
        assertNull(service.poll(session));
        Kv credential = service.exchange(session, "test-code");
        assertTrue(tokenBody.get().contains("grant_type=authorization_code"));
        assertTrue(tokenBody.get().contains("code_verifier="));
        assertNotNull(proof.get());
        assertEquals("ak-test", credential.getStr("access_key_id"));
        assertFalse(credential.containsKey("access_token"));
        assertNotNull(credential.get("dpop_private_key_jwk"));
        Kv refreshed = service.refresh(credential);
        assertTrue(tokenBody.get().contains("grant_type=refresh_token"));
        assertTrue(tokenBody.get().contains("refresh_token=refresh-test"));
        assertEquals(credential.get("code_verifier"), refreshed.get("code_verifier"));
        assertEquals(3, service.models(refreshed).size());
        assertTrue(modelAuthorization.get().startsWith("SDK-HMAC-SHA256 Access=ak-test,"));
        assertTrue(service.benefit(refreshed, "benefit-new"));
        assertFalse(service.benefit(refreshed, "deepseek-v4-flash"));
      }
    } finally {
      previous.forEach((key, value) -> EnvUtils.set(key.toString(), value == null ? null : value.toString())); server.stop(0);
    }
  }
}
