package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import nexus.io.llm.proxy.model.RequestRecord;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class StreamUsageAccumulatorTest {
  @Test
  public void readsDeepSeekCacheHitAndActualResponseModel() {
    RequestRecord record = new RequestRecord();
    record.model = "deepseek-flash";
    record.requestedModel = "claude-sonnet-4-5";
    StreamUsageAccumulator accumulator = new StreamUsageAccumulator(new ProtocolFormatService(), record);
    byte[] bytes = """
        data: {"model":"deepseek-flash","usage":{"prompt_tokens":62400,"completion_tokens":522,"prompt_cache_hit_tokens":60000,"prompt_cache_miss_tokens":2400}}

        data: [DONE]

        """.getBytes(StandardCharsets.UTF_8);
    for (byte value : bytes) {
      accumulator.accept(new byte[] {value}, 1);
    }
    accumulator.finishEvent();
    assertEquals(60000, record.cachedTokens);
    assertEquals(62400, record.inputTokens);
    assertEquals(522, record.outputTokens);
    assertEquals("deepseek-flash", record.model);
    assertEquals("deepseek-flash", record.responseModel);
  }

  @Test
  public void readsNestedDeepSeekUsageAndDoesNotInventCacheHits() {
    RequestRecord record = new RequestRecord();
    ProtocolFormatService formatter = new ProtocolFormatService();
    formatter.usage(record, """
        {"response":{"model":"deepseek-v4-pro","usage":{"input_tokens":80,"output_tokens":5,"prompt_cache_hit_tokens":64}}}
        """);
    assertEquals(64, record.cachedTokens);
    assertEquals("deepseek-v4-pro", record.responseModel);
    RequestRecord uncached = new RequestRecord();
    formatter.usage(uncached, """
        {"model":"deepseek-flash","usage":{"prompt_tokens":80,"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":80}}
        """);
    assertEquals(0, uncached.cachedTokens);
  }

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
