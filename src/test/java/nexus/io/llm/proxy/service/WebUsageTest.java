package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.llm.proxy.model.WebUsageQuery;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebUsageTest {
  @Test
  public void aggregatesJavaRequestRecordsForTheWebConsole() throws Exception {
    Path directory = Files.createTempDirectory("web-usage");
    String previous = EnvUtils.get("proxy.data-dir");
    try {
      EnvUtils.set("proxy.data-dir", directory.toString());
      StorageService storage = new StorageService();
      storage.record(record("web-1", 1_700_000_000_000L, 200, "gpt-a", "openai", "client-a",
          100, 50, 20, 5, 10, 1_000, 200, 0.001));
      storage.record(record("web-2", 1_700_000_060_000L, 500, "gpt-a", "openai", "client-a",
          10, 0, 0, 0, 0, 500, 0, 0));
      storage.record(record("web-3", 1_700_000_120_000L, 499, "claude-b", "claude", "client-b",
          30, 5, 0, 0, 0, 700, 0, 0));

      WebUsageQuery query = new WebUsageQuery();
      query.start = 1_699_999_000_000L;
      query.end = 1_700_002_000_000L;

      Kv overview = storage.webUsageOverview(query);
      assertEquals(3L, overview.getLong("totalRequests").longValue());
      assertEquals(1L, overview.getLong("successCount").longValue());
      assertEquals(1L, overview.getLong("failureCount").longValue());
      assertEquals(1L, overview.getLong("canceledCount").longValue());
      assertEquals(50.0, overview.getDouble("successRate"), 0.0001);
      assertEquals(140L, overview.getLong("inputTokens").longValue());
      assertEquals(55L, overview.getLong("outputTokens").longValue());
      assertEquals(10L, overview.getLong("reasoningTokens").longValue());
      assertEquals(20L, overview.getLong("cacheReadTokens").longValue());
      assertEquals(5L, overview.getLong("cacheCreationTokens").longValue());
      assertEquals(195L, overview.getLong("totalTokens").longValue());
      assertEquals(50.0, overview.getDouble("tps"), 0.0001);
      assertEquals(1L, overview.getLong("tpsSampleCount").longValue());
      assertEquals(733.333, overview.getDouble("averageLatencyMs"), 0.001);
      assertEquals(20.0 / 140.0, overview.getDouble("cacheHitRate"), 0.0001);
      assertEquals(0.001, overview.getDouble("estimatedCost"), 0.000001);
      assertEquals(1L, overview.getLong("pricedRequests").longValue());
      List<Kv> timeline = ProxyJson.list(overview.get("timeline")).stream()
          .map(ProxyJson::object).toList();
      assertEquals(1, timeline.size());
      assertEquals(3L, timeline.get(0).getLong("requests").longValue());
      assertEquals(195L, timeline.get(0).getLong("tokens").longValue());
      List<Kv> models = ProxyJson.list(timeline.get(0).get("models")).stream()
          .map(ProxyJson::object).toList();
      assertEquals("gpt-a", models.get(0).getStr("key"));
      assertEquals(160L, models.get(0).getLong("tokens").longValue());
      assertEquals("claude-b", models.get(1).getStr("key"));

      Kv analysis = storage.webUsageAnalysis(query);
      List<Kv> analysisModels = categories(analysis, "models");
      assertEquals("gpt-a", analysisModels.get(0).getStr("key"));
      assertEquals(2L, analysisModels.get(0).getLong("requests").longValue());
      assertEquals(1L, analysisModels.get(0).getLong("failures").longValue());
      assertEquals(160L, analysisModels.get(0).getLong("tokens").longValue());
      assertEquals("claude-b", analysisModels.get(1).getStr("key"));
      assertEquals("client-a", categories(analysis, "apiKeys").get(0).getStr("key"));
      assertEquals("provider:openai", categories(analysis, "upstreams").get(0).getStr("key"));

      WebUsageQuery page = new WebUsageQuery();
      page.start = query.start;
      page.end = query.end;
      page.page = 1;
      page.pageSize = 2;
      Kv events = storage.webUsageEvents(page);
      assertEquals(3L, events.getLong("total").longValue());
      assertEquals(2, events.getLong("totalPages").longValue());
      List<Kv> items = ProxyJson.list(events.get("items")).stream().map(ProxyJson::object).toList();
      assertEquals(2, items.size());
      assertEquals("web-3", items.get(0).getStr("id"));
      assertTrue(items.get(0).getBoolean("canceled"));
      assertFalse(items.get(0).getBoolean("failed"));
      assertEquals("web-2", items.get(1).getStr("id"));
      assertTrue(items.get(1).getBoolean("failed"));
      assertEquals(500L, items.get(1).getLong("failure_status").longValue());
      assertEquals("boom", items.get(1).getStr("failure_body"));

      WebUsageQuery failed = new WebUsageQuery();
      failed.start = query.start;
      failed.end = query.end;
      failed.failed = true;
      assertEquals(1L, storage.webUsageEvents(failed).getLong("total").longValue());

      WebUsageQuery canceled = new WebUsageQuery();
      canceled.start = query.start;
      canceled.end = query.end;
      canceled.canceled = true;
      assertEquals(1L, storage.webUsageEvents(canceled).getLong("total").longValue());

      WebUsageQuery upstream = new WebUsageQuery();
      upstream.start = query.start;
      upstream.end = query.end;
      upstream.upstream = "provider:openai";
      assertEquals(2L, storage.webUsageEvents(upstream).getLong("total").longValue());

      WebUsageQuery client = new WebUsageQuery();
      client.start = query.start;
      client.end = query.end;
      client.apiKey = "client-a";
      Kv clientEvents = storage.webUsageEvents(client);
      assertEquals(2L, clientEvents.getLong("total").longValue());
      Kv first = ProxyJson.object(ProxyJson.list(clientEvents.get("items")).get(1));
      assertEquals("gpt-a", first.getStr("model"));
      assertEquals("gpt-a", first.getStr("alias"));
      assertEquals("gpt-a-2026", first.getStr("response_model"));
      assertEquals(200L, first.getLong("ttft_ms").longValue());
      assertEquals("/v1/chat/completions", first.getStr("endpoint"));
      assertEquals(0.001, ProxyJson.object(first.get("cost")).getDouble("total"), 0.000001);
      assertEquals(5L, ProxyJson.object(first.get("tokens")).getLong("cache_creation_tokens").longValue());

      Kv status = storage.webUsageStatus();
      assertEquals(3L, status.getLong("totalRecords").longValue());
      assertEquals("collecting", status.getStr("state"));
      assertNotNull(storage.detail("web-1"));
      assertEquals("gpt-a-2026", storage.detail("web-1").responseModel);
    } finally {
      EnvUtils.set("proxy.data-dir", previous);
      try (Stream<Path> files = Files.list(directory)) {
        for (Path file : files.toList()) {
          Files.deleteIfExists(file);
        }
      }
      Files.deleteIfExists(directory);
    }
  }

  private RequestRecord record(String id, long timestamp, int status, String model, String provider,
      String apiKey, long input, long output, long cached, long cacheCreation, long reasoning,
      long duration, long firstByte, double cost) {
    RequestRecord record = new RequestRecord();
    record.id = id;
    record.timestamp = timestamp;
    record.status = status;
    record.model = model;
    record.requestedModel = model;
    record.responseModel = model + "-2026";
    record.reasoningEffort = "high";
    record.provider = provider;
    record.apiKeyName = apiKey;
    record.source = apiKey + "-agent";
    record.path = "/v1/chat/completions";
    record.durationMs = duration;
    record.firstByteMs = firstByte;
    record.inputTokens = input;
    record.outputTokens = output;
    record.cachedTokens = cached;
    record.cacheCreationTokens = cacheCreation;
    record.reasoningTokens = reasoning;
    record.cost = cost;
    if (status >= 400) {
      record.error = status == 499 ? "Client disconnected" : "boom";
    }
    return record;
  }

  private List<Kv> categories(Kv analysis, String key) {
    return ProxyJson.list(analysis.get(key)).stream().map(ProxyJson::object).toList();
  }
}
