package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiFinishReasonTest {
  @Test
  public void geminiFinishReasonsSurviveBufferedAndStreamingConversion() throws Exception {
    for (String[] reasons : List.of(new String[] {"MAX_TOKENS", "length"}, new String[] {"SAFETY", "content_filter"})) {
      Kv candidate = Kv.create().set("content", Kv.create().set("parts", List.of(Kv.create().set("text", "partial"))))
          .set("finishReason", reasons[0]);
      Kv source = Kv.create().set("candidates", List.of(candidate));
      Kv result = new ProtocolConversionService().response(source, "gemini", "openai", "m");
      assertEquals(reasons[1], ProxyJson.object(ProxyJson.list(result.get("choices")).getFirst()).getStr("finish_reason"));
      String data = "data: " + JsonUtils.toJson(source) + "\n\n";
      try (ProtocolEventStream stream = new ProtocolEventStream(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), "gemini", "openai", "m")) {
        String output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(output.contains("\"finish_reason\":\"" + reasons[1] + "\""));
      }
    }
  }

  @Test
  public void promptBlockIsReportedAndOpenAiLengthMapsBackToGemini() throws Exception {
    Kv blocked = Kv.create().set("promptFeedback", Kv.create().set("blockReason", "SAFETY"));
    Kv converted = new ProtocolConversionService().response(blocked, "gemini", "openai", "m");
    assertEquals("content_filter", ProxyJson.object(ProxyJson.list(converted.get("choices")).getFirst()).getStr("finish_reason"));
    Kv chat = ProxyJson.parse("""
        {"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}]}
        """);
    Kv gemini = new ProtocolConversionService().response(chat, "openai", "gemini", "m");
    assertEquals("MAX_TOKENS", ProxyJson.object(ProxyJson.list(gemini.get("candidates")).getFirst()).getStr("finishReason"));
    String source = "data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n\n";
    try (ProtocolEventStream stream = new ProtocolEventStream(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "gemini", "openai", "m")) {
      assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("\"finish_reason\":\"content_filter\""));
    }
  }
}
