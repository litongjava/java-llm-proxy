package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/** OpenAI wire adaptation shared by CodeBuddy and WorkBuddy. */
public class BuddyTransportService {
  public Kv payload(Kv payload, String model, String endpoint) {
    BusinessException.require("/chat/completions".equals(endpoint), 400, "BUDDY_ENDPOINT_UNSUPPORTED", "Buddy supports chat completions only");
    payload.set("stream", true).set("stream_options", ProxyJson.object(payload.get("stream_options")).set("include_usage", true));
    if (model.toLowerCase(Locale.ROOT).startsWith("deepseek") && payload.get("reasoning_effort") != null) {
      String effort = payload.getStr("reasoning_effort");
      payload.set("thinking", Kv.create().set("type", "none".equals(effort) ? "disabled" : "enabled"));
    }
    return payload;
  }

  public Response response(Response original, boolean clientStream) throws IOException {
    if (!original.isSuccessful() || original.body() == null) { return original; }
    if (!original.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("text/event-stream")) {
      original.close();
      throw new IOException("Buddy returned an unexpected response format");
    }
    int maximum = Math.max(1024, EnvUtils.getInt("proxy.oauth.buddy.max-event-bytes", 4 * 1024 * 1024));
    SseFrameInputStream stream = new SseFrameInputStream(original.body().byteStream(), maximum, frame -> {
      StringBuilder data = new StringBuilder();
      for (String line : new String(frame, StandardCharsets.UTF_8).split("\\r?\\n")) {
        if (line.startsWith("data:")) { data.append(line.substring(5).stripLeading()).append('\n'); }
      }
      String text = data.toString().trim();
      if (!text.isBlank() && !"[DONE]".equals(text)) {
        Kv chunk;
        try { chunk = ProxyJson.parse(text); }
        catch (RuntimeException error) { throw new IOException("Buddy returned an invalid stream event"); }
        if (chunk.get("error") != null || (!chunk.containsKey("choices") && ProxyJson.number(chunk, "code", 0) != 0)) {
          throw new IOException("Buddy reported a stream error");
        }
      }
      return frame;
    });
    BufferedSource source = Okio.buffer(Okio.source(stream));
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() { return MediaType.get("text/event-stream"); }
      @Override public long contentLength() { return -1; }
      @Override public BufferedSource source() { return source; }
    };
    Response normalized = original.newBuilder().removeHeader("Content-Length").body(body).build();
    // Both providers emit OpenAI chunks, including tool fragments and final usage.
    return clientStream ? normalized : Aop.get(ClineTransportService.class).response(normalized, false);
  }
}
