package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClaudeThinkingUsageTest {
  @Test
  public void thinkingTokensAreMappedForBufferedAndStreamingResponses() throws Exception {
    for (long count : new long[] {0, 23}) {
      Kv source = ProxyJson.parse("""
          {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":38,"output_tokens_details":{"thinking_tokens":%d}}}
          """.formatted(count));
      Kv chat = new ProtocolConversionService().response(source, "anthropic", "openai", "m");
      Kv details = ProxyJson.object(ProxyJson.object(chat.get("usage")).get("completion_tokens_details"));
      assertEquals(count, ProxyJson.number(details, "reasoning_tokens", -1));
      Kv responses = new ProtocolConversionService().response(source, "anthropic", "responses", "m");
      Kv responseDetails = ProxyJson.object(ProxyJson.object(responses.get("usage")).get("output_tokens_details"));
      assertEquals(count, ProxyJson.number(responseDetails, "reasoning_tokens", -1));
      String input = "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":38,\"output_tokens_details\":{\"thinking_tokens\":" + count + "}}}\n\n"
          + "data: {\"type\":\"message_stop\"}\n\n";
      try (ProtocolEventStream stream = new ProtocolEventStream(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), "anthropic", "openai", "m")) {
        assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("\"reasoning_tokens\":" + count));
      }
    }
    Kv output = Kv.create();
    ProtocolConversionService.copyClaudeThinkingTokens(Kv.create(), output);
    assertFalse(output.containsKey("completion_tokens_details"));
  }
}
