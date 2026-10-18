package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;

/** Groups parallel calls/results into Gemini turns and restores their correspondence. */
public class GeminiHistoryService {
  public List<Kv> normalize(List<Kv> contents) {
    List<Kv> merged = new ArrayList<>();
    for (Kv content : contents) {
      List<?> parts = ProxyJson.list(content.get("parts"));
      if (parts.isEmpty()) { continue; }
      if (!merged.isEmpty() && content.get("role").equals(merged.getLast().get("role"))) {
        Kv previous = merged.getLast();
        List<Object> combined = new ArrayList<>(ProxyJson.list(previous.get("parts")));
        combined.addAll(parts);
        previous.set("parts", combined);
      } else { merged.add(Kv.create().set(content)); }
    }
    List<String> pending = new ArrayList<>();
    for (Kv content : merged) {
      List<Kv> parts = ProxyJson.list(content.get("parts")).stream().map(ProxyJson::object).toList();
      if ("model".equals(content.getStr("role"))) {
        pending.clear();
        for (Kv part : parts) {
          Kv call = ProxyJson.object(part.get("functionCall"));
          if (call.isEmpty()) { continue; }
          if (pending.isEmpty() && part.get("thoughtSignature") == null) {
            part.set("thoughtSignature", "skip_thought_signature_validator");
          }
          pending.add(call.getStr("id"));
        }
        content.set("parts", parts);
      } else if (!pending.isEmpty()) {
        List<Kv> ordered = new ArrayList<>();
        for (String id : pending) {
          for (Kv part : parts) {
            if (java.util.Objects.equals(id, ProxyJson.object(part.get("functionResponse")).getStr("id"))) { ordered.add(part); }
          }
        }
        for (Kv part : parts) { if (!ordered.contains(part)) { ordered.add(part); } }
        content.set("parts", ordered);
        pending.clear();
      }
    }
    return merged;
  }
}
