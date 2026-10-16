package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.model.exception.BusinessException;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class MiniMaxTransportServiceTest {
  @Test
  public void enforcesAdaptiveThinkingAndGroupsParallelToolResults() {
    MiniMaxTransportService service = new MiniMaxTransportService();
    Kv body = ProxyJson.parse("""
        {"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"a","name":"first","input":{}},
         {"type":"tool_use","id":"b","name":"second","input":{}}]},
         {"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"one"}]},
         {"role":"user","content":[{"type":"tool_result","tool_use_id":"b","content":"two"}]}],"reasoning_effort":"high"}
        """);
    Kv payload = service.payload(body, "MiniMax-M3.1-Flash-Preview", "/messages");
    assertEquals(Boolean.TRUE, payload.getBoolean("stream"));
    assertEquals("adaptive", ProxyJson.object(payload.get("thinking")).getStr("type"));
    assertEquals("high", ProxyJson.object(payload.get("output_config")).getStr("effort"));
    List<?> messages = ProxyJson.list(payload.get("messages"));
    assertEquals(2, messages.size());
    assertEquals(2, ProxyJson.list(ProxyJson.object(messages.get(1)).get("content")).size());
    try {
      service.payload(Kv.create().set("reasoning_effort", "none"), "MiniMax-M3.1-Flash-Preview", "/messages");
      fail("Forced thinking cannot be disabled");
    } catch (BusinessException expected) {
      assertEquals("MINIMAX_THINKING_REQUIRED", expected.getCode());
    }
    Request.Builder request = new Request.Builder().url("https://example.invalid/messages")
        .header("Authorization", "Bearer opaque").header("x-api-key", "old").header("anthropic-version", "2023-06-01");
    service.headers(request);
    assertNull(request.build().header("x-api-key"));
    assertNull(request.build().header("anthropic-version"));
    assertEquals("Bearer opaque", request.build().header("Authorization"));
  }

  private Response response(String data) {
    return new Response.Builder().request(new Request.Builder().url("https://example.invalid/messages").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type", "text/event-stream")
        .body(ResponseBody.create(data, MediaType.get("text/event-stream"))).build();
  }

  @Test
  public void aggregatesThinkingToolArgumentsAndUsageWithoutMixingText() throws Exception {
    String stream = """
        data: {"type":"message_start","message":{"id":"msg-test","type":"message","role":"assistant","model":"test","usage":{"input_tokens":10}}}

        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"reason"}}

        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

        data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}

        data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call-test","name":"lookup","input":{}}}

        data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}

        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}

        data: {"type":"message_stop"}

        """;
    try (Response result = new MiniMaxTransportService().response(response(stream), false)) {
      Kv message = ProxyJson.parse(result.body().string());
      List<?> blocks = ProxyJson.list(message.get("content"));
      assertEquals(3, blocks.size());
      assertEquals("reason", ProxyJson.object(blocks.get(0)).getStr("thinking"));
      assertEquals("sig", ProxyJson.object(blocks.get(0)).getStr("signature"));
      assertEquals("answer", ProxyJson.object(blocks.get(1)).getStr("text"));
      assertEquals("call-test", ProxyJson.object(blocks.get(2)).getStr("id"));
      assertEquals(10, ProxyJson.number(ProxyJson.object(message.get("usage")), "input_tokens", 0));
      assertEquals(20, ProxyJson.number(ProxyJson.object(message.get("usage")), "output_tokens", 0));
    }
  }

  @Test
  public void rejectsTruncatedAndErrorStreams() throws Exception {
    for (String stream : List.of("data: {\"type\":\"ping\"}\n\n", "data: {\"type\":\"error\",\"error\":{}}\n\n")) {
      try {
        new MiniMaxTransportService().response(response(stream), false);
        fail("Incomplete response must fail");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().startsWith("MiniMax"));
      }
    }
  }
}
