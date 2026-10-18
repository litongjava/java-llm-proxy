package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import junit.framework.TestCase;
import nexus.io.llm.proxy.model.RequestRecord;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.Response;
import okhttp3.Protocol;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.http.common.HttpResponse;
import java.io.ByteArrayOutputStream;

public class ProxyAllocationTest extends TestCase {
  private final PluginHookService hooks = new PluginHookService() {
    @Override List<NativePlugin> enabled(String capability) { return List.of(); }
  };

  public void testNoPluginsPreserveBodiesWithoutSerialization() throws Exception {
    Kv payload = Kv.create().set("model", "test").set("input", "hello");
    assertSame(payload, hooks.transformRequest(payload, "responses", "responses", "test", true));
    assertSame(payload, hooks.thinking(payload, null, "test"));
    byte[] bytes = "data: test\n\n".getBytes(StandardCharsets.UTF_8);
    assertSame(bytes, hooks.responsePhase(bytes, "responses", null, null, null, "response_translator", "response.translate"));
    assertSame(bytes, hooks.response(bytes, null, null));
    assertSame(bytes, hooks.streamChunk(hooks.streamHooks(), bytes, 0, null, null, null, null, null));
    assertFalse(hooks.streamHooks().history());
    assertEquals("identity", hooks.authenticate(null, "identity"));
    assertEquals("test", hooks.route(null, payload, "responses", "test", true).model());
  }

  public void testUnchangedRequestNeedsNoBodyReadback() throws Exception {
    Request request = new Request.Builder().url("http://localhost/responses")
        .post(RequestBody.create("{}", MediaType.get("application/json"))).build();
    PluginHookService.Prepared before = hooks.before(request, null, null);
    assertSame(request, before.request());
    assertFalse(before.bodyChanged());
    assertNull(before.terminated());
    assertSame(request, hooks.request(request, null, null, null).request());
  }

  public void testStreamSnapshotDoesNotRefreshPluginsForEachChunk() throws Exception {
    int[] lookups = { 0 };
    PluginHookService counting = new PluginHookService() {
      @Override List<NativePlugin> enabled(String capability) { lookups[0]++; return List.of(); }
    };
    PluginHookService.StreamHooks snapshot = counting.streamHooks();
    byte[] bytes = { 1, 2, 3 };
    assertSame(bytes, counting.streamChunk(snapshot, bytes, -1, null, null, null, null, List.of()));
    assertSame(bytes, counting.streamChunk(snapshot, bytes, 0, null, null, null, null, List.of()));
    assertSame(bytes, counting.streamChunk(snapshot, bytes, 1, null, null, null, null, List.of()));
    assertEquals(1, lookups[0]);
  }

  public void testUtf8CaptureBoundariesForEveryByteLimit() {
    String original = "ASCII\u00e9\u4e2d\ud83d\ude80tail";
    byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
    for (int limit = 0; limit <= bytes.length + 1; limit++) {
      String prefix = Utf8Capture.prefix(original, limit);
      assertTrue(original.startsWith(prefix));
      assertTrue(prefix.getBytes(StandardCharsets.UTF_8).length <= limit);
      assertEquals(prefix, Utf8Capture.prefix(bytes, limit));
      Utf8Capture capture = new Utf8Capture();
      capture.write(bytes, 0, Math.min(limit, bytes.length));
      assertEquals(prefix, capture.utf8());
    }
    assertSame(original, Utf8Capture.prefix(original, bytes.length));
  }

  public void testCapturedUpstreamPreservesWireAndTrimsPartialCharacter() throws Exception {
    byte[] bytes = "a\ud83d\ude80z".getBytes(StandardCharsets.UTF_8);
    RequestRecord record = new RequestRecord();
    try (CapturedResponseBody captured = new CapturedResponseBody(ResponseBody.create(bytes, MediaType.get("application/json")), record, 3)) {
      assertEquals(new String(bytes, StandardCharsets.UTF_8), captured.string());
      assertEquals("a", record.upstreamResponseBody);
      assertTrue(record.responseTruncated);
    }
    assertEquals("a", record.upstreamResponseBody);
  }

  public void testNoPluginStreamStillSendsAndPersistsCapturedResponse() throws Exception {
    String wire = "data: {\"text\":\"\u4e2d\ud83d\ude80\"}\n\ndata: [DONE]\n\n";
    byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
    for (boolean capture : new boolean[] { true, false }) {
      ByteArrayOutputStream sent = new ByteArrayOutputStream();
      RequestRecord record = new RequestRecord();
      record.protocol = "responses";
      record.requestBody = "saved request";
      record.status = 200;
      boolean[] completed = { false, false };
      ProxyService proxy = new ProxyService() {
        @Override boolean sendStreamHeaders(ChannelContext downstream, HttpResponse response) { return true; }
        @Override boolean sendStreamChunk(ChannelContext downstream, byte[] chunk) { sent.writeBytes(chunk); return true; }
        @Override void closeStream(ChannelContext downstream) { completed[1] = true; }
        @Override void finish(RequestRecord saved, Provider provider, ProxySettings settings) {
          assertSame(record, saved);
          assertEquals("saved request", saved.requestBody);
          assertEquals(capture ? wire : null, saved.responseBody);
          completed[0] = true;
        }
      };
      ProxySettings settings = new ProxySettings();
      settings.captureBodies = capture;
      Request request = new Request.Builder().url("http://localhost/responses").build();
      Response upstream = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
          .header("Content-Type", "text/event-stream").body(ResponseBody.create(bytes, MediaType.get("text/event-stream"))).build();
      proxy.stream(null, null, new HttpResponse(), upstream, record, new Provider(), settings, 4096, null, hooks.streamHooks());
      assertEquals(wire, sent.toString(StandardCharsets.UTF_8));
      assertNull(record.error);
      assertFalse(record.responseTruncated);
      assertTrue(completed[0]);
      assertTrue(completed[1]);
    }
  }
  public void testClientDisconnectIsNotUpstreamFailure() throws Exception {
    RequestRecord record = new RequestRecord();
    record.status = 200;
    ProxyService proxy = new ProxyService() {
      @Override boolean sendStreamHeaders(ChannelContext downstream, HttpResponse response) { return true; }
      @Override boolean sendStreamChunk(ChannelContext downstream, byte[] chunk) { return false; }
      @Override void closeStream(ChannelContext downstream) { }
      @Override void finish(RequestRecord saved, Provider provider, ProxySettings settings) { }
    };
    Response upstream = new Response.Builder().request(new Request.Builder().url("http://localhost/test").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(ResponseBody.create("data: {}\n\n", MediaType.get("text/event-stream"))).build();
    proxy.stream(null, null, new HttpResponse(), upstream, record, new Provider(), new ProxySettings(), 4096, null, hooks.streamHooks());
    assertEquals(499, record.status);
    assertEquals("Client disconnected", record.error);
  }
}
