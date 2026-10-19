package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.sql.DriverManager;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderCountsTest {
  @Test public void countsUseCoveringIndexAndPreserveSuccessDefinition() throws Exception {
    var directory = Files.createTempDirectory("provider-counts");
    String previous = EnvUtils.get("proxy.data-dir");
    try {
      EnvUtils.set("proxy.data-dir", directory.toString());
      StorageService storage = new StorageService();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("proxy.db"));
           var statement = connection.createStatement()) {
        for (int status : new int[] {199, 200, 302, 400, 499, 502}) {
          statement.executeUpdate("INSERT INTO requests(id,timestamp,provider,status,summary,body) VALUES ('" + status + "',1,'test'," + status + ",'{}','{}')");
        }
        var counts = storage.providerCounts("test");
        assertEquals(6L, ((Number) counts.get("total")).longValue());
        assertEquals(2L, ((Number) counts.get("success")).longValue());
        assertEquals(0L, ((Number) storage.providerCounts("missing").get("total")).longValue());
        try (var plan = statement.executeQuery("EXPLAIN QUERY PLAN SELECT count(*),sum(CASE WHEN status>=200 AND status<400 THEN 1 ELSE 0 END) FROM requests WHERE provider='test'")) {
          assertTrue(plan.next());
          assertTrue(plan.getString(4).contains("COVERING INDEX requests_provider_time_status"));
        }
      }
    } finally {
      EnvUtils.set("proxy.data-dir", previous);
      try (var files = Files.walk(directory)) {
        for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(file); }
      }
    }
  }
}
