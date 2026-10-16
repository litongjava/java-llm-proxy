package nexus.io.llm.proxy.service;

import nexus.io.llm.proxy.model.RequestRecord;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolFormatImageTest {
  private String prompt(String content) {
    RequestRecord record = new RequestRecord();
    record.path = "/v1/responses";
    record.protocol = "responses";
    record.requestBody = "{\"input\":[{\"role\":\"user\",\"content\":[" + content + "]}]}";
    return new ProtocolFormatService().format(record).getPrompt();
  }

  @Test public void rendersCommonImageBlocksWithoutDroppingText() {
    for (String block : new String[] {
        "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,aGVsbG8=\"}}",
        "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,aGVsbG8=\"}",
        "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"aGVsbG8=\"}}",
        "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"aGVsbG8=\"}}"
    }) {
      String result = prompt("{\"type\":\"text\",\"text\":\"Describe this\"}," + block);
      assertTrue(result.contains("Describe this"));
      assertTrue(result.contains("![Image](<data:image/png;base64,aGVsbG8=>)"));
    }
    assertTrue(prompt("{\"type\":\"input_image\",\"image_url\":\"https://example.com/image.png\"}").contains("![Image](<https://example.com/image.png>)"));
    assertFalse(prompt("{\"type\":\"input_image\",\"image_url\":\"javascript:alert(1)\"}").contains("![Image]"));
  }

  @Test public void rendersGeneratedImagesInJsonAndStreamResponses() {
    RequestRecord record = new RequestRecord();
    record.path = "/v1/responses";
    record.protocol = "responses";
    record.requestBody = "{}";
    record.responseBody = "{\"output\":[{\"type\":\"image_generation_call\",\"result\":\"aGVsbG8=\"}]}";
    assertTrue(new ProtocolFormatService().format(record).getResponse().contains("data:image/png;base64,aGVsbG8="));
    record.stream = true;
    record.responseBody = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"result\":\"aGVsbG8=\"}}\n\n";
    assertTrue(new ProtocolFormatService().format(record).getResponse().contains("data:image/png;base64,aGVsbG8="));
  }
}
