package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiUsageTest {
  @Test
  public void cachedOnlyResponseDoesNotBecomeGeneratedOutput() throws Exception {
    assertUsage(142871, 140830, 0, 0, 142871, 2041, 0);
  }

  @Test
  public void missingCandidateCountUsesRawPromptAndIncludesThoughts() throws Exception {
    assertUsage(100, 80, 0, 7, 120, 20, 20);
  }

  @Test
  public void explicitCandidatesIncludeThoughtsAndNegativeInputIsClamped() throws Exception {
    assertUsage(10, 15, 4, 3, 17, 0, 7);
  }

  @Test
  public void inconsistentTotalNeverProducesNegativeOutput() throws Exception {
    assertUsage(100, 80, 0, 0, 90, 20, 0);
  }

  private void assertUsage(long prompt, long cached, long candidates, long thoughts, long total,
      long expectedInput, long expectedOutput) throws Exception {
    Kv metadata = Kv.create().set("promptTokenCount", prompt).set("cachedContentTokenCount", cached)
        .set("candidatesTokenCount", candidates).set("thoughtsTokenCount", thoughts).set("totalTokenCount", total);
    Kv response = Kv.create().set("candidates", List.of(Kv.create().set("finishReason", "STOP")
        .set("content", Kv.create().set("role", "model").set("parts", List.of(Kv.create().set("text", "hello"))))))
        .set("usageMetadata", metadata);
    for (String target : List.of("anthropic", "openai", "responses")) {
      Kv converted = new ProtocolConversionService().response(response, "gemini", target, "test");
      verify(ProxyJson.object(converted.get("usage")), target, prompt, cached, thoughts, expectedInput, expectedOutput);
      String events = "data: " + JsonUtils.toJsonWithLongAsNumber(response) + "\n\n";
      try (ProtocolEventStream stream = new ProtocolEventStream(new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)), "gemini", target, "test")) {
        Kv finalUsage = Kv.create();
        for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
          if (line.startsWith("data: {")) {
            Kv event = ProxyJson.parse(line.substring(6));
            if (event.containsKey("usage")) {
              finalUsage = ProxyJson.object(event.get("usage"));
            }
            if ("response.completed".equals(event.getStr("type"))) {
              finalUsage = ProxyJson.object(ProxyJson.object(event.get("response")).get("usage"));
            }
          }
        }
        verify(finalUsage, target, prompt, cached, thoughts, expectedInput, expectedOutput);
      }
    }
  }

  private void verify(Kv usage, String target, long prompt, long cached, long thoughts, long input, long output) {
    if ("anthropic".equals(target)) {
      assertEquals(input, ProxyJson.number(usage, "input_tokens", -1));
      assertEquals(output, ProxyJson.number(usage, "output_tokens", -1));
      assertEquals(cached, ProxyJson.number(usage, "cache_read_input_tokens", -1));
    } else {
      boolean chat = "openai".equals(target);
      assertEquals(prompt, ProxyJson.number(usage, chat ? "prompt_tokens" : "input_tokens", -1));
      assertEquals(output, ProxyJson.number(usage, chat ? "completion_tokens" : "output_tokens", -1));
      assertEquals(prompt + output, ProxyJson.number(usage, "total_tokens", -1));
      assertEquals(cached, ProxyJson.number(ProxyJson.object(usage.get(chat ? "prompt_tokens_details" : "input_tokens_details")), "cached_tokens", -1));
      assertEquals(thoughts, ProxyJson.number(ProxyJson.object(usage.get(chat ? "completion_tokens_details" : "output_tokens_details")), "reasoning_tokens", -1));
    }
  }
}
