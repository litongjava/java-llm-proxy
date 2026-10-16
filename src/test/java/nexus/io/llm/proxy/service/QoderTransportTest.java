package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class QoderTransportTest {
  private Response response(String wire) {
    return new Response.Builder().request(new Request.Builder().url("http://localhost/").build()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").header("Content-Type", "text/event-stream")
        .body(ResponseBody.create(wire, MediaType.get("text/event-stream"))).build();
  }

  @Test
  public void preservesImagesToolsHistoryAndProductSession() {
    List<Kv> image = List.of(Kv.create().set("type", "text").set("text", "hello"), Kv.create().set("type", "image_url").set("image_url", Kv.create().set("url", "data:image/png;base64,AA==")));
    Kv body = Kv.create().set("messages", List.of(Kv.create().set("role", "system").set("content", "system"),
        Kv.create().set("role", "user").set("content", image))).set("tools", List.of(Kv.create().set("type", "function"))).set("max_tokens", 12);
    QoderTransportService transport = new QoderTransportService();
    Kv international = transport.payload("qoder", body, "auto", "/chat/completions");
    Kv china = transport.payload("qodercn", body, "auto", "/chat/completions");
    assertEquals("qodercli", international.getStr("session_type"));
    assertEquals("qodercli", china.getStr("session_type"));
    assertEquals(image, ProxyJson.object(ProxyJson.list(international.get("messages")).get(0)).get("content"));
    assertEquals("hello", ProxyJson.object(international.get("chat_context")).getStr("text"));
    assertEquals(12, ProxyJson.number(ProxyJson.object(international.get("parameters")), "max_tokens", 0));
    assertEquals(1, ProxyJson.list(international.get("tools")).size());
  }

  @Test
  public void normalizesToolCallHistoryAndLeavesUnspecifiedReasoningAbsent() {
    Kv assistant = Kv.create().set("role", "assistant").set("content", null)
        .set("tool_calls", List.of(Kv.create().set("id", "call-1"))).set("internal", "discard");
    Kv result = new QoderTransportService().payload("qoder", Kv.create().set("messages", List.of(assistant)), "ultimate", "/chat/completions");
    Kv message = ProxyJson.object(ProxyJson.list(result.get("messages")).get(0));
    assertEquals("", message.get("content"));
    assertFalse(message.containsKey("internal"));
    assertNotNull(message.get("tool_calls"));
    assertFalse(ProxyJson.object(result.get("parameters")).containsKey("reasoning_effort"));
  }

  @Test
  public void unwrapsStringEnvelopeAndAggregatesUsage() throws Exception {
    String chunk = """
        {"id":"qoder-1","choices":[{"index":0,"delta":{"content":"hello","reasoning_content":"reason"},"finish_reason":"stop"}],"usage":{"total_tokens":3}}
        """;
    String wire = "data: {}\n\ndata: " + JsonUtils.toJson(Kv.create().set("body", chunk).set("statusCodeValue", 200)) + "\n\ndata: [DONE]\n\n";
    try (Response result = new QoderTransportService().response(response(wire), false)) {
      Kv body = ProxyJson.parse(result.body().string());
      Kv message = ProxyJson.object(ProxyJson.object(ProxyJson.list(body.get("choices")).get(0)).get("message"));
      assertEquals("hello", message.getStr("content"));
      assertEquals("reason", message.getStr("reasoning_content"));
      assertEquals(3, ProxyJson.number(ProxyJson.object(body.get("usage")), "total_tokens", 0));
    }
  }

  @Test(expected = IOException.class)
  public void rejectsEnvelopeFailure() throws Exception {
    try (Response result = new QoderTransportService().response(response("data: {\"statusCodeValue\":429,\"body\":{}}\n\n"), true)) { result.body().string(); }
  }

  @Test(expected = IOException.class)
  public void rejectsStatusWithoutBody() throws Exception {
    try (Response result = new QoderTransportService().response(response("data: {\"statusCodeValue\":503}\n\n"), true)) { result.body().string(); }
  }

  @Test
  public void handlesNullHeartbeatAndNestedDone() throws Exception {
    String wire = "data: {\"body\":null}\n\ndata: {\"body\":\"null\"}\n\ndata: {\"body\":\"42\"}\n\ndata: {\"body\":\"[DONE]\"}\n\n";
    try (Response result = new QoderTransportService().response(response(wire), true)) {
      assertEquals("data: [DONE]\n\n", result.body().string());
    }
  }

  @Test(expected = IOException.class)
  public void rejectsQueueFailure() throws Exception {
    try (Response result = new QoderTransportService().response(response("data: {\"code\":10605}\n\n"), true)) { result.body().string(); }
  }
}
