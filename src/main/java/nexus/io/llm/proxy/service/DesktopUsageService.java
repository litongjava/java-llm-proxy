package nexus.io.llm.proxy.service;

import java.time.Instant;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.RequestRecord;

public class DesktopUsageService {
  public List<Kv> queue(int count) {
    List<Kv> records = new ArrayList<>();
    for (RequestRecord record : Aop.get(StorageService.class).queuedUsage(count)) {
      Kv tokens = Kv.create().set("input_tokens", number(record.inputTokens))
          .set("output_tokens", number(record.outputTokens)).set("reasoning_tokens", number(record.reasoningTokens))
          .set("cache_read_tokens", number(record.cachedTokens)).set("cached_tokens", number(record.cachedTokens))
          .set("total_tokens", number(record.inputTokens + record.outputTokens));
      Kv item = Kv.create().set("request_id", record.id).set("timestamp", Instant.ofEpochMilli(record.timestamp).toString())
          .set("model", record.model).set("provider", record.provider).set("source", record.source)
          .set("originator", record.originator).set("session_id", record.session_id).set("thread_id", record.thread_id)
          .set("alias", record.requestedModel).set("response_model", record.responseModel)
          .set("reasoning_effort", record.reasoningEffort)
          .set("endpoint", record.path).set("auth_type", "api-key").set("api_key", record.apiKeyName)
          .set("latency_ms", number(record.durationMs)).set("ttft_ms", number(record.firstByteMs))
          .set("failed", record.status >= 400 || record.status < 200).set("tokens", tokens);
      if (record.status >= 400 || record.status < 200) {
        item.set("fail", Kv.create().set("status_code", record.status).set("body", record.error));
      }
      records.add(item);
    }
    return records;
  }

  private BigInteger number(long value) {
    // Preserve JSON numeric fields for the desktop collector's typed contract.
    return BigInteger.valueOf(value);
  }
}
