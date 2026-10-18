package nexus.io.llm.proxy.service;

import nexus.io.llm.proxy.model.RequestRecord;
import org.junit.Test;
import static org.junit.Assert.*;

public class AnthropicUsageTest {
  @Test
  public void totalsSeparateAnthropicInputCategoriesWithoutCountingStreamEventsTwice() {
    RequestRecord record = new RequestRecord();
    ProtocolFormatService service = new ProtocolFormatService();
    String usage = "{\"input_tokens\":2,\"cache_read_input_tokens\":159967,\"cache_creation_input_tokens\":621,\"output_tokens\":8}";
    service.usage(record, "data: {\"type\":\"message_start\",\"message\":{\"usage\":" + usage + "}}\n\n");
    service.usage(record, "data: {\"type\":\"message_delta\",\"usage\":" + usage.replace(":8}", ":3314}") + "}\n\n");
    service.usage(record, "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":3314}}\n\n");
    assertEquals(160590, record.inputTokens);
    assertEquals(159967, record.cachedTokens);
    assertEquals(621, record.cacheCreationTokens);
    assertEquals(3314, record.outputTokens);
  }

  @Test
  public void openAiInputAlreadyIncludesCachedTokens() {
    RequestRecord record = new RequestRecord();
    new ProtocolFormatService().usage(record, "{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":80},\"output_tokens\":20}}");
    assertEquals(100, record.inputTokens);
    assertEquals(80, record.cachedTokens);
    assertEquals(0, record.cacheCreationTokens);
  }
}
