package nexus.io.llm.proxy.service;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginHostCallbacksTest {
  @Test
  public void acceptsGoHttpFieldsAndCancelsOpenResponseStream() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch release = new CountDownLatch(1);
    server.createContext("/echo", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().add("X-Received", exchange.getRequestHeaders().getFirst("X-Fixture"));
      exchange.sendResponseHeaders(201, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/stream", exchange -> {
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write("first".getBytes(StandardCharsets.UTF_8));
      exchange.getResponseBody().flush();
      try { release.await(10, TimeUnit.SECONDS); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      exchange.close();
    });
    server.start();
    String origin = "http://127.0.0.1:" + server.getAddress().getPort();
    try (PluginHostCallbacks host = new PluginHostCallbacks()) {
      Kv result = host.call("host.http.do", Kv.create().set("Method", "POST").set("URL", origin + "/echo")
          .set("Headers", Kv.create().set("X-Fixture", List.of("go-abi"))).set("Body", PluginHostCallbacks.encode("payload".getBytes(StandardCharsets.UTF_8))));
      assertEquals(201, ProxyJson.number(result, "StatusCode", 0));
      assertEquals("payload", PluginHostCallbacks.text(result.get("Body")));
      String cancelled = host.call("host.http.operation_open", Kv.create()).getStr("operation_id");
      host.call("host.http.cancel", Kv.create().set("operation_id", cancelled));
      try {
        host.call("host.http.do", Kv.create().set("operation_id", cancelled).set("request", Kv.create().set("Method", "GET").set("URL", origin + "/echo")));
        fail("An operation cancelled before execution must not reach the server");
      } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Canceled")); }
      String operation = host.call("host.http.operation_open", Kv.create()).getStr("operation_id");
      Kv stream = host.call("host.http.do_stream", Kv.create().set("operation_id", operation).set("request", Kv.create().set("Method", "GET").set("URL", origin + "/stream")));
      String id = stream.getStr("stream_id");
      assertEquals("first", PluginHostCallbacks.text(host.call("host.http.stream_read", Kv.create().set("stream_id", id)).get("payload")));
      host.call("host.http.cancel", Kv.create().set("operation_id", operation));
      Kv end = host.call("host.http.stream_read", Kv.create().set("stream_id", id));
      assertTrue(end.getBoolean("done"));
    } finally { release.countDown(); server.stop(0); }
  }
}
