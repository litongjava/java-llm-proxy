package nexus.io.llm.proxy.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelCatalogRefreshTest {
  @Test
  public void refreshPublishesModelsAndRejectsInvalidCatalog() throws Exception {
    ModelCatalogService service = new ModelCatalogService();
    String original;
    try (InputStream input = getClass().getResourceAsStream("/catalog/models.json")) {
      original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    try {
      Kv catalog = ProxyJson.parse(original);
      List<?> pro = ProxyJson.list(catalog.get("codex-pro"));
      assertTrue(pro.stream().anyMatch(item -> "gpt-6.1-terra".equals(ProxyJson.object(item).getStr("id"))));
      for (String plan : List.of("free", "team", "plus", "pro")) {
        catalog.set("codex-" + plan, List.of(Kv.create().set("id", "refresh-test-model")));
      }
      assertTrue(ModelCatalogService.installCatalog(JsonUtils.toJson(catalog)));
      assertTrue(service.definitions("codex").stream().anyMatch(item -> "refresh-test-model".equals(ProxyJson.object(item).getStr("id"))));
      assertFalse(ModelCatalogService.installCatalog("{\"codex-pro\":[]}"));
      assertTrue(service.definitions("codex").stream().anyMatch(item -> "refresh-test-model".equals(ProxyJson.object(item).getStr("id"))));
    } finally {
      assertTrue(ModelCatalogService.installCatalog(original));
    }
  }
  @Test
  public void remoteFallbackLoadsTheNextValidCatalog() throws Exception {
    ModelCatalogService service = new ModelCatalogService();
    String original;
    try (InputStream input = getClass().getResourceAsStream("/catalog/models.json")) {
      original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    Kv catalog = ProxyJson.parse(original);
    for (String plan : List.of("free", "team", "plus", "pro")) {
      catalog.set("codex-" + plan, List.of(Kv.create().set("id", "remote-test-model")));
    }
    byte[] valid = JsonUtils.toJson(catalog).getBytes(StandardCharsets.UTF_8);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/invalid", exchange -> {
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    });
    server.createContext("/valid", exchange -> {
      exchange.sendResponseHeaders(200, valid.length);
      try (OutputStream output = exchange.getResponseBody()) { output.write(valid); }
    });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      assertTrue(ModelCatalogService.refreshFromUrls(new OkHttpClient(), List.of(base + "/invalid", base + "/valid")));
      assertTrue(service.definitions("codex").stream().anyMatch(item -> "remote-test-model".equals(ProxyJson.object(item).getStr("id"))));
      assertFalse(ModelCatalogService.refreshFromUrls(new OkHttpClient(), List.of(base + "/invalid")));
      assertTrue(service.definitions("codex").stream().anyMatch(item -> "remote-test-model".equals(ProxyJson.object(item).getStr("id"))));
    } finally {
      server.stop(0);
      assertTrue(ModelCatalogService.installCatalog(original));
    }
  }
}
