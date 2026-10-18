package nexus.io.llm.proxy.service;

import java.util.List;
import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiHistoryTest {
  @Test public void groupsParallelCallsAndMatchesResultsByIdAndName() {
    Kv body = ProxyJson.parse("""
        {"input":[
          {"type":"function_call","call_id":"a","name":"first","arguments":"{}"},
          {"type":"function_call","call_id":"b","name":"second","arguments":"{}"},
          {"type":"function_call_output","call_id":"b","output":"{\"value\":2}"},
          {"type":"function_call_output","call_id":"a","output":"done"},
          {"type":"message","role":"user","content":"continue"}]}
        """.replace("\"output\":\"{\"value\":2}\"", "\"output\":\"{\\\"value\\\":2}\""));
    Kv result = new ProtocolConversionService().request(body, "responses", "gemini", "gemini-3", true);
    List<?> contents = ProxyJson.list(result.get("contents"));
    assertEquals(2, contents.size());
    List<?> calls = ProxyJson.list(ProxyJson.object(contents.get(0)).get("parts"));
    assertEquals(2, calls.size());
    assertEquals("skip_thought_signature_validator", ProxyJson.object(calls.get(0)).getStr("thoughtSignature"));
    assertNull(ProxyJson.object(calls.get(1)).get("thoughtSignature"));
    List<?> outputs = ProxyJson.list(ProxyJson.object(contents.get(1)).get("parts"));
    Kv first = ProxyJson.object(ProxyJson.object(outputs.get(0)).get("functionResponse"));
    Kv second = ProxyJson.object(ProxyJson.object(outputs.get(1)).get("functionResponse"));
    assertEquals("a", first.getStr("id"));
    assertEquals("first", first.getStr("name"));
    assertEquals("second", second.getStr("name"));
    assertEquals("{\"value\":2}", ProxyJson.object(second.get("response")).get("result"));
  }

  @Test public void retainsToolResultImagesAndText() {
    Kv body = ProxyJson.parse("""
        {"input":[{"type":"function_call","call_id":"a","name":"view_image","arguments":"{}"},
          {"type":"function_call_output","call_id":"a","output":[
            {"type":"input_text","text":"image result"},
            {"type":"input_image","image_url":"data:image/png;base64,aGVsbG8="}]}]}
        """);
    Kv result = new ProtocolConversionService().request(body, "responses", "gemini", "test", true);
    Kv content = ProxyJson.object(ProxyJson.list(result.get("contents")).get(1));
    Kv response = ProxyJson.object(ProxyJson.object(ProxyJson.list(content.get("parts")).getFirst()).get("functionResponse"));
    assertEquals("view_image", response.getStr("name"));
    assertEquals(List.of("image result"), ProxyJson.object(response.get("response")).get("result"));
    assertEquals("aGVsbG8=", ProxyJson.object(ProxyJson.object(ProxyJson.list(response.get("parts")).getFirst()).get("inlineData")).getStr("data"));
  }
}
