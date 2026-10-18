package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import com.jfinal.kit.Kv;

/** Recognize generated deltas, excluding SSE heartbeats, roles, ids and usage. */
final class StreamOutputService {
  private StreamOutputService() {}

  static boolean hasOutput(byte[] frame) {
    StringBuilder data = new StringBuilder();
    for (String line : new String(frame, StandardCharsets.UTF_8).split("\\r?\\n")) {
      if (line.startsWith("data:")) { data.append(line.substring(5).stripLeading()).append('\n'); }
    }
    try { return output(ProxyJson.parse(data.toString())); }
    catch (RuntimeException ignored) { return false; }
  }

  private static boolean text(Object value) { return value instanceof String s && !s.isEmpty(); }

  private static boolean output(Kv value) {
    String type = ProxyJson.text(value, "type", "");
    if (type.endsWith(".delta") && (type.contains("output_text") || type.contains("reasoning")
        || type.contains("function_call_arguments") || type.contains("custom_tool_call_input"))) {
      return text(value.get("delta"));
    }
    Kv delta = ProxyJson.object(value.get("delta"));
    if (text(delta.get("text")) || text(delta.get("thinking")) || text(delta.get("partial_json"))) { return true; }
    Kv block = ProxyJson.object(value.get("content_block"));
    if (text(block.get("text")) || text(block.get("thinking"))) { return true; }
    for (Object choice : ProxyJson.list(value.get("choices"))) {
      Kv d = ProxyJson.object(ProxyJson.object(choice).get("delta"));
      if (text(d.get("content")) || text(d.get("reasoning_content")) || text(d.get("reasoning"))) { return true; }
      if (text(ProxyJson.object(d.get("function_call")).get("arguments"))) { return true; }
      for (Object call : ProxyJson.list(d.get("tool_calls"))) {
        if (text(ProxyJson.object(ProxyJson.object(call).get("function")).get("arguments"))) { return true; }
      }
    }
    for (Object candidate : ProxyJson.list(value.get("candidates"))) {
      for (Object part : ProxyJson.list(ProxyJson.object(ProxyJson.object(candidate).get("content")).get("parts"))) {
        Kv p = ProxyJson.object(part);
        if (text(p.get("text")) || !ProxyJson.object(p.get("functionCall")).isEmpty()) { return true; }
      }
    }
    return false;
  }
}
