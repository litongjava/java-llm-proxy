package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;

public class SseFrameInputStreamTest extends TestCase {
  public void testFramesPreserveUtf8AndCrLfAcrossSmallReads() throws Exception {
    String wire = "event: message\r\ndata: {\"text\":\"你好\"}\r\n\r\ndata: [DONE]\n\n";
    List<String> seen = new ArrayList<>();
    try (SseFrameInputStream input = new SseFrameInputStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), 1024, frame -> {
      seen.add(new String(frame, StandardCharsets.UTF_8));
      return frame;
    })) {
      assertEquals(wire, new String(input.readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(2, seen.size());
      assertTrue(seen.getFirst().contains("你好"));
      assertEquals(-1, input.read());
    }
  }

  public void testDropAndTrailingEvent() throws Exception {
    try (SseFrameInputStream input = new SseFrameInputStream(new ByteArrayInputStream("data: skip\n\ndata: keep".getBytes(StandardCharsets.UTF_8)), 100,
        frame -> new String(frame, StandardCharsets.UTF_8).contains("skip") ? new byte[0] : frame)) {
      assertEquals("data: keep", new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  public void testEventLimit() throws Exception {
    try (SseFrameInputStream input = new SseFrameInputStream(new ByteArrayInputStream(new byte[11]), 10, frame -> frame)) {
      try { input.read(); fail("Expected event limit"); }
      catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
    }
  }
}
