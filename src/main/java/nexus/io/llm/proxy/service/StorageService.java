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
import java.util.List;
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
              cost REAL, summary TEXT NOT NULL, body TEXT NOT NULL
            )
            """);
        for (String column : List.of("originator", "session_id", "thread_id")) {
          boolean exists = false;
          try (ResultSet columns = statement.executeQuery("PRAGMA table_info(requests)")) {
            while (columns.next()) { if (column.equals(columns.getString("name"))) exists = true; }
          }
          if (!exists) statement.execute("ALTER TABLE requests ADD COLUMN " + column + " TEXT");
          statement.execute("CREATE INDEX IF NOT EXISTS requests_" + column + " ON requests(" + column + ", timestamp DESC)");
        }
        statement.execute("CREATE INDEX IF NOT EXISTS requests_time ON requests(timestamp DESC)");
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
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("SELECT body FROM upstream_tasks WHERE id=? AND owner=?")) {
      statement.setString(1, id);
      statement.setString(2, owner);
      try (ResultSet result = statement.executeQuery()) { return result.next() ? JsonUtils.parse(result.getString(1), UpstreamTask.class) : null; }
    }
  }

  public List<RecentRequestBucket> recentRequests(String provider) {
    long current = System.currentTimeMillis() / 600000;
    long first = current - 19;
    List<RecentRequestBucket> buckets = new ArrayList<>();
    DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    for (long bucket = first; bucket <= current; bucket++) {
      RecentRequestBucket item = new RecentRequestBucket();
      item.time = clock.format(Instant.ofEpochMilli(bucket * 600000)) + "-" + clock.format(Instant.ofEpochMilli((bucket + 1) * 600000));
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
    } catch (Exception error) { throw new IllegalStateException("Cannot read recent request metrics", error); }
  }

  public ProxySettings settings() {
    try (Connection connection = connection(); Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT body FROM settings WHERE id=1")) {
      return result.next() ? JsonUtils.parse(result.getString(1), ProxySettings.class) : new ProxySettings();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read settings", e);
    }
  }

  public synchronized void saveSettings(ProxySettings settings) {
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
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
          input_tokens,output_tokens,cached_tokens,reasoning_tokens,cost,summary,body,originator,session_id,thread_id)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """)) {
      Object[] values = {record.id, record.timestamp, record.model, record.provider, record.apiKeyName,
          record.source, record.status, record.durationMs, record.inputTokens, record.outputTokens,
          record.cachedTokens, record.reasoningTokens, record.cost, JsonUtils.toJson(summary), body, record.originator, record.session_id, record.thread_id};
      bind(statement, Arrays.asList(values));
      statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot save request record", e);
    }
  }

  public RequestRecord detail(String id) {
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
        "SELECT body FROM requests WHERE id=?")) {
      statement.setString(1, id);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? JsonUtils.parse(result.getString(1), RequestRecord.class) : null;
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
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM usage_queue WHERE id=?")) {
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
          stats.set("total", result.getLong(1)).set("success", result.getLong(2))
              .set("inputTokens", result.getLong(3)).set("outputTokens", result.getLong(4))
              .set("cachedTokens", result.getLong(5)).set("reasoningTokens", result.getLong(6))
              .set("cost", result.getDouble(7)).set("averageDuration", result.getDouble(8));
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
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT timestamp/3600000*3600000,count(*),sum(input_tokens+output_tokens) FROM requests"
          + where + " GROUP BY timestamp/3600000 ORDER BY 1 DESC LIMIT 168")) {
        bind(statement, values);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            trend.add(Kv.create().set("time", result.getLong(1)).set("requests", result.getLong(2)).set("tokens", result.getLong(3)));
          }
        }
      }
      return Kv.create().set("stats", stats).set("records", records).set("trend", trend).set("page", query.page).set("pageSize", query.pageSize);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot query usage", e);
    }
  }

  public void prune(int days) {
    try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM requests WHERE timestamp<?")) {
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
