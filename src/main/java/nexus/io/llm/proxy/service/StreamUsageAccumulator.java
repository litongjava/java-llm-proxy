package nexus.io.llm.proxy.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import nexus.io.llm.proxy.model.RequestRecord;

public final class StreamUsageAccumulator {
  private static final int MAX_EVENT_BYTES = 1024 * 1024;
  private final ByteArrayOutputStream event = new ByteArrayOutputStream();
  private final ProtocolFormatService formatter;
  private final RequestRecord record;
  private int lineLength;
  private boolean overflow;

  public StreamUsageAccumulator(ProtocolFormatService formatter, RequestRecord record) {
    this.formatter = formatter;
    this.record = record;
  }

  public void accept(byte[] bytes, int count) {
    for (int i = 0; i < count; i++) {
      byte value = bytes[i];
      if (event.size() < MAX_EVENT_BYTES) {
        event.write(value);
      } else {
        overflow = true;
      }
      if (value == '\n') {
        if (lineLength == 0) {
          finishEvent();
        }
        lineLength = 0;
      } else if (value != '\r') {
        lineLength++;
      }
    }
  }

  public void finishEvent() {
    if (!overflow && event.size() > 0) {
      formatter.usage(record, event.toString(StandardCharsets.UTF_8));
    }
    event.reset();
    overflow = false;
  }
}
