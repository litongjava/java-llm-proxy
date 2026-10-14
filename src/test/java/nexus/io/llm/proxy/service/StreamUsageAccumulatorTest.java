package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import nexus.io.llm.proxy.model.RequestRecord;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class StreamUsageAccumulatorTest {
  @Test
  public void readsUsageAcrossByteBoundariesWithoutCapturingBody() {
    RequestRecord record = new RequestRecord();
    StreamUsageAccumulator accumulator = new StreamUsageAccumulator(new ProtocolFormatService(), record);
    String events = """
        data: {"choices":[{"delta":{"content":"你好"}}]}

        data: {"usage":{"prompt_tokens":12,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":3}}}

        data: [DONE]

        """;
    for (byte value : events.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)) {
      accumulator.accept(new byte[] {value}, 1);
    }
    accumulator.finishEvent();
    assertEquals(12, record.inputTokens);
    assertEquals(7, record.outputTokens);
    assertEquals(3, record.cachedTokens);
    assertEquals(null, record.responseBody);
  }

  @Test
  public void skipsOversizedContentEventAndReadsFollowingUsage() {
    RequestRecord record = new RequestRecord();
    StreamUsageAccumulator accumulator = new StreamUsageAccumulator(new ProtocolFormatService(), record);
    byte[] content = ("data: {\"text\":\"" + "x".repeat(1024 * 1024 + 100) + "\"}\n\n")
        .getBytes(StandardCharsets.UTF_8);
    accumulator.accept(content, content.length);
    byte[] usage = "data: {\"usage\":{\"input_tokens\":21,\"output_tokens\":9}}\n\n".getBytes(StandardCharsets.UTF_8);
    accumulator.accept(usage, usage.length);
    assertEquals(21, record.inputTokens);
    assertEquals(9, record.outputTokens);
  }
}
