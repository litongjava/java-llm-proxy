package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Adapts the standalone compact contract to Codex's native Responses compaction. */
public final class CodexCompactionService {
  private CodexCompactionService() { }

  public static boolean supports(Provider provider, String endpoint) {
    return "/responses/compact".equals(endpoint) && "codex".equals(provider.upstreamType)
        && "oauth".equals(provider.authType);
  }

  public static Kv request(Kv original) {
    List<Object> input = new ArrayList<>(ProxyJson.list(original.get("input")));
    BusinessException.require(!input.isEmpty(), 400, "COMPACT_INPUT", "Compaction requires a non-empty input array");
    BusinessException.require(!Boolean.TRUE.equals(original.getBoolean("stream")), 400, "COMPACT_STREAM", "Standalone compaction is not a streaming endpoint");
    input.add(Kv.create().set("type", "compaction_trigger"));
    return Kv.create().set("model", original.get("model")).set("input", input)
        .set("instructions", ProxyJson.text(original, "instructions", ""))
        .set("stream", true).set("store", false);
  }

  static Kv compacted(Kv response, Object originalInput) {
    BusinessException.require("completed".equals(response.getStr("status")), 502, "COMPACT_INCOMPLETE", "Native compaction did not complete");
    List<?> output = ProxyJson.list(response.get("output"));
    BusinessException.require(output.stream().anyMatch(item -> {
      Kv value = ProxyJson.object(item);
      return "compaction".equals(value.getStr("type")) && value.getStr("encrypted_content") != null
          && !value.getStr("encrypted_content").isBlank();
    }), 502, "COMPACT_OUTPUT", "Native compaction did not return an encrypted compaction item");
    List<Object> window = new ArrayList<>();
    // Keep caller-authored instructions and user messages, as in a standalone compact window.
    // Assistant output and tool transcripts are represented by the opaque compaction item.
    for (Object item : ProxyJson.list(originalInput)) {
      Kv value = ProxyJson.object(item);
      if (List.of("user", "developer", "system").contains(ProxyJson.text(value, "role", ""))) {
        window.add(item);
      }
    }
    window.addAll(output);
    return Kv.create().set("id", response.get("id")).set("object", "response.compaction")
        .set("created_at", response.get("created_at")).set("output", window).set("usage", response.get("usage"));
  }

  public static Response response(Response received, Object originalInput) throws Exception {
    if (!received.isSuccessful() || received.body() == null) { return received; }
    Kv body;
    try (received) { body = compacted(ProxyJson.parse(received.body().string()), originalInput); }
    return received.newBuilder().removeHeader("Content-Length").header("Content-Type", "application/json")
        .body(ResponseBody.create(JsonUtils.toJsonWithLongAsNumber(body), MediaType.get("application/json"))).build();
  }
}
