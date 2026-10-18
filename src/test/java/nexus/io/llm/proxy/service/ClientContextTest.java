package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.RequestRecord;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientContextTest {
  @Test
  public void recognizesClaudeCliAcrossVersions() {
    RequestRecord record = new RequestRecord();
    for (String agent : new String[] {"claude-cli/2.1.292 (external, cli)", "claude-cli/3.0.0 (cli)"}) {
      record.requestHeaders = Kv.create().set("User-Agent", agent);
      HeaderService.captureClientContext(record);
      assertEquals("claude-code-cli", record.originator);
    }
    record.requestHeaders = Kv.create().set("user-agent", "other-client/1.0").set("originator", "codex-tui");
    HeaderService.captureClientContext(record);
    assertEquals("codex-tui", record.originator);
  }
  @Test
  public void capturesClaudeCodeSessionWhenStandardHeadersAreBlank() {
    RequestRecord record = new RequestRecord();
    record.requestHeaders = Kv.create().set("session-id", " ").set("session_id", "")
        .set("X-Claude-Code-Session-ID", " 2adf332b-17bb-432b-b077-4264c8984053 ");
    HeaderService.captureClientContext(record);
    assertEquals("2adf332b-17bb-432b-b077-4264c8984053", record.session_id);
    record.requestHeaders.set("session_id", "standard-session");
    HeaderService.captureClientContext(record);
    assertEquals("standard-session", record.session_id);
    record.requestHeaders.set("session-id", "preferred-session");
    HeaderService.captureClientContext(record);
    assertEquals("preferred-session", record.session_id);
  }

  @Test
  public void capturesCaseInsensitiveHeadersAndUnderscoreAliases() {
    RequestRecord record = new RequestRecord();
    record.requestHeaders = Kv.create().set("Originator", " codex-tui ")
        .set("Session-ID", "session-1").set("thread_id", "thread-1");
    HeaderService.captureClientContext(record);
    assertEquals("codex-tui", record.originator);
    assertEquals("session-1", record.session_id);
    assertEquals("thread-1", record.thread_id);
    record.requestHeaders = Kv.create();
    HeaderService.captureClientContext(record);
    assertNull(record.originator);
    assertNull(record.session_id);
    assertNull(record.thread_id);
  }
  @Test
  public void persistsContextAndFiltersAfterReopeningDatabase() throws Exception {
    java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("client-context");
    String previous = nexus.io.tio.utils.environment.EnvUtils.get("proxy.data-dir");
    try {
      nexus.io.tio.utils.environment.EnvUtils.set("proxy.data-dir", directory.toString());
      StorageService storage = new StorageService();
      RequestRecord record = new RequestRecord();
      record.id = "context-test";
      record.timestamp = 1000;
      record.status = 200;
      record.requestHeaders = Kv.create().set("originator", "codex-tui")
          .set("x-claude-code-session-id", "session-1").set("thread-id", "thread-1");
      storage.record(record);
      storage = new StorageService();
      assertEquals("session-1", storage.detail(record.id).session_id);
      assertEquals("thread-1", storage.queuedUsage(10).get(0).thread_id);
      nexus.io.llm.proxy.model.UsageQuery query = new nexus.io.llm.proxy.model.UsageQuery();
      query.originator = "codex-tui";
      query.session_id = "session-1";
      query.thread_id = "thread-1";
      assertEquals(1L, ((Kv) storage.usage(query).get("stats")).getLong("total").longValue());
      query.session_id = "' OR 1=1 --";
      assertEquals(0L, ((Kv) storage.usage(query).get("stats")).getLong("total").longValue());
      try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("proxy.db"));
          java.sql.Statement statement = connection.createStatement();
          java.sql.ResultSet row = statement.executeQuery("SELECT originator, session_id, thread_id FROM requests")) {
        assertTrue(row.next());
        assertEquals("codex-tui", row.getString(1));
        assertEquals("session-1", row.getString(2));
        assertEquals("thread-1", row.getString(3));
      }
    } finally {
      nexus.io.tio.utils.environment.EnvUtils.set("proxy.data-dir", previous);
      try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(directory)) {
        for (java.nio.file.Path file : files.toList()) java.nio.file.Files.delete(file);
      }
      java.nio.file.Files.delete(directory);
    }
  }
}
