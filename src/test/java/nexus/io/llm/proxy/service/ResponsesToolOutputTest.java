package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResponsesToolOutputTest {
  private final ProtocolConversionService converter = new ProtocolConversionService();

  @Test public void convertsToolResultBlocksForStreamingAndNonStreaming() {
    Kv body = ProxyJson.parse("""
        {"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":[
          {"type":"text","text":"result"},{"type":"text","text":""},
          {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}}
        ]}]}]}
        """);
    for (boolean stream : List.of(false, true)) {
      Kv result = converter.request(body, "anthropic", "responses", "test", stream);
      Kv item = ProxyJson.object(ProxyJson.list(result.get("input")).getFirst());
      assertEquals("call_1", item.getStr("call_id"));
      List<?> output = ProxyJson.list(item.get("output"));
      assertEquals("input_text", ProxyJson.object(output.get(0)).getStr("type"));
      assertEquals("result", ProxyJson.object(output.get(0)).getStr("text"));
      assertEquals("", ProxyJson.object(output.get(1)).getStr("text"));
      assertEquals("input_image", ProxyJson.object(output.get(2)).getStr("type"));
      assertEquals("data:image/png;base64,aGVsbG8=", ProxyJson.object(output.get(2)).getStr("image_url"));
      assertEquals("text", ProxyJson.object(ProxyJson.list(ProxyJson.object(ProxyJson.list(
          ProxyJson.object(ProxyJson.list(body.get("messages")).getFirst()).get("content")).getFirst()).get("content")).getFirst()).getStr("type"));
    }
  }

  @Test public void preservesStringResultsAndNativePayload() {
    Kv body = ProxyJson.parse("""
        {"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"ok"}]}],
        "thinking":{"type":"adaptive"},"context_management":{"edits":[]}}
        """);
    assertSame(body, converter.request(body, "anthropic", "anthropic", "test", true));
    Kv result = converter.request(body, "anthropic", "responses", "test", false);
    assertEquals("ok", ProxyJson.object(ProxyJson.list(result.get("input")).getFirst()).get("output"));
  }

  @Test public void replayLocalFailureWhenProvided() throws Exception {
    String file = System.getProperty("responsesToolReplayLog");
    if (file == null) { return; }
    Kv record = ProxyJson.parse(Files.readString(Path.of(file)));
    Kv body = ProxyJson.parse(record.getStr("requestBody"));
    Kv result = converter.request(body, "anthropic", "responses", "deepseek-flash", true);
    int count = 0;
    for (Object value : ProxyJson.list(result.get("input"))) {
      Kv item = ProxyJson.object(value);
      if (!"function_call_output".equals(item.get("type"))) { continue; }
      for (Object block : ProxyJson.list(item.get("output"))) {
        assertTrue(List.of("input_text", "input_image", "input_file").contains(ProxyJson.object(block).getStr("type")));
        count++;
      }
    }
    assertTrue(count > 0);
  }
}
