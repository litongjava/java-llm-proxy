package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class ToolResultContentTest {
  private final ProtocolConversionService converter = new ProtocolConversionService();

  @Test public void convertsNestedTextAndImagesWithoutMutatingInput() {
    Kv input = ProxyJson.parse("""
        {"input":[{"type":"function_call_output","call_id":"call_1","output":[
          {"type":"input_text","text":"result"},
          {"type":"input_text","text":""},
          {"type":"input_image","image_url":"data:image/png;base64,aGVsbG8="},
          {"type":"input_image","image_url":"https://example.com/image.png"},
          {"type":"text","text":"native"}
        ]}]}
        """);
    Kv result = toolResult(input);
    assertEquals("call_1", result.getStr("tool_use_id"));
    List<?> blocks = ProxyJson.list(result.get("content"));
    assertEquals(4, blocks.size());
    assertEquals("text", ProxyJson.object(blocks.get(0)).getStr("type"));
    assertEquals("result", ProxyJson.object(blocks.get(0)).getStr("text"));
    Kv image = ProxyJson.object(blocks.get(1));
    assertEquals("image", image.getStr("type"));
    assertEquals("base64", ProxyJson.object(image.get("source")).getStr("type"));
    assertEquals("aGVsbG8=", ProxyJson.object(image.get("source")).getStr("data"));
    assertEquals("https://example.com/image.png", ProxyJson.object(ProxyJson.object(blocks.get(2)).get("source")).getStr("url"));
    assertEquals("native", ProxyJson.object(blocks.get(3)).getStr("text"));
    assertEquals("input_text", ProxyJson.object(ProxyJson.list(ProxyJson.object(ProxyJson.list(input.get("input")).getFirst()).get("output")).getFirst()).getStr("type"));
  }

  @Test public void preservesStringAndEmptyResults() {
    for (Object output : List.of("", "result", List.of())) {
      Kv input = Kv.create().set("input", List.of(Kv.create().set("type", "function_call_output").set("call_id", "c").set("output", output)));
      assertEquals(output, toolResult(input).get("content"));
    }
  }

  private Kv toolResult(Kv input) {
    Kv converted = converter.request(input, "responses", "anthropic", "test", false);
    return ProxyJson.object(ProxyJson.list(ProxyJson.object(ProxyJson.list(converted.get("messages")).getFirst()).get("content")).getFirst());
  }

  @Test public void replayLocalFailureWhenProvided() throws Exception {
    String file = System.getProperty("toolResultReplayLog");
    if (file == null) { return; }
    Kv record = ProxyJson.parse(Files.readString(Path.of(file)));
    Kv converted = converter.request(ProxyJson.parse(record.getStr("requestBody")), "responses", "anthropic", "test", false);
    int texts = 0;
    for (Object message : ProxyJson.list(converted.get("messages"))) {
      for (Object value : ProxyJson.list(ProxyJson.object(message).get("content"))) {
        Kv block = ProxyJson.object(value);
        if (!"tool_result".equals(block.getStr("type"))) { continue; }
        for (Object nested : ProxyJson.list(block.get("content"))) {
          Kv part = ProxyJson.object(nested);
          assertNotEquals("input_text", part.getStr("type"));
          assertNotEquals("input_image", part.getStr("type"));
          if ("text".equals(part.getStr("type"))) { texts++; }
        }
      }
    }
    assertEquals(14, texts);
  }
}
