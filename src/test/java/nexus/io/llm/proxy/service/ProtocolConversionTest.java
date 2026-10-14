package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolConversionTest {
  private final ProtocolConversionService converter = new ProtocolConversionService();
  private final List<String> protocols = List.of("openai", "anthropic", "gemini", "responses");

  @Test
  public void preservesSystemToolCallsAndResultsAcrossRequestProtocols() {
    Kv original = ProxyJson.parse("""
        {"model":"test","messages":[
          {"role":"system","content":"Answer accurately"},
          {"role":"user","content":"weather"},
          {"role":"assistant","tool_calls":[{"id":"call_weather","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"Tokyo\\"}"}}]},
          {"role":"tool","tool_call_id":"call_weather","name":"weather","content":"{\\"temperature\\":21}"}
        ],"tools":[{"type":"function","function":{"name":"weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]}
        """);
    for (String source : protocols) {
      Kv sourceBody = converter.request(original, "openai", source, "test", false);
      for (String target : protocols) {
        Kv body = converter.request(sourceBody, source, target, "test", false);
        String text = JsonUtils.toJson(body);
        assertTrue(source + " -> " + target, text.contains("Answer accurately"));
        assertTrue(source + " -> " + target, text.contains("Tokyo"));
        assertTrue(source + " -> " + target, text.contains("call_weather"));
        assertTrue(source + " -> " + target, text.contains("temperature"));
      }
    }
  }

  @Test
  public void convertsTextResponsesAndUsageAcrossAllProtocols() {
    Kv original = ProxyJson.parse("""
        {"id":"test-id","model":"test","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
        "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
        """);
    for (String source : protocols) {
      Kv sourceBody = converter.response(original, "openai", source, "test");
      for (String target : protocols) {
        Kv body = converter.response(sourceBody, source, target, "test");
        Kv restored = converter.response(body, target, "openai", "test");
        assertTrue(source + " -> " + target, JsonUtils.toJson(restored).contains("hello"));
        assertEquals(3, ProxyJson.number(ProxyJson.object(restored.get("usage")), "prompt_tokens", -1));
        assertEquals(4, ProxyJson.number(ProxyJson.object(restored.get("usage")), "completion_tokens", -1));
      }
    }
  }

  @Test
  public void translatesStreamingTextAndUsageIncrementally() throws Exception {
    Kv sources = Kv.create()
        .set("openai", """
            data: {"choices":[{"delta":{"content":"hello"}}]}

            data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4}}

            data: [DONE]

            """)
        .set("anthropic", """
            data: {"type":"message_start","message":{"usage":{"input_tokens":3}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

            data: {"type":"message_delta","usage":{"output_tokens":4}}

            data: {"type":"message_stop"}

            """)
        .set("gemini", """
            data: {"candidates":[{"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4}}

            """)
        .set("responses", """
            data: {"type":"response.output_text.delta","delta":"hello"}

            data: {"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":4}}}

            """);
    for (String source : protocols) {
      for (String target : protocols) {
        if (source.equals(target)) { continue; }
        try (ProtocolEventStream stream = new ProtocolEventStream(new ByteArrayInputStream(sources.getStr(source).getBytes(StandardCharsets.UTF_8)), source, target, "test")) {
          String result = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(source + " -> " + target, result.contains("hello"));
          assertTrue(source + " -> " + target, result.contains("4"));
          assertTrue(source + " -> " + target, result.endsWith("\n\n"));
        }
      }
    }
  }
}
