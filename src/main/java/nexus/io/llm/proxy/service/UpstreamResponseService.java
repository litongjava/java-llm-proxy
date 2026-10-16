package nexus.io.llm.proxy.service;

import java.io.IOException;
import okhttp3.Response;
import okio.BufferedSource;

/** Recover omitted SSE headers without consuming or buffering the full response. */
public final class UpstreamResponseService {
  private UpstreamResponseService() { }

  public static Response normalizeContentType(Response response) throws IOException {
    String contentType = response.header("Content-Type");
    if (!response.isSuccessful() || response.body() == null
        || (contentType != null && !contentType.isBlank())) { return response; }
    BufferedSource prefix = response.body().source().peek();
    // Stop as soon as a field prefix is known; waiting for a whole event would
    // delay delivery on long-lived streams and large tool-call arguments.
    for (int skipped = 0; skipped < 64 && prefix.request(1); skipped++) {
      byte next = prefix.getBuffer().getByte(0);
      if (next != '\r' && next != '\n' && next != ' ' && next != '\t') { break; }
      prefix.readByte();
    }
    StringBuilder field = new StringBuilder();
    for (int index = 0; index <= 5 && prefix.request(1); index++) {
      byte next = prefix.readByte();
      if (next == ':') {
        if (field.isEmpty() || field.toString().equals("event") || field.toString().equals("data")
            || field.toString().equals("id") || field.toString().equals("retry")) {
          return response.newBuilder().header("Content-Type", "text/event-stream").build();
        }
        break;
      }
      if (next < 'a' || next > 'z') { break; }
      field.append((char) next);
    }
    return response;
  }
}
