package nexus.io.llm.proxy.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import nexus.io.llm.proxy.model.RequestRecord;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Captures upstream bytes before provider unwrapping and protocol conversion. */
public class CapturedResponseBody extends ResponseBody {
  private final ResponseBody original;
  private final BufferedSource source;
  public CapturedResponseBody(ResponseBody body, RequestRecord record, int limit) {
    original = body;
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    source = Okio.buffer(new ForwardingSource(body.source()) {
      @Override
      public long read(Buffer sink, long count) throws IOException {
        long start = sink.size();
        long read = super.read(sink, count);
        if (read > 0) {
          long keep = Math.min(read, Math.max(0, limit - capture.size()));
          Buffer copied = new Buffer();
          sink.copyTo(copied, start, keep);
          capture.write(copied.readByteArray());
          record.responseTruncated |= keep < read;
        }
        if (read < 0) { record.upstreamResponseBody = capture.toString(StandardCharsets.UTF_8); }
        return read;
      }
      @Override
      public void close() throws IOException {
        record.upstreamResponseBody = capture.toString(StandardCharsets.UTF_8);
        super.close();
      }
    });
  }
  @Override public MediaType contentType() { return original.contentType(); }
  @Override public long contentLength() { return original.contentLength(); }
  @Override public BufferedSource source() { return source; }
}
