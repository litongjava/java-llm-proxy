package nexus.io.llm.proxy.service;

import com.alibaba.fastjson2.JSON;
import java.nio.file.*;
import java.util.*;
import nexus.io.llm.proxy.model.RequestRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RequestImageServiceTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void storesCommonProtocolsOnceAndFormatsRelativeImages() throws Exception {
    Path root = temporary.getRoot().toPath();
    RequestImageService service = new RequestImageService(root);
    String[] blocks = {
        "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,aGVsbG8=\"}}",
        "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,aGVsbG8=\"}",
        "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"aGVsbG8=\"}}",
        "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"aGVsbG8=\"}}",
        "{\"inline_data\":{\"mime_type\":\"image/png\",\"data\":\"aGVsbG8=\"}}"
    };
    String expected = "request-images/2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.png";
    for (String block : blocks) {
      String original = "{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"keep me\"}," + block + "]}]}";
      String stored = service.externalize(original);
      assertTrue(stored.contains(expected));
      assertFalse(stored.contains("aGVsbG8="));
      assertTrue(original.contains("aGVsbG8="));
      assertEquals(stored, service.externalize(stored));
      RequestRecord record = new RequestRecord();
      record.requestBody = stored; record.path = "/v1/responses"; record.protocol = "responses";
      String prompt = new ProtocolFormatService().format(record).getPrompt();
      assertTrue(prompt.contains("keep me"));
      assertTrue(prompt.contains("![Image](<" + expected + ">)"));
    }
    assertArrayEquals("hello".getBytes(), service.read(expected));
    try (var files = Files.list(root.resolve("request-images"))) { assertEquals(1L, files.count()); }
  }

  @Test public void extractsBeforeCaptureLimitAndPreservesOtherFields() {
    RequestImageService service = new RequestImageService(temporary.getRoot().toPath());
    byte[] image = new byte[5 * 1024 * 1024];
    Arrays.fill(image, (byte) 42);
    String original = JSON.toJSONString(Map.of("image_url", "data:image/png;base64," + Base64.getEncoder().encodeToString(image), "tail", "still here"));
    String stored = service.externalize(original);
    assertTrue(stored.length() < 200);
    assertTrue(stored.contains("still here"));
    assertEquals(stored, Utf8Capture.prefix(stored, 4194304));
  }

  @Test public void invalidOrUnsupportedInputsRemainUnchanged() throws Exception {
    RequestImageService service = new RequestImageService(temporary.getRoot().toPath());
    for (String body : List.of("{broken", "{\"url\":\"https://example.com/image.png\"}",
        "{\"url\":\"data:image/png;base64,???\"}", "{\"url\":\"data:image/svg+xml;base64,aGVsbG8=\"}")) {
      assertEquals(body, service.externalize(body));
    }
    assertFalse(RequestImageService.isImagePath("request-images/../../config.yaml"));
    assertFalse(RequestImageService.isImagePath("/etc/passwd"));
    assertFalse(RequestImageService.isImagePath("request-images/" + "a".repeat(64) + ".svg"));
    try { service.read("../config.yaml"); fail("Traversal accepted"); }
    catch (IllegalArgumentException expected) { }
    try { service.read("request-images/" + "a".repeat(64) + ".png"); fail("Missing image accepted"); }
    catch (NoSuchFileException expected) { }
  }

  @Test public void diskFailurePreservesOriginalBody() throws Exception {
    Path root = temporary.getRoot().toPath();
    Files.writeString(root.resolve("request-images"), "not a directory");
    String body = "{\"url\":\"data:image/png;base64,aGVsbG8=\"}";
    assertEquals(body, new RequestImageService(root).externalize(body));
  }

  @Test public void persistsRelativePathsInSqliteAndKeepsUsageSummarySmall() throws Exception {
    String previous = nexus.io.tio.utils.environment.EnvUtils.get("proxy.data-dir");
    Path root = temporary.getRoot().toPath();
    try {
      nexus.io.tio.utils.environment.EnvUtils.set("proxy.data-dir", root.toString());
      StorageService storage = new StorageService();
      RequestRecord record = new RequestRecord();
      record.id = "image-persistence"; record.path = "/v1/responses";
      record.requestBody = "{\"image_url\":\"data:image/png;base64,aGVsbG8=\"}";
      record.upstreamRequestBody = record.requestBody;
      storage.record(record);
      RequestRecord loaded = storage.detail(record.id);
      assertTrue(loaded.requestBody.contains("request-images/"));
      assertFalse(loaded.requestBody.contains("base64"));
      assertEquals(loaded.requestBody, loaded.upstreamRequestBody);
      assertNull(storage.queuedUsage(1).get(0).requestBody);
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("proxy.db"));
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT body FROM requests")) {
        assertTrue(rows.next());
        assertFalse(rows.getString(1).contains("aGVsbG8="));
      }
      try (var files = Files.list(root.resolve("request-images"))) { assertEquals(1L, files.count()); }
    } finally { nexus.io.tio.utils.environment.EnvUtils.set("proxy.data-dir", previous); }
  }
}
