package nexus.io.llm.proxy.service;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpstreamLifecycleTest {
  private void stalled(boolean headers, boolean disconnect) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean closed = new AtomicBoolean();
    server.createContext("/", exchange -> {
      if (headers) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush(); }
      if (disconnect) { closed.set(true); }
      try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      exchange.close();
    });
    server.start();
    OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
        .addInterceptor(new UpstreamLifecycle(closed::get, disconnect ? 10000 : 300,
            disconnect ? 10000 : 300, "test-stall")).build();
    long start = System.nanoTime();
    try {
      try (var response = client.newCall(new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort()).build()).execute()) {
        response.body().string();
        fail("Silent or abandoned call should be cancelled");
      } catch (IOException expected) {
        assertTrue("Cancellation should precede server release", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
      }
    } finally { release.countDown(); server.stop(0); client.connectionPool().evictAll(); }
  }

  @Test(timeout=8000) public void cancelsSilentHeaders() throws Exception { stalled(false, false); }
  @Test(timeout=8000) public void cancelsSilentBody() throws Exception { stalled(true, false); }
  @Test(timeout=8000) public void cancelsDisconnectedClientBeforeHeaders() throws Exception { stalled(false, true); }
  @Test(timeout=8000) public void cancelsDisconnectedClientDuringBody() throws Exception { stalled(true, true); }

  @Test(timeout=8000) public void activeBodyCanOutliveHeaderDeadlineAndClosingStopsWatch() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicBoolean closed = new AtomicBoolean();
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) {
        for (int i = 0; i < 12; i++) {
          output.write('x'); output.flush();
          try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
      }
    });
    server.start();
    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new UpstreamLifecycle(closed::get, 500, 700, "test-active")).build();
    var call = client.newCall(new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort()).build());
    try {
      try (var response = call.execute()) { assertEquals("xxxxxxxxxxxx", response.body().string()); }
      closed.set(true);
      Thread.sleep(250);
      assertFalse(call.isCanceled());
    } finally { server.stop(0); client.connectionPool().evictAll(); }
  }
}
