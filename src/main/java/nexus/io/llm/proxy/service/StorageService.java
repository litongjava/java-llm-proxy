package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import nexus.io.llm.proxy.model.RecentRequestBucket;
import nexus.io.llm.proxy.model.UpstreamTask;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.llm.proxy.model.UsageQuery;
import nexus.io.llm.proxy.model.WebUsageQuery;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;

public class StorageService {
  private final String url;
  private final RequestImageService requestImages;

  public StorageService() {
    try {
      Path directory = Path.of(EnvUtils.get("proxy.data-dir", "data")).toAbsolutePath();
      requestImages = new RequestImageService(directory);
      Files.createDirectories(directory);
      Class.forName("org.sqlite.JDBC");
      url = "jdbc:sqlite:" + directory.resolve("proxy.db");
      try (Connection connection = connection(); Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY, body TEXT NOT NULL)");
        statement.execute("""
            CREATE TABLE IF NOT EXISTS requests (
              id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, model TEXT, provider TEXT,
              api_key TEXT, source TEXT, status INTEGER, duration INTEGER, input_tokens INTEGER,
              output_tokens INTEGER, cached_tokens INTEGER, reasoning_tokens INTEGER,
              cache_creation_tokens INTEGER, cost REAL, summary TEXT NOT NULL, body TEXT NOT NULL
            )
            """);
        for (String column : List.of("originator", "session_id", "thread_id")) {
          boolean exists = false;
          try (ResultSet columns = statement.executeQuery("PRAGMA table_info(requests)")) {
            while (columns.next()) {
              if (column.equals(columns.getString("name")))
                exists = true;
            }
          }
          if (!exists)
            statement.execute("ALTER TABLE requests ADD COLUMN " + column + " TEXT");
          statement.execute(
              "CREATE INDEX IF NOT EXISTS requests_" + column + " ON requests(" + column + ", timestamp DESC)");
        }
        if (!columnExists(statement, "requests", "cache_creation_tokens")) {
          statement.execute("ALTER TABLE requests ADD COLUMN cache_creation_tokens INTEGER");
        }
        statement.execute("CREATE INDEX IF NOT EXISTS requests_time ON requests(timestamp DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS requests_provider_time_status ON requests(provider,timestamp DESC,status)");
        statement.execute("CREATE INDEX IF NOT EXISTS requests_model_time ON requests(model, timestamp DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS requests_api_key_time ON requests(api_key, timestamp DESC)");
        statement.execute("CREATE TABLE IF NOT EXISTS usage_queue (id TEXT PRIMARY KEY)");
        statement.execute("""
            CREATE TABLE IF NOT EXISTS upstream_tasks (
              id TEXT NOT NULL, owner TEXT NOT NULL, body TEXT NOT NULL,
              PRIMARY KEY (id, owner)
            )
            """);
        statement.execute("""
            CREATE TRIGGER IF NOT EXISTS queue_request AFTER INSERT ON requests
            BEGIN
              INSERT OR IGNORE INTO usage_queue(id) VALUES (NEW.id);
            END
            """);
        statement.execute("""
            CREATE TRIGGER IF NOT EXISTS prune_queue AFTER DELETE ON requests
            BEGIN
              DELETE FROM usage_queue WHERE id=OLD.id;
            END
            """);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot initialize proxy storage", e);
    }
  }

