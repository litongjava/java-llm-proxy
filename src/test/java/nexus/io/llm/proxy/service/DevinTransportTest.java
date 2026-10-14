package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class DevinTransportTest {
  private byte[] frame(int flag, byte[] body) {
    return ByteBuffer.allocate(body.length + 5).put((byte) flag).putInt(body.length).put(body).array();
  }
  @Test
  public void decodesCompressedTextToolsUsageAndTrailer() throws Exception {
    byte[] payload = new ProtoWire().text(3, "Hello from Devin").text(9, "reasoning")
        .bytes(6, new ProtoWire().text(1, "weather-call").text(2, "weather").text(3, "{\"city\":\"Tokyo\"}").bytes())
        .bytes(7, new ProtoWire().number(2, 11).number(3, 7).number(5, 3).bytes()).bytes();
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(payload); }
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    wire.write(frame(1, compressed.toByteArray()));
    wire.write(frame(2, "{}".getBytes(StandardCharsets.UTF_8)));
    try (DevinTransportService.DevinStream stream = new DevinTransportService.DevinStream(new ByteArrayInputStream(wire.toByteArray()), "test")) {
      String sse = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(sse.contains("Hello from Devin"));
      assertTrue(sse.contains("weather"));
      assertTrue(sse.contains("[DONE]"));
      Kv usage = ProxyJson.object(stream.completed().get("usage"));
      assertEquals(14, ProxyJson.number(usage, "prompt_tokens", 0));
      assertEquals(7, ProxyJson.number(usage, "completion_tokens", 0));
    }
  }
  @Test(expected = IOException.class)
  public void rejectsMissingTrailer() throws Exception {
    try (DevinTransportService.DevinStream stream = new DevinTransportService.DevinStream(new ByteArrayInputStream(frame(0, new ProtoWire().text(3, "partial").bytes())), "test")) {
      stream.readAllBytes();
    }
  }
  @Test(expected = IOException.class)
  public void rejectsErrorTrailer() throws Exception {
    byte[] trailer = "{\"error\":{\"code\":\"resource_exhausted\"}}".getBytes(StandardCharsets.UTF_8);
    try (DevinTransportService.DevinStream stream = new DevinTransportService.DevinStream(new ByteArrayInputStream(frame(2, trailer)), "test")) { stream.readAllBytes(); }
  }
}
