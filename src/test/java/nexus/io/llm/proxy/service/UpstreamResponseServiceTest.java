package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import nexus.io.llm.proxy.model.Provider;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpstreamResponseServiceTest {
  private static final String EVENTS = """
      event: response.output_item.added
      data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_test","name":"weather","arguments":""}}

      event: response.function_call_arguments.delta
      data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}

      event: response.completed
      data: {"type":"response.completed","response":{"id":"resp_test","status":"completed","output":[],"usage":{"input_tokens":6029,"output_tokens":31}}}

      """;

  private Response response(String body, String type, int status) {
    Response.Builder builder = new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
        .protocol(Protocol.HTTP_1_1).code(status).message("Test")
        .body(ResponseBody.create(body, (MediaType) null));
    if (type != null) { builder.header("Content-Type", type); }
    return builder.build();
  }

  @Test public void detectsMissingAndBlankHeadersWithoutConsumingEvents() throws Exception {
    for (String header : new String[] {null, ""}) {
      for (String prefix : new String[] {"", ": heartbeat\n\n", "\r\n", "id: 1\n", "retry: 1000\n", "data: {}\n\n"}) {
        try (Response normalized = UpstreamResponseService.normalizeContentType(response(prefix + EVENTS, header, 200))) {
          assertEquals("text/event-stream", normalized.header("Content-Type"));
          assertEquals(prefix + EVENTS, normalized.body().string());
        }
      }
    }
  }

  @Test public void preservesExplicitTypesErrorsAndNonSseBodies() throws Exception {
    for (String body : new String[] {"", "{\"data\":[]}", "[1,2]", "<html>error</html>", "eventual: text"}) {
      try (Response original = response(body, null, 200)) {
        assertSame(original, UpstreamResponseService.normalizeContentType(original));
        assertEquals(body, original.body().string());
      }
    }
    for (Response original : new Response[] {response(EVENTS, "application/json", 200), response(EVENTS, null, 429)}) {
      try (original) { assertSame(original, UpstreamResponseService.normalizeContentType(original)); }
    }
  }

  @Test public void detectsPrefixWithoutWaitingForNextStreamChunk() throws Exception {
    BufferedSource source = Okio.buffer(new Source() {
      boolean sent;
      public long read(Buffer sink, long count) throws IOException {
        if (sent) { throw new IOException("Must not wait for the next chunk"); }
        sent = true;
        sink.writeUtf8("event:");
        return 6;
      }
      public Timeout timeout() { return Timeout.NONE; }
      public void close() { }
    });
    ResponseBody body = new ResponseBody() {
      public MediaType contentType() { return null; }
      public long contentLength() { return -1; }
      public BufferedSource source() { return source; }
    };
    try (Response original = response("", null, 200).newBuilder().body(body).build()) {
      assertEquals("text/event-stream", UpstreamResponseService.normalizeContentType(original).header("Content-Type"));
      assertEquals("event:", original.body().source().readUtf8(6));
    }
  }

  @Test public void missingHeaderCodexStreamConvertsToolCallToAnthropic() throws Exception {
    Provider provider = new Provider();
    provider.upstreamType = "codex";
    try (Response normalized = new OAuthTransportService().response(provider,
        UpstreamResponseService.normalizeContentType(response(EVENTS, null, 200)), true);
        ProtocolEventStream stream = new ProtocolEventStream(normalized.body().byteStream(), "responses", "anthropic", "test")) {
      assertEquals("text/event-stream", normalized.header("Content-Type"));
      String output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(output.contains("tool_use"));
      assertTrue(output.contains("call_test"));
      assertTrue(output.contains("weather"));
      assertTrue(output.contains("message_stop"));
      assertTrue(output.contains("6029"));
      assertTrue(output.contains("31"));
    }
  }

  @Test public void missingHeaderCodexStreamStillAggregatesForNonStreamingClient() throws Exception {
    Provider provider = new Provider();
    provider.upstreamType = "codex";
    try (Response normalized = new OAuthTransportService().response(provider,
        UpstreamResponseService.normalizeContentType(response(EVENTS, null, 200)), false)) {
      assertEquals("application/json", normalized.header("Content-Type"));
      assertEquals("completed", ProxyJson.parse(normalized.body().string()).getStr("status"));
    }
  }
}
