package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;

/**
 * Normalizes Gemini counts without counting cached input as generated output.
 */
public class GeminiUsageService {
  public Kv openAi(Kv metadata) {
    long input = Math.max(0, ProxyJson.number(metadata, "promptTokenCount", 0));
    long cached = Math.max(0, ProxyJson.number(metadata, "cachedContentTokenCount", 0));
    long thoughts = Math.max(0, ProxyJson.number(metadata, "thoughtsTokenCount", 0));
    long candidates = Math.max(0, ProxyJson.number(metadata, "candidatesTokenCount", 0));
    if (candidates == 0) {
      candidates = Math.max(0, ProxyJson.number(metadata, "totalTokenCount", 0) - input - thoughts);
    }
    long output = candidates + thoughts;
    return Kv.create().set("prompt_tokens", input).set("completion_tokens", output).set("total_tokens", input + output)
        .set("prompt_tokens_details", Kv.create().set("cached_tokens", cached))
        .set("completion_tokens_details", Kv.create().set("reasoning_tokens", thoughts));
  }

  public Kv anthropic(Kv usage) {
    long cached = ProxyJson.number(ProxyJson.object(usage.get("prompt_tokens_details")), "cached_tokens", 0);
    return Kv.create().set("input_tokens", Math.max(0, ProxyJson.number(usage, "prompt_tokens", 0) - cached))
        .set("output_tokens", ProxyJson.number(usage, "completion_tokens", 0)).set("cache_read_input_tokens", cached);
  }

  public Kv responses(Kv usage) {
    return Kv.create().set("input_tokens", usage.get("prompt_tokens"))
        .set("output_tokens", usage.get("completion_tokens")).set("total_tokens", usage.get("total_tokens"))
        .set("input_tokens_details", usage.get("prompt_tokens_details"))
        .set("output_tokens_details", usage.get("completion_tokens_details"));
  }
}
