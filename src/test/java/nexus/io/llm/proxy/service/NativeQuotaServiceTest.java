package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.util.List;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import com.jfinal.kit.Kv;
import org.junit.Test;
import okhttp3.Request;
import okio.Buffer;

public class NativeQuotaServiceTest {
  @Test
  public void fetchResolvesStoredCredentialAndRejectsDisabledAndHttpFailures() throws Exception {
    Path directory = Files.createTempDirectory("native-quota-test");
    Path config = directory.resolve("config.yaml");
    Files.writeString(config, "{}");
    String previousDir = EnvUtils.get("proxy.auth-dir");
    String previousConfig = EnvUtils.get("proxy.config");
    String previousOrigin = EnvUtils.get("proxy.oauth.workbuddycn.origin");
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger status = new AtomicInteger(200);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v2/billing/meter/get-user-resource", exchange -> {
      calls.incrementAndGet();
      assertEquals("Bearer test-access", exchange.getRequestHeaders().getFirst("Authorization"));
      assertEquals("POST", exchange.getRequestMethod());
      byte[] response = "{\"code\":0,\"data\":{\"Response\":{\"Data\":{\"Accounts\":[]}}}}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      EnvUtils.set("proxy.auth-dir", directory.toString());
      EnvUtils.set("proxy.config", config.toString());
      EnvUtils.set("proxy.oauth.workbuddycn.origin", "http://127.0.0.1:" + server.getAddress().getPort());
      Path file = directory.resolve("account.json");
      Kv credential = credential().set("type", "workbuddycn").set("proxy_url", "direct");
      Files.writeString(file, JsonUtils.toJson(credential));
      NativeQuotaService service = new NativeQuotaService();
      Kv result = service.fetch(new CredentialService().index("account.json"));
      assertEquals("workbuddycn", result.getStr("provider"));
      assertEquals(0, ProxyJson.number(ProxyJson.object(result.get("payload")), "code", -1));
      assertFalse(JsonUtils.toJson(result).contains("test-access"));
      status.set(401);
      try { service.fetch("account.json"); fail("Expected HTTP failure"); }
      catch (Exception expected) { assertTrue(expected.getMessage().contains("401")); }
      Files.writeString(file, JsonUtils.toJson(credential.set("disabled", true)));
      try { service.fetch("account.json"); fail("Expected disabled credential rejection"); }
      catch (Exception expected) { assertTrue(expected.getMessage().contains("disabled")); }
      assertEquals(2, calls.get());
    } finally {
      server.stop(0);
      EnvUtils.set("proxy.auth-dir", previousDir);
      EnvUtils.set("proxy.config", previousConfig);
      EnvUtils.set("proxy.oauth.workbuddycn.origin", previousOrigin);
      Files.deleteIfExists(directory.resolve("account.json"));
      Files.deleteIfExists(config);
      Files.deleteIfExists(directory);
    }
  }

  private Kv credential() {
    return Kv.create().set("access_token", "test-access").set("user_id", "test-user").set("uid", "test-user")
        .set("enterprise_id", "test-tenant").set("device_mid", "test-device");
  }

  @Test
  public void buddyRegionsUseIndependentOriginsAndAccountHeaders() throws Exception {
    NativeQuotaService service = new NativeQuotaService();
    for (String provider : List.of("buddy", "workbuddy", "workbuddycn")) {
      Request request = service.prepare(provider, credential());
      String host = provider.equals("buddy") ? "copilot.tencent.com" : provider.equals("workbuddy") ? "www.workbuddy.ai" : "www.workbuddy.cn";
      assertEquals(host, request.url().host());
      assertEquals("/v2/billing/meter/get-user-resource", request.url().encodedPath());
      assertEquals(host, request.header("X-Domain"));
      assertEquals("SaaS", request.header("X-Product"));
      assertEquals("test-user", request.header("X-User-Id"));
      assertEquals("test-tenant", request.header("X-Tenant-Id"));
      assertEquals("Bearer test-access", request.header("Authorization"));
      Buffer body = new Buffer(); request.body().writeTo(body);
      assertEquals("{}", body.readUtf8());
    }
  }

  @Test
  public void queriesOnlyReadEndpointsWithCorrectProviderIdentity() throws Exception {
    NativeQuotaService service = new NativeQuotaService();
    Request qoder = service.prepare("qodercn", credential().set("security_oauth_token", "security-token"));
    assertEquals("https://openapi.qoder.com.cn/sash/api/v2/me/usage", qoder.url().toString());
    assertEquals("Bearer security-token", qoder.header("Authorization"));
    assertEquals("10", qoder.header("Cosy-ClientType"));
    Request zcode = service.prepare("zcode", credential());
    assertEquals("test-device", zcode.header("X-Device-Mid"));
    assertTrue(zcode.url().encodedPath().endsWith("/billing/balance"));
    for (String provider : List.of("cline", "minimax", "raccoon", "loomy", "qoder", "zcode")) {
      assertEquals("GET", service.prepare(provider, credential()).method());
    }
    assertNull(service.prepare("loomy", credential()).header("Authorization"));
    assertEquals("test-access", service.prepare("loomy", credential()).header("token"));
  }

  @Test
  public void traeUsesAccountScopedBillingIdentityAndReadOnlyBody() throws Exception {
    NativeQuotaService service = new NativeQuotaService();
    Request one = service.prepare("trae", credential());
    Request again = service.prepare("trae", credential());
    Request other = service.prepare("trae", credential().set("uid", "another-user"));
    assertEquals("Cloud-IDE-JWT test-access", one.header("Authorization"));
    assertEquals(one.header("X-Device-Id"), again.header("X-Device-Id"));
    assertNotEquals(one.header("X-Device-Id"), other.header("X-Device-Id"));
    assertTrue(one.header("X-Device-Id").matches("[0-9]{15}"));
    assertEquals("stable_cn", one.header("Package-Type"));
    assertEquals("/trae/api/v2/pay/ide_user_ent_usage", one.url().encodedPath());
    Buffer body = new Buffer(); one.body().writeTo(body);
    assertEquals("{\"require_usage\":true,\"req_source\":2}", body.readUtf8());
  }
}
