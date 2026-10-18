package nexus.io.llm.proxy.service;

import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.model.exception.BusinessException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolImageConversionTest {
  private Kv convert(Kv image, String target) {
    Kv request = Kv.create().set("input", List.of(Kv.create().set("role", "user").set("content", List.of(image))));
    return new ProtocolConversionService().request(request, "responses", target, "test", false);
  }

  @Test public void convertsStringAndObjectImageUrls() {
    for (Object value : List.of("data:image/png;base64,aGVsbG8=", Kv.create().set("url", "data:image/png;base64,aGVsbG8="))) {
      Kv result = convert(Kv.create().set("type", "input_image").set("image_url", value), "gemini");
      Kv content = ProxyJson.object(ProxyJson.list(result.get("contents")).getFirst());
      Kv part = ProxyJson.object(ProxyJson.list(content.get("parts")).getFirst());
      assertEquals("aGVsbG8=", ProxyJson.object(part.get("inlineData")).getStr("data"));
      assertEquals("image/png", ProxyJson.object(part.get("inlineData")).getStr("mimeType"));
    }
  }

  @Test public void preservesRemoteImageUrl() {
    Kv result = convert(Kv.create().set("type", "input_image").set("image_url", "https://example.com/image.png"), "gemini");
    assertTrue(result.toString().contains("https://example.com/image.png"));
  }

  @Test public void rejectsMissingFileIdAndMalformedImagesWith400() {
    for (Kv image : List.of(Kv.create().set("type", "input_image"),
        Kv.create().set("type", "input_image").set("file_id", "file-test"),
        Kv.create().set("type", "input_image").set("image_url", "data:image/png;base64,"),
        Kv.create().set("type", "input_image").set("image_url", "file:///private/image.png"))) {
      for (String target : List.of("gemini", "anthropic", "openai")) {
        try { convert(image, target); fail("Expected image validation error"); }
        catch (BusinessException error) { assertEquals(400, error.getStatus()); }
      }
    }
  }

  @Test public void acceptsChatImageUrlString() {
    Kv body = Kv.create().set("messages", List.of(Kv.create().set("role", "user").set("content",
        List.of(Kv.create().set("type", "image_url").set("image_url", "data:image/png;base64,aGVsbG8=")))));
    assertTrue(new ProtocolConversionService().request(body, "openai", "anthropic", "test", false).toString().contains("aGVsbG8="));
  }
}
