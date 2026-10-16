package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.tio.utils.environment.EnvUtils;

public class NativeDesktopRefreshTest {
  @Test
  public void lobsterRefreshSendsStoredInstallationWithoutBearer() throws Exception {
    AtomicReference<Kv> sent = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/auth/refresh", exchange -> {
      sent.set(ProxyJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] response = "{\"code\":0,\"data\":{\"accessToken\":\"new\",\"refreshToken\":\"rotated\",\"expiresIn\":3600}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      try (exchange) { exchange.getResponseBody().write(response); }
    });
    server.start();
    String previousBase = EnvUtils.get("proxy.oauth.lobsterai.api-base-url");
    String previousVersion = EnvUtils.get("proxy.oauth.lobsterai.client-version");
    try {
      EnvUtils.set("proxy.oauth.lobsterai.api-base-url", "http://127.0.0.1:" + server.getAddress().getPort());
      EnvUtils.set("proxy.oauth.lobsterai.client-version", "2026.9.4");
      Kv result = new LobsterAiService().refresh(Kv.create().set("refresh_token", "old").set("uuid", "installation")
          .set("first_keyfrom", "100").set("latest_keyfrom", "200").set("user_id", "user"));
      assertEquals("rotated", result.getStr("refresh_token"));
      assertEquals("200", sent.get().getStr("latestKeyfrom"));
      assertEquals("installation", sent.get().getStr("uuid"));
      assertEquals("user", sent.get().getStr("userId"));
      assertNull(authorization.get());
    } finally {
      server.stop(0);
      EnvUtils.set("proxy.oauth.lobsterai.api-base-url", previousBase);
      EnvUtils.set("proxy.oauth.lobsterai.client-version", previousVersion);
    }
  }

  @Test
  public void traeRefreshUsesExchangeTokenContract() throws Exception {
    AtomicReference<Kv> sent = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/cloudide/api/v3/trae/oauth/ExchangeToken", exchange -> {
      sent.set(ProxyJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] response = "{\"Result\":{\"Token\":\"new\",\"RefreshToken\":\"rotated\",\"TokenExpireDuration\":3600}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      try (exchange) { exchange.getResponseBody().write(response); }
    });
    server.start();
    String previous = EnvUtils.get("proxy.oauth.trae.oauth-base-url");
    try {
      EnvUtils.set("proxy.oauth.trae.oauth-base-url", "http://127.0.0.1:" + server.getAddress().getPort());
      Kv result = new TraeService().refresh(Kv.create().set("refresh_token", "old").set("machine_id", "installation"));
      assertEquals("rotated", result.getStr("refresh_token"));
      assertEquals("installation", result.getStr("machine_id"));
      assertEquals("-", sent.get().getStr("ClientSecret"));
      assertEquals("", sent.get().getStr("UserID"));
      assertEquals("old", sent.get().getStr("RefreshToken"));
    } finally {
      server.stop(0);
      EnvUtils.set("proxy.oauth.trae.oauth-base-url", previous);
    }
  }
}
