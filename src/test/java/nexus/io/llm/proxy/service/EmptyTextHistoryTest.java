package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmptyTextHistoryTest {
  private final ProtocolConversionService converter = new ProtocolConversionService();

  @Test
  public void removesEmptyMessagesWithoutLosingCallsResultsOrText() {
    Kv input = ProxyJson.parse("""
        {"input":[
          {"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]},
          {"type":"message","role":"assistant","content":[{"type":"output_text","text":""}]},
          {"type":"message","role":"assistant","content":[{"type":"output_text","text":"  \\n"}]},
          {"type":"function_call","call_id":"call_1","name":"run","arguments":"{}"},
          {"type":"function_call_output","call_id":"call_1","output":""},
          {"type":"message","role":"assistant","content":[{"type":"output_text","text":""},{"type":"output_text","text":" done "}]}
        ]}
        """);
    for (String target : List.of("gemini", "anthropic")) {
      Kv converted = converter.request(input, "responses", target, "test", false);
      List<?> messages = ProxyJson.list(converted.get("gemini".equals(target) ? "contents" : "messages"));
      assertEquals(target, 4, messages.size());
      for (Object value : messages) {
        List<?> parts = ProxyJson.list(ProxyJson.object(value).get("gemini".equals(target) ? "parts" : "content"));
        assertFalse(parts.isEmpty());
        for (Object part : parts) {
          Object text = ProxyJson.object(part).get("text");
          if (text instanceof String s) { assertFalse(s.isBlank()); }
        }
      }
      Kv restored = converter.request(converted, target, "openai", "test", false);
      List<?> history = ProxyJson.list(restored.get("messages"));
      assertEquals("call_1", ProxyJson.object(ProxyJson.list(ProxyJson.object(history.get(1)).get("tool_calls")).getFirst()).getStr("id"));
      assertEquals("tool", ProxyJson.object(history.get(2)).getStr("role"));
      assertEquals("call_1", ProxyJson.object(history.get(2)).getStr("tool_call_id"));
      assertEquals(" done ", ProxyJson.object(ProxyJson.list(ProxyJson.object(history.get(3)).get("content")).getFirst()).getStr("text"));
    }
  }

  @Test
  public void replayLocalFailureWhenProvided() throws Exception {
    String file = System.getProperty("emptyTextReplayLog");
    if (file == null) { return; }
    Kv record = ProxyJson.parse(Files.readString(Path.of(file)));
    Kv converted = converter.request(ProxyJson.parse(record.getStr("requestBody")), "responses", "gemini", "test", false);
    Kv old = ProxyJson.object(ProxyJson.parse(record.getStr("upstreamRequestBody")).get("request"));
    List<?> before = ProxyJson.list(old.get("contents"));
    List<?> after = ProxyJson.list(converted.get("contents"));
    assertEquals(before.size(), after.size());
    int removed = 0;
    for (int i = 0; i < before.size(); i++) {
      List<?> original = ProxyJson.list(ProxyJson.object(before.get(i)).get("parts"));
      List<?> cleaned = original.stream().filter(p -> !(ProxyJson.object(p).get("text") instanceof String s && s.isBlank())).toList();
      removed += original.size() - cleaned.size();
      assertEquals("Message " + i, cleaned, ProxyJson.list(ProxyJson.object(after.get(i)).get("parts")));
    }
    assertEquals(42, removed);
  }
}
