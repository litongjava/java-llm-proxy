package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;

/** Extract metadata before body capture can truncate or discard the request. */
public final class ReasoningEffortService {
  private ReasoningEffortService() {}

  public static String extract(Kv payload) {
    Object nested = ProxyJson.object(payload.get("reasoning")).get("effort");
    String effort = nonBlank(nested);
    return effort != null ? effort : nonBlank(payload.get("reasoning_effort"));
  }

  private static String nonBlank(Object value) {
    return value instanceof String text && !text.isBlank() ? text.trim() : null;
  }
}
