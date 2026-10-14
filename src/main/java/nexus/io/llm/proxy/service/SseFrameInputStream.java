package nexus.io.llm.proxy.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Applies stream hooks to complete SSE frames, preserving wire bytes and UTF-8 boundaries. */
public class SseFrameInputStream extends InputStream {
  @FunctionalInterface public interface Transform { byte[] apply(byte[] frame) throws Exception; }
  private final InputStream source;
  private final Transform transform;
  private final int maximum;
  private byte[] output = new byte[0];
  private int position;
  private boolean ended;

  public SseFrameInputStream(InputStream source, int maximum, Transform transform) {
    this.source = source;
    this.maximum = maximum;
    this.transform = transform;
  }

  public byte[] nextFrame() throws IOException {
    while (!ended) {
      ByteArrayOutputStream frame = new ByteArrayOutputStream();
      int lineLength = 0;
      while (true) {
        int value = source.read();
        if (value == -1) { ended = true; break; }
        frame.write(value);
        if (frame.size() > maximum) { throw new IOException("SSE event exceeds the configured limit"); }
        if (value == '\n') {
          if (lineLength == 0) { break; }
          lineLength = 0;
        } else if (value != '\r') { lineLength++; }
      }
      if (frame.size() == 0) { return null; }
      try {
        byte[] result = transform.apply(frame.toByteArray());
        if (result != null && result.length > 0) { return result; }
      } catch (IOException error) { throw error; }
      catch (Exception error) { throw new IOException("SSE plugin transformation failed", error); }
    }
    return null;
  }

  @Override public int read() throws IOException {
    byte[] single = new byte[1];
    return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
  }

  @Override public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (length == 0) { return 0; }
    if (position == output.length) {
      output = nextFrame();
      position = 0;
      if (output == null) { output = new byte[0]; return -1; }
    }
    int count = Math.min(length, output.length - position);
    System.arraycopy(output, position, bytes, offset, count);
    position += count;
    return count;
  }

  @Override public void close() throws IOException { source.close(); }
}
