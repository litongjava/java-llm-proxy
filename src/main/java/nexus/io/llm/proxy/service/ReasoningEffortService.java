package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;

/** Extract metadata before body capture can truncate or discard the request. */
public final class ReasoningEffortService {
  private ReasoningEffortService() {}

  public static String extract(Kv payload) {
    Object nested = ProxyJson.object(payload.get("reasoning")).get("effort");
    String effort = nonBlank(nested);
    if (effort == null) { effort = nonBlank(payload.get("reasoning_effort")); }
    if (effort == null) { effort = nonBlank(ProxyJson.object(payload.get("output_config")).get("effort")); }
    return effort;
  }

  /** Usage shows an explicit upstream effort, or the client's requested effort when
   * the target protocol does not expose one. This is not proof of model execution. */
  public static String forRecord(Kv upstreamPayload, String requestedEffort) {
    String upstreamEffort = extract(upstreamPayload);
    return upstreamEffort != null ? upstreamEffort : requestedEffort;
  }

  private static String nonBlank(Object value) {
    return value instanceof String text && !text.isBlank() ? text.trim() : null;
  }
}