  private Connection connection() throws Exception {
    Connection connection = DriverManager.getConnection(url);
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=10000");
    }
    return connection;
  }

  public void saveTask(UpstreamTask task) throws Exception {
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO upstream_tasks(id, owner, body) VALUES (?, ?, ?)
        ON CONFLICT(id, owner) DO UPDATE SET body=excluded.body
        """)) {
      statement.setString(1, task.id);
      statement.setString(2, task.owner);
      statement.setString(3, JsonUtils.toJson(task));
      statement.executeUpdate();
    }
  }

  public UpstreamTask task(String id, String owner) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection
            .prepareStatement("SELECT body FROM upstream_tasks WHERE id=? AND owner=?")) {
      statement.setString(1, id);
      statement.setString(2, owner);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? JsonUtils.parse(result.getString(1), UpstreamTask.class) : null;
      }
    }
  }

  public List<RecentRequestBucket> recentRequests(String provider) {
    long current = System.currentTimeMillis() / 600000;
    long first = current - 19;
    List<RecentRequestBucket> buckets = new ArrayList<>();
    DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    for (long bucket = first; bucket <= current; bucket++) {
      RecentRequestBucket item = new RecentRequestBucket();
      item.time = clock.format(Instant.ofEpochMilli(bucket * 600000)) + "-"
          + clock.format(Instant.ofEpochMilli((bucket + 1) * 600000));
      buckets.add(item);
    }
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
        SELECT timestamp / 600000 AS bucket,
          SUM(CASE WHEN status < 400 THEN 1 ELSE 0 END),
          SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END)
        FROM requests WHERE provider = ? AND timestamp >= ? AND timestamp < ?
        GROUP BY bucket
        """)) {
      statement.setString(1, provider);
      statement.setLong(2, first * 600000);
      statement.setLong(3, (current + 1) * 600000);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          RecentRequestBucket item = buckets.get((int) (rows.getLong(1) - first));
          item.success = BigInteger.valueOf(rows.getLong(2));
          item.failed = BigInteger.valueOf(rows.getLong(3));
        }
      }
      return buckets;
    } catch (Exception error) {
      throw new IllegalStateException("Cannot read recent request metrics", error);
    }
  }

  public Kv providerCounts(String provider) {
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
        SELECT count(*),coalesce(sum(CASE WHEN status>=200 AND status<400 THEN 1 ELSE 0 END),0)
        FROM requests WHERE provider=?
        """)) {
      statement.setString(1, provider);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return Kv.create().set("total", result.getLong(1)).set("success", result.getLong(2));
      }
    } catch (Exception error) {
      throw new IllegalStateException("Cannot read provider counts", error);
    }
  }

  public ProxySettings settings() {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT body FROM settings WHERE id=1")) {
      return result.next() ? JsonUtils.parse(result.getString(1), ProxySettings.class) : new ProxySettings();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read settings", e);
    }
  }

  public synchronized void saveSettings(ProxySettings settings) {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO settings(id, body) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET body=excluded.body")) {
      statement.setString(1, JsonUtils.toJson(settings));
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot save settings", e);
    }
  }

  public void record(RequestRecord record) {
    HeaderService.captureClientContext(record);
    record.requestBody = requestImages.externalize(record.requestBody);
    record.upstreamRequestBody = requestImages.externalize(record.upstreamRequestBody);
    String body = JsonUtils.toJson(record);
    RequestRecord summary = JsonUtils.parse(body, RequestRecord.class);
    summary.requestBody = null;
    summary.upstreamRequestBody = null;
    summary.upstreamResponseBody = null;
    summary.upstreamResponseHeaders = null;
    summary.responseBody = null;
    summary.requestHeaders = null;
    summary.upstreamRequestHeaders = null;
    summary.responseHeaders = null;
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO requests(id,timestamp,model,provider,api_key,source,status,duration,
          input_tokens,output_tokens,cached_tokens,reasoning_tokens,cache_creation_tokens,cost,summary,body,
          originator,session_id,thread_id)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """)) {
      Object[] values = { record.id, record.timestamp, record.model, record.provider, record.apiKeyName, record.source,
          record.status, record.durationMs, record.inputTokens, record.outputTokens, record.cachedTokens,
          record.reasoningTokens, record.cacheCreationTokens, record.cost, JsonUtils.toJson(summary), body,
          record.originator, record.session_id, record.thread_id };
      bind(statement, Arrays.asList(values));
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot save request record", e);
    }
  }

  public RequestRecord detail(String id) {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement("SELECT body FROM requests WHERE id=?")) {
      statement.setString(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        RequestRecord record = JsonUtils.parse(result.getString(1), RequestRecord.class);
        HeaderService.fillDclSessionIds(record);
        return record;
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read request", e);
    }
  }

  public List<RequestRecord> queuedUsage(int count) {
    List<RequestRecord> records = new ArrayList<>();
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
        SELECT r.summary FROM requests r JOIN usage_queue q ON q.id=r.id
        ORDER BY r.timestamp,r.id LIMIT ?
        """)) {
      statement.setInt(1, count);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          records.add(JsonUtils.parse(result.getString(1), RequestRecord.class));
        }
      }
      return records;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read usage queue", e);
    }
  }

  public void acknowledgeUsage(List<String> ids) {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement("DELETE FROM usage_queue WHERE id=?")) {
      connection.setAutoCommit(false);
      for (String id : ids) {
        statement.setString(1, id);
        statement.addBatch();
      }
      statement.executeBatch();
      connection.commit();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot acknowledge usage records", e);
    }
  }

  public Kv usage(UsageQuery query) {
    List<Object> values = new ArrayList<>();
    values.add(query.from);
    values.add(query.to);
    StringBuilder where = new StringBuilder(" WHERE timestamp>=? AND timestamp<=?");
    filter(where, values, "model", query.model);
    filter(where, values, "provider", query.provider);
    filter(where, values, "api_key", query.apiKeyName);
    filter(where, values, "source", query.source);
    filter(where, values, "originator", query.originator);
    filter(where, values, "session_id", query.session_id);
    filter(where, values, "thread_id", query.thread_id);

    if ("success".equals(query.result)) {
      where.append(" AND status>=200 AND status<400");
    } else if ("error".equals(query.result)) {
      where.append(" AND (status<200 OR status>=400)");
    }
    try (Connection connection = connection()) {
      Kv stats = Kv.create();
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT count(*),sum(CASE WHEN status>=200 AND status<400 THEN 1 ELSE 0 END),
            sum(input_tokens),sum(output_tokens),sum(cached_tokens),sum(reasoning_tokens),
            sum(cost),avg(duration) FROM requests
          """ + where)) {
        bind(statement, values);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          stats.set("total", result.getLong(1)).set("success", result.getLong(2)).set("inputTokens", result.getLong(3))
              .set("outputTokens", result.getLong(4)).set("cachedTokens", result.getLong(5))
              .set("reasoningTokens", result.getLong(6)).set("cost", result.getDouble(7))
              .set("averageDuration", result.getDouble(8));
        }
      }
      List<RequestRecord> records = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT summary FROM requests" + where + " ORDER BY timestamp DESC,id DESC LIMIT ? OFFSET ?")) {
        List<Object> pageValues = new ArrayList<>(values);
        pageValues.add(query.pageSize);
        pageValues.add((long) (query.page - 1) * query.pageSize);
        bind(statement, pageValues);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            records.add(JsonUtils.parse(result.getString(1), RequestRecord.class));
          }
        }
      }
      List<Kv> trend = new ArrayList<>();
      try (PreparedStatement statement = connection
          .prepareStatement("SELECT timestamp/3600000*3600000,count(*),sum(input_tokens+output_tokens) FROM requests"
              + where + " GROUP BY timestamp/3600000 ORDER BY 1 DESC LIMIT 168")) {
        bind(statement, values);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            trend.add(Kv.create().set("time", result.getLong(1)).set("requests", result.getLong(2)).set("tokens",
                result.getLong(3)));
          }
        }
      }
      return Kv.create().set("stats", stats).set("records", records).set("trend", trend).set("page", query.page)
          .set("pageSize", query.pageSize);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot query usage", e);
    }
  }

  public Kv webUsageOverview(WebUsageQuery query) {
    WebUsageFilter filter = webUsageFilter(query);
    try (Connection connection = connection()) {
      long total;
      long success;
      long failure;
      long canceled;
      long input;
      long output;
      long reasoning;
      long cached;
      long cacheCreation;
      long totalTokens;
      long durationSum;
      double tpsSum;
      long tpsCount;
      double cost;
      long priced;
      Long first;
      Long last;
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT count(*),
            COALESCE(SUM(CASE WHEN status>=200 AND status<400 THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN (status<200 OR status>=400) AND status<>499 THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN status=499 THEN 1 ELSE 0 END),0),
            COALESCE(SUM(input_tokens),0),
            COALESCE(SUM(output_tokens),0),
            COALESCE(SUM(reasoning_tokens),0),
            COALESCE(SUM(cached_tokens),0),
            COALESCE(SUM(cache_creation_tokens),0),
            COALESCE(SUM(input_tokens+output_tokens),0),
            COALESCE(SUM(duration),0),
            COALESCE(SUM(CASE WHEN duration>0 AND output_tokens>0 AND status>=200 AND status<400
              THEN output_tokens*1000.0/duration ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN duration>0 AND output_tokens>0 AND status>=200 AND status<400
              THEN 1 ELSE 0 END),0),
            COALESCE(SUM(cost),0),
            COALESCE(SUM(CASE WHEN cost>0 THEN 1 ELSE 0 END),0),
            MIN(timestamp), MAX(timestamp)
          FROM requests
          """ + filter.clause)) {
        bind(statement, filter.values);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          total = result.getLong(1);
          success = result.getLong(2);
          failure = result.getLong(3);
          canceled = result.getLong(4);
          input = result.getLong(5);
          output = result.getLong(6);
          reasoning = result.getLong(7);
          cached = result.getLong(8);
          cacheCreation = result.getLong(9);
          totalTokens = result.getLong(10);
          durationSum = result.getLong(11);
          tpsSum = result.getDouble(12);
          tpsCount = result.getLong(13);
          cost = result.getDouble(14);
          priced = result.getLong(15);
          long firstValue = result.getLong(16);
          first = result.wasNull() ? null : firstValue;
          long lastValue = result.getLong(17);
          last = result.wasNull() ? null : lastValue;
        }
      }
      long completed = success + failure;
      double successRate = completed == 0 ? 0 : success * 100.0 / completed;
      double averageLatency = total == 0 ? 0 : (double) durationSum / total;
      double tps = tpsCount == 0 ? 0 : tpsSum / tpsCount;
      double cacheHitRate = input == 0 ? 0 : Math.min(1.0, (double) cached / input);
      double minutes = webUsageWindowMinutes(query, first, last);
      double rpm = total == 0 ? 0 : total / minutes;
      double tpm = total == 0 ? 0 : totalTokens / minutes;
      return Kv.create()
          .set("totalRequests", total)
          .set("successCount", success)
          .set("failureCount", failure)
          .set("canceledCount", canceled)
          .set("successRate", successRate)
          .set("inputTokens", input)
          .set("outputTokens", output)
          .set("reasoningTokens", reasoning)
          .set("cacheReadTokens", cached)
          .set("cacheCreationTokens", cacheCreation)
          .set("totalTokens", totalTokens)
          .set("rpm", rpm)
          .set("tpm", tpm)
          .set("tps", tps)
          .set("tpsSampleCount", tpsCount)
          .set("averageLatencyMs", averageLatency)
          .set("cacheHitRate", cacheHitRate)
          .set("estimatedCost", cost)
          .set("pricedRequests", priced)
          .set("timeline", webUsageTimeline(connection, filter));
    } catch (Exception error) {
      throw new IllegalStateException("Cannot query web usage overview", error);
    }
  }

  public Kv webUsageAnalysis(WebUsageQuery query) {
    WebUsageFilter filter = webUsageFilter(query);
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement("""
            SELECT
              COALESCE(NULLIF(TRIM(model), ''), 'unknown'),
              COALESCE(NULLIF(TRIM(provider), ''), 'Unknown Provider'),
              COALESCE(NULLIF(TRIM(source), ''), 'Unknown source'),
              COALESCE(NULLIF(TRIM(api_key), ''), 'Unrecorded key'),
              COUNT(*),
              COALESCE(SUM(CASE WHEN (status<200 OR status>=400) AND status<>499 THEN 1 ELSE 0 END),0),
              COALESCE(SUM(input_tokens+output_tokens),0)
            FROM requests
            """ + filter.clause + """
            GROUP BY 1,2,3,4
            """)) {
      bind(statement, filter.values);
      Map<String, Kv> models = new LinkedHashMap<>();
      Map<String, Kv> providers = new LinkedHashMap<>();
      Map<String, Kv> sources = new LinkedHashMap<>();
      Map<String, Kv> apiKeys = new LinkedHashMap<>();
      Map<String, Kv> upstreams = new LinkedHashMap<>();
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String model = result.getString(1);
          String provider = result.getString(2);
          String source = result.getString(3);
          String apiKey = result.getString(4);
          long requests = result.getLong(5);
          long failures = result.getLong(6);
          long tokens = result.getLong(7);
          addWebCategory(models, model, requests, failures, tokens);
          addWebCategory(providers, provider, requests, failures, tokens);
          addWebCategory(sources, source, requests, failures, tokens);
          addWebCategory(apiKeys, apiKey, requests, failures, tokens);
          Kv upstream = upstreams.computeIfAbsent(provider, key -> Kv.create()
              .set("key", "provider:" + key).set("label", key).set("provider", key)
              .set("isDefault", false).set("requests", 0L).set("failures", 0L).set("tokens", 0L));
          upstream.set("requests", upstream.getLong("requests") + requests);
          upstream.set("failures", upstream.getLong("failures") + failures);
          upstream.set("tokens", upstream.getLong("tokens") + tokens);
        }
      }
      return Kv.create()
          .set("models", sortedWebCategories(models))
          .set("providers", sortedWebCategories(providers))
          .set("sources", sortedWebCategories(sources))
          .set("apiKeys", sortedWebCategories(apiKeys))
          .set("upstreams", sortedWebCategories(upstreams));
    } catch (Exception error) {
      throw new IllegalStateException("Cannot query web usage analysis", error);
    }
  }

  public Kv webUsageEvents(WebUsageQuery query) {
    WebUsageFilter filter = webUsageFilter(query);
    int pageSize = Math.max(1, Math.min(5000, query.pageSize));
    try (Connection connection = connection()) {
      int total;
      try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM requests" + filter.clause)) {
        bind(statement, filter.values);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          total = result.getInt(1);
        }
      }
      int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
      int page = Math.max(1, Math.min(query.page, totalPages));
      List<Object> pageValues = new ArrayList<>(filter.values);
      pageValues.add(pageSize);
      pageValues.add((long) (page - 1) * pageSize);
      List<Kv> items = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT id,timestamp,model,provider,api_key,source,status,duration,input_tokens,output_tokens,
            cached_tokens,reasoning_tokens,cache_creation_tokens,cost,summary,originator,session_id,thread_id
          FROM requests
          """ + filter.clause + """
          ORDER BY timestamp DESC,id DESC LIMIT ? OFFSET ?
          """)) {
        bind(statement, pageValues);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            items.add(webUsageEvent(result));
          }
        }
      }
      return Kv.create().set("items", items).set("total", total).set("page", page)
          .set("pageSize", pageSize).set("totalPages", totalPages);
    } catch (Exception error) {
      throw new IllegalStateException("Cannot query web usage events", error);
    }
  }

  public Kv webUsageStatus() {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*), MAX(timestamp) FROM requests")) {
      result.next();
      long total = result.getLong(1);
      long last = result.getLong(2);
      return Kv.create().set("state", "collecting")
          .set("message", "Reading request records from the Java proxy database")
          .set("lastCollectedAt", last > 0 ? Instant.ofEpochMilli(last).toString() : null)
          .set("totalRecords", total);
    } catch (Exception error) {
      throw new IllegalStateException("Cannot read web usage status", error);
    }
  }

  private Kv webUsageEvent(ResultSet result) throws Exception {
    String id = result.getString("id");
    String model = result.getString("model");
    String provider = result.getString("provider");
    String apiKey = result.getString("api_key");
    String source = result.getString("source");
    int status = result.getInt("status");
    long duration = result.getLong("duration");
    long input = result.getLong("input_tokens");
    long output = result.getLong("output_tokens");
    long reasoning = result.getLong("reasoning_tokens");
    long cached = result.getLong("cached_tokens");
    long cacheCreation = result.getLong("cache_creation_tokens");
    double cost = result.getDouble("cost");
    String summary = result.getString("summary");
    RequestRecord record = null;
    if (summary != null && !summary.isBlank()) {
      try {
        record = JsonUtils.parse(summary, RequestRecord.class);
      } catch (Exception ignored) {
        // A damaged summary must not hide the aggregate columns.
      }
    }
    if (model == null || model.isBlank()) {
      model = record == null || record.model == null || record.model.isBlank() ? "unknown" : record.model;
    }
    if (provider == null) {
      provider = "";
    }
    if (source == null) {
      source = "";
    }
    boolean canceled = status == 499;
    boolean failed = (status < 200 || status >= 400) && !canceled;
    String apiKeyKey = apiKey == null || apiKey.isBlank() ? "Unrecorded key" : apiKey;
    String sourceDisplay = source.isBlank() ? (provider.isBlank() ? "Unknown source" : provider) : source;
    String requestedModel = record != null && record.requestedModel != null && !record.requestedModel.isBlank()
        ? record.requestedModel : model;
    String responseModel = record == null || record.responseModel == null ? "" : record.responseModel;
    String reasoningEffort = record == null || record.reasoningEffort == null ? "" : record.reasoningEffort;
    String endpoint = record == null || record.path == null ? "" : record.path;
    String originator = result.getString("originator");
    if (originator == null || originator.isBlank()) {
      originator = record == null || record.originator == null ? "" : record.originator;
    }
    String sessionId = result.getString("session_id");
    if (sessionId == null || sessionId.isBlank()) {
      sessionId = record == null || record.session_id == null ? "" : record.session_id;
    }
    String threadId = result.getString("thread_id");
    if (threadId == null || threadId.isBlank()) {
      threadId = record == null || record.thread_id == null ? "" : record.thread_id;
    }
    String failureBody = failed && record != null && record.error != null ? record.error : "";
    Long ttft = record != null && record.firstByteMs > 0 ? record.firstByteMs : null;
    long timestamp = result.getLong("timestamp");
    Kv tokens = Kv.create()
        .set("input_tokens", input)
        .set("output_tokens", output)
        .set("reasoning_tokens", reasoning)
        .set("cache_read_tokens", cached)
        .set("cache_creation_tokens", cacheCreation)
        .set("total_tokens", input + output);
    Kv item = Kv.create()
        .set("id", id)
        .set("row_id", id)
        .set("timestamp", Instant.ofEpochMilli(timestamp).toString())
        .set("latency_ms", duration)
        .set("ttft_ms", ttft)
        .set("source", source)
        .set("source_display", sourceDisplay)
        .set("failed", failed)
        .set("canceled", canceled)
        .set("failure_status", failed ? status : 0)
        .set("failure_body", failureBody)
        .set("provider", provider)
        .set("model", model)
        .set("response_model", responseModel)
        .set("originator", originator)
        .set("session_id", sessionId)
        .set("thread_id", threadId)
        .set("alias", requestedModel)
        .set("reasoning_effort", reasoningEffort)
        .set("service_tier", "")
        .set("response_service_tier", "")
        .set("endpoint", endpoint)
        .set("auth_type", "api-key")
        .set("api_key_hash", apiKeyKey)
        .set("api_key_display", apiKey == null ? "" : apiKey)
        .set("api_key_remark", "")
        .set("tokens", tokens);
    if (cost > 0) {
      item.set("cost", Kv.create().set("total", cost).set("pricing_model", model));
    } else {
      item.set("cost", null);
    }
    return item;
  }

  private List<Kv> webUsageTimeline(Connection connection, WebUsageFilter filter) throws Exception {
    TreeMap<String, Kv> points = new TreeMap<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT
          CASE WHEN timestamp > 0 THEN
            strftime('%Y-%m-%d-%H-', datetime(timestamp / 1000, 'unixepoch', 'localtime')) ||
            CASE WHEN CAST(strftime('%M', datetime(timestamp / 1000, 'unixepoch', 'localtime')) AS INTEGER) < 30
              THEN '00' ELSE '30' END
          ELSE 'unknown-00' END,
          COALESCE(NULLIF(TRIM(model), ''), 'unknown'),
          COUNT(*),
          COALESCE(SUM(CASE WHEN status>=200 AND status<400 THEN 1 ELSE 0 END),0),
          COALESCE(SUM(CASE WHEN (status<200 OR status>=400) AND status<>499 THEN 1 ELSE 0 END),0),
          COALESCE(SUM(CASE WHEN status=499 THEN 1 ELSE 0 END),0),
          COALESCE(SUM(input_tokens+output_tokens),0)
        FROM requests
        """ + filter.clause + """
        GROUP BY 1,2 ORDER BY 1 ASC,2 ASC
        """)) {
      bind(statement, filter.values);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String hour = result.getString(1);
          String model = result.getString(2);
          long requests = result.getLong(3);
          long success = result.getLong(4);
          long failure = result.getLong(5);
          long canceled = result.getLong(6);
          long tokens = result.getLong(7);
          Kv point = points.computeIfAbsent(hour, key -> Kv.create()
              .set("hour", key).set("requests", 0L).set("success", 0L).set("failure", 0L)
              .set("canceled", 0L).set("tokens", 0L).set("models", new ArrayList<Kv>()));
          point.set("requests", point.getLong("requests") + requests);
          point.set("success", point.getLong("success") + success);
          point.set("failure", point.getLong("failure") + failure);
          point.set("canceled", point.getLong("canceled") + canceled);
          point.set("tokens", point.getLong("tokens") + tokens);
          @SuppressWarnings("unchecked")
          List<Kv> models = (List<Kv>) point.get("models");
          Kv modelPoint = null;
          for (Kv item : models) {
            if (model.equals(item.getStr("key"))) {
              modelPoint = item;
              break;
            }
          }
          if (modelPoint == null) {
            modelPoint = Kv.create().set("key", model).set("label", model)
                .set("tokens", 0L).set("requests", 0L);
            models.add(modelPoint);
          }
          modelPoint.set("tokens", modelPoint.getLong("tokens") + tokens);
          modelPoint.set("requests", modelPoint.getLong("requests") + requests);
        }
      }
    }
    for (Kv point : points.values()) {
      @SuppressWarnings("unchecked")
      List<Kv> models = (List<Kv>) point.get("models");
      models.sort((left, right) -> {
        int byTokens = Long.compare(right.getLong("tokens"), left.getLong("tokens"));
        return byTokens != 0 ? byTokens : left.getStr("key").compareTo(right.getStr("key"));
      });
    }
    return new ArrayList<>(points.values());
  }

  private WebUsageFilter webUsageFilter(WebUsageQuery query) {
    List<String> clauses = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    if (query.start != null) {
      clauses.add("timestamp >= ?");
      values.add(query.start);
    }
    if (query.end != null) {
      clauses.add("timestamp <= ?");
      values.add(query.end);
    }
    if (query.model != null && !query.model.isBlank()) {
      clauses.add("COALESCE(NULLIF(TRIM(model), ''), 'unknown') = ? COLLATE NOCASE");
      values.add(query.model.trim());
    }
    if (query.provider != null && !query.provider.isBlank()) {
      clauses.add("COALESCE(NULLIF(TRIM(provider), ''), 'Unknown Provider') = ? COLLATE NOCASE");
      values.add(query.provider.trim());
    }
    if (query.source != null && !query.source.isBlank()) {
      clauses.add("COALESCE(NULLIF(TRIM(source), ''), 'Unknown source') = ? COLLATE NOCASE");
      values.add(query.source.trim());
    }
    if (query.originator != null && !query.originator.isBlank()) {
      clauses.add("originator = ? COLLATE NOCASE");
      values.add(query.originator.trim());
    }
    if (query.sessionId != null && !query.sessionId.isBlank()) {
      clauses.add("session_id = ? COLLATE NOCASE");
      values.add(query.sessionId.trim());
    }
    if (query.threadId != null && !query.threadId.isBlank()) {
      clauses.add("thread_id = ? COLLATE NOCASE");
      values.add(query.threadId.trim());
    }
    if (query.apiKey != null && !query.apiKey.isBlank()) {
      clauses.add("COALESCE(NULLIF(TRIM(api_key), ''), 'Unrecorded key') = ? COLLATE NOCASE");
      values.add(query.apiKey.trim());
    }
    if (query.upstream != null && !query.upstream.isBlank()) {
      String upstream = query.upstream.trim();
      if (upstream.startsWith("provider:")) {
        clauses.add("COALESCE(NULLIF(TRIM(provider), ''), 'Unknown Provider') = ? COLLATE NOCASE");
        values.add(upstream.substring("provider:".length()));
      } else {
        clauses.add("0 = 1");
      }
    }
    if (Boolean.TRUE.equals(query.failed)) {
      clauses.add("(status<200 OR status>=400) AND status<>499");
    } else if (Boolean.FALSE.equals(query.failed)) {
      clauses.add("status>=200 AND status<400");
    }
    if (Boolean.TRUE.equals(query.canceled)) {
      clauses.add("status=499");
    } else if (Boolean.FALSE.equals(query.canceled)) {
      clauses.add("status<>499");
    }
    String clause = clauses.isEmpty() ? " " : " WHERE " + String.join(" AND ", clauses) + " ";
    return new WebUsageFilter(clause, values);
  }

  private void addWebCategory(Map<String, Kv> categories, String key, long requests, long failures, long tokens) {
    Kv item = categories.computeIfAbsent(key, ignored -> Kv.create().set("key", key).set("label", key)
        .set("requests", 0L).set("failures", 0L).set("tokens", 0L));
    item.set("requests", item.getLong("requests") + requests);
    item.set("failures", item.getLong("failures") + failures);
    item.set("tokens", item.getLong("tokens") + tokens);
  }

  private List<Kv> sortedWebCategories(Map<String, Kv> categories) {
    List<Kv> values = new ArrayList<>(categories.values());
    values.sort((left, right) -> {
      int byTokens = Long.compare(right.getLong("tokens"), left.getLong("tokens"));
      if (byTokens != 0) {
        return byTokens;
      }
      int byRequests = Long.compare(right.getLong("requests"), left.getLong("requests"));
      return byRequests != 0 ? byRequests : left.getStr("key").compareTo(right.getStr("key"));
    });
    return values;
  }

  private double webUsageWindowMinutes(WebUsageQuery query, Long first, Long last) {
    long start = query.start != null ? query.start : first == null ? 0 : first;
    long end = query.end != null ? query.end : last == null ? start : last;
    return Math.max(1.0, (end - start) / 60000.0);
  }

  private boolean columnExists(Statement statement, String table, String column) throws Exception {
    try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (columns.next()) {
        if (column.equals(columns.getString("name"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static final class WebUsageFilter {
    private final String clause;
    private final List<Object> values;

    private WebUsageFilter(String clause, List<Object> values) {
      this.clause = clause;
      this.values = values;
    }
  }

  public void prune(int days) {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement("DELETE FROM requests WHERE timestamp<?")) {
      statement.setLong(1, System.currentTimeMillis() - days * 86400000L);
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot prune usage", e);
    }
  }

  private void filter(StringBuilder where, List<Object> values, String column, String value) {
    if (value != null && !value.isBlank()) {
      where.append(" AND ").append(column).append("=?");
      values.add(value);
    }
  }

  private void bind(PreparedStatement statement, List<Object> values) throws Exception {
    for (int i = 0; i < values.size(); i++) {
      statement.setObject(i + 1, values.get(i));
    }
  }
}
