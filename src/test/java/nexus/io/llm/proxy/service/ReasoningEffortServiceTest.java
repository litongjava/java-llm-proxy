package nexus.io.llm.proxy.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReasoningEffortServiceTest {
  @Test public void readsResponsesAndCompletionsWithoutInventingDefaults() {
    assertEquals("medium", extract("{\"reasoning\":{\"effort\":\"medium\",\"context\":\"all_turns\"}}"));
    assertEquals("high", extract("{\"reasoning_effort\":\"high\"}"));
    assertEquals("none", extract("{\"reasoning\":{\"effort\":\"none\"}}"));
    assertNull(extract("{}"));
    assertNull(extract("{\"reasoning\":null,\"reasoning_effort\":\" \"}"));
    assertNull(extract("{\"reasoning\":{\"effort\":42}}"));
  }

  private String extract(String body) {
    return ReasoningEffortService.extract(ProxyJson.parse(body));
  }
}
