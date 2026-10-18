package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamOutputTest {
  private boolean output(String json) { return StreamOutputService.hasOutput(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8)); }

  @Test public void ignoresTransportAndEmptyEvents() {
    assertFalse(StreamOutputService.hasOutput(": ping\n\n".getBytes(StandardCharsets.UTF_8)));
    assertFalse(output("[DONE]"));
    assertFalse(output("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}"));
    assertFalse(output("{\"type\":\"response.created\",\"response\":{\"id\":\"abc\"}}"));
    assertFalse(output("{\"usage\":{\"output_tokens\":20},\"choices\":[]}"));
    assertFalse(output("{\"type\":\"response.output_text.delta\",\"delta\":\"\"}"));
  }

  @Test public void recognizesTextReasoningAndToolArguments() {
    assertTrue(output("{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}"));
    assertTrue(output("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}"));
    assertTrue(output("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"));
    assertTrue(output("{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{}\"}"));
    assertTrue(output("{\"type\":\"content_block_delta\",\"delta\":{\"thinking\":\"think\"}}"));
    assertTrue(output("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}"));
  }
}
