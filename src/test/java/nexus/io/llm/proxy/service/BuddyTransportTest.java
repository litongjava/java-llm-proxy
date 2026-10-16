package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.io.IOException;
import com.jfinal.kit.Kv;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class BuddyTransportTest {
  private Response response(String wire) {
    return new Response.Builder().request(new Request.Builder().url("http://localhost/").build()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").header("Content-Type", "text/event-stream")
        .body(ResponseBody.create(wire, MediaType.get("text/event-stream"))).build();
  }

  @Test
  public void aggregatesOpenAiChunksAndPreservesUsage() throws Exception {
    String wire = """
        data: {"id":"buddy-1","choices":[{"index":0,"delta":{"content":"hello","reasoning_content":"reason"},"finish_reason":"stop"}],"usage":{"total_tokens":3}}

        data: [DONE]

        """;
    BuddyTransportService transport = new BuddyTransportService();
    Kv payload = transport.payload(Kv.create().set("reasoning_effort", "high"), "deepseek-model", "/chat/completions");
    assertEquals("enabled", ProxyJson.object(payload.get("thinking")).getStr("type"));
    assertEquals(true, payload.get("stream"));
    try (Response result = transport.response(response(wire), false)) {
      Kv body = ProxyJson.parse(result.body().string());
      Kv message = ProxyJson.object(ProxyJson.object(ProxyJson.list(body.get("choices")).get(0)).get("message"));
      assertEquals("hello", message.getStr("content"));
      assertEquals("reason", message.getStr("reasoning_content"));
      assertEquals(3, ProxyJson.number(ProxyJson.object(body.get("usage")), "total_tokens", 0));
    }
  }

  @Test(expected = IOException.class)
  public void rejectsTencentTopLevelStreamErrors() throws Exception {
    try (Response result = new BuddyTransportService().response(response("data: {\"code\":11140,\"msg\":\"private detail\"}\n\n"), true)) {
      result.body().string();
    }
  }
}
