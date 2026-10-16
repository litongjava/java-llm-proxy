package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.io.IOException;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class ClineTransportTest {
  private Response response(String data) {
    return new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type", "text/event-stream")
        .header("Content-Length", "999").body(ResponseBody.create(data, MediaType.get("text/event-stream"))).build();
  }

  @Test
  public void aggregatesReasoningToolFragmentsChoicesAndFinalUsage() throws Exception {
    String wire = """
        : heartbeat

        data: {"id":"chat-1","created":42,"model":"model","choices":[{"index":0,"delta":{"role":"assistant","reasoning":"think ","content":"你","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"weather","arguments":"{\\\"city\\\":"}}]}},{"index":1,"delta":{"content":"alternative"},"finish_reason":"stop"}]}

        data: {"choices":[{"index":0,"delta":{"reasoning":"more","content":"好","tool_calls":[{"index":0,"function":{"arguments":"\\\"北京\\\"}"}}]},"finish_reason":"tool_calls"}]}

        data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7,"completion_tokens_details":{"reasoning_tokens":2}}}

        data: [DONE]

        """;
    try (Response converted = new ClineTransportService().response(response(wire), false)) {
      assertEquals("application/json", converted.header("Content-Type"));
      assertNull(converted.header("Content-Length"));
      Kv body = ProxyJson.parse(converted.body().string());
      assertEquals("chat.completion", body.getStr("object"));
      assertEquals("chat-1", body.getStr("id"));
      assertEquals(2, ProxyJson.list(body.get("choices")).size());
      Kv choice = ProxyJson.object(ProxyJson.list(body.get("choices")).get(0));
      Kv message = ProxyJson.object(choice.get("message"));
      assertEquals("你好", message.getStr("content"));
      assertEquals("think more", message.getStr("reasoning"));
      assertEquals("tool_calls", choice.getStr("finish_reason"));
      Kv tool = ProxyJson.object(ProxyJson.list(message.get("tool_calls")).get(0));
      assertEquals("call-1", tool.getStr("id"));
      assertEquals("{\"city\":\"北京\"}", ProxyJson.object(tool.get("function")).getStr("arguments"));
      assertEquals(7, ProxyJson.number(ProxyJson.object(body.get("usage")), "total_tokens", 0));
    }
  }

  @Test
  public void forcesSseAndUsageAndPreservesStreamingBytes() throws Exception {
    Provider provider = new Provider();
    provider.authType = "oauth";
    provider.upstreamType = "cline";
    OAuthTransportService transport = new OAuthTransportService();
    Kv body = transport.payload(provider, Kv.create().set("stream", false), "test", "/chat/completions");
    assertEquals(true, body.get("stream"));
    assertEquals(true, ProxyJson.object(body.get("stream_options")).get("include_usage"));
    String wire = "data: {\"choices\":[]}\r\n\r\ndata: [DONE]\r\n\r\n";
    try (Response original = response(wire)) {
      assertSame(original, transport.response(provider, original, true));
      assertEquals(wire, original.body().string());
    }
  }

  @Test(expected = BusinessException.class)
  public void refusesUnsupportedNativeEndpoint() {
    new ClineTransportService().payload(Kv.create(), "/responses");
  }

  @Test(expected = IOException.class)
  public void rejectsPrematureEof() throws Exception {
    new ClineTransportService().response(response("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"), false);
  }

  @Test
  public void rejectsStreamErrorsWithoutEchoingUpstreamDetails() throws Exception {
    try {
      new ClineTransportService().response(response("data: {\"error\":{\"message\":\"private-detail\"}}\n\n"), false);
      fail("Expected stream failure");
    } catch (IOException error) { assertFalse(error.getMessage().contains("private-detail")); }
  }

  @Test
  public void boundsStreamEventsAndTotalResponse() throws Exception {
    String previous = EnvUtils.get("proxy.oauth.cline.max-response-bytes");
    try {
      EnvUtils.set("proxy.oauth.cline.max-response-bytes", "1024");
      for (String wire : new String[] { "data: " + "x".repeat(1100), (":" + "x".repeat(200) + "\n\n").repeat(6) }) {
        try {
          new ClineTransportService().response(response(wire), false);
          fail("Expected response size limit");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
      }
    } finally { EnvUtils.set("proxy.oauth.cline.max-response-bytes", previous); }
  }
}
