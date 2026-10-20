package nexus.io.llm.proxy.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReasoningEffortServiceTest {
  @Test public void readsResponsesAndCompletionsWithoutInventingDefaults() {
    assertEquals("medium", extract("{\"reasoning\":{\"effort\":\"medium\",\"context\":\"all_turns\"}}"));
    assertEquals("high", extract("{\"reasoning_effort\":\"high\"}"));
    assertEquals("none", extract("{\"reasoning\":{\"effort\":\"none\"}}"));
    assertEquals("high", extract("{\"output_config\":{\"effort\":\"high\"}}"));
    assertNull(extract("{\"output_config\":{\"effort\":\" \"}}"));
    assertNull(extract("{\"output_config\":\"high\"}"));
    assertNull(extract("{}"));
    assertNull(extract("{\"reasoning\":null,\"reasoning_effort\":\" \"}"));
    assertNull(extract("{\"reasoning\":{\"effort\":42}}"));
  }

  private String extract(String body) {
    return ReasoningEffortService.extract(ProxyJson.parse(body));
  }

  @Test public void retainsRequestedEffortWhenAnthropicConversionHasNoEffortField() {
    var request = ProxyJson.parse("{\"input\":\"hi\",\"reasoning\":{\"effort\":\"medium\",\"summary\":\"auto\"}}");
    var upstream = new ProtocolConversionService().request(request, "responses", "anthropic", "deepseek-v4.1-flash", false);
    assertNull(ReasoningEffortService.extract(upstream));
    assertEquals("medium", ReasoningEffortService.forRecord(upstream, ReasoningEffortService.extract(request)));
  }

  @Test public void explicitUpstreamEffortWinsAndSummaryIsNeverAnEffort() {
    assertEquals("high", ReasoningEffortService.forRecord(ProxyJson.parse("{\"reasoning_effort\":\"high\"}"), "medium"));
    assertEquals("none", ReasoningEffortService.forRecord(ProxyJson.parse("{\"reasoning\":{\"effort\":\"none\"}}"), "medium"));
    assertEquals("auto", ReasoningEffortService.forRecord(ProxyJson.parse("{\"reasoning_effort\":\"auto\"}"), "medium"));
    assertNull(ReasoningEffortService.forRecord(ProxyJson.parse("{\"reasoning\":{\"summary\":\"auto\"}}"), null));
  }
}
