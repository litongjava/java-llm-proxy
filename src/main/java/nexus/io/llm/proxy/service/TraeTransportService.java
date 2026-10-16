package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/** Converts SOLO events incrementally, including tool-call and reasoning deltas. */
public class TraeTransportService {
  public Response response(Response response, boolean clientStream, String model) throws Exception {
    if (!response.isSuccessful() || response.body() == null) { return response; }
    String id = "chatcmpl-" + SnowflakeIdUtils.id();
    SseFrameInputStream frames = new SseFrameInputStream(response.body().byteStream(),
        EnvUtils.getInt("proxy.oauth.trae.max-event-bytes", 8 * 1024 * 1024), frame -> convertFrame(frame, id, model));
    BufferedSource source = Okio.buffer(Okio.source(frames));
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() { return MediaType.get("text/event-stream"); }
      @Override public long contentLength() { return -1; }
      @Override public BufferedSource source() { return source; }
    };
    Response converted = response.newBuilder().removeHeader("Content-Length").header("Content-Type", "text/event-stream").body(body).build();
    return Aop.get(ClineTransportService.class).response(converted, clientStream);
  }

  public byte[] convertFrame(byte[] frame, String id, String model) throws IOException {
    String event = "";
    StringBuilder data = new StringBuilder();
    for (String line : new String(frame, StandardCharsets.UTF_8).split("\\r?\\n")) {
      if (line.startsWith("event:")) { event = line.substring(6).trim(); }
      if (line.startsWith("data:")) {
        if (!data.isEmpty()) { data.append('\n'); }
        data.append(line.substring(5).stripLeading());
      }
    }
    if (event.isBlank() || data.isEmpty()) { return new byte[0]; }
    Kv value;
    try { value = ProxyJson.parse(data.toString()); } catch (RuntimeException error) { throw new IOException("TRAE returned an invalid stream event"); }
    if ("error".equals(event)) { throw new IOException("TRAE reported a stream error (code " + ProxyJson.number(value, "code", -1) + ")"); }
    Kv delta = Kv.create();
    Kv choice = Kv.create().set("index", 0).set("delta", delta);
    Kv chunk = Kv.create().set("id", id).set("object", "chat.completion.chunk")
        .set("created", Instant.now().getEpochSecond()).set("model", model).set("choices", List.of(choice));
    switch (event) {
      case "output" -> {
        if (value.get("response") != null) { delta.set("content", value.get("response")); }
        if (value.get("reasoning_content") != null) { delta.set("reasoning_content", value.get("reasoning_content")); }
        List<Kv> calls = new ArrayList<>();
        int index = 0;
        for (Object item : ProxyJson.list(value.get("tool_calls"))) {
          Kv call = ProxyJson.object(item);
          Kv function = ProxyJson.object(call.containsKey("function_call") ? call.remove("function_call") : call.get("function"));
          function.remove("namespace"); function.remove("partial_arguments");
          call.set("function", function);
          call.putIfAbsent("index", index++);
          call.putIfAbsent("type", "function");
          calls.add(call);
        }
        if (!calls.isEmpty()) { delta.set("tool_calls", calls); }
      }
      case "token_usage" -> { chunk.set("choices", List.of()).set("usage", value); }
      case "done" -> { choice.set("finish_reason", ProxyJson.text(value, "finish_reason", "stop")); }
      default -> { return new byte[0]; }
    }
    String wire = "data: " + JsonUtils.toJsonWithLongAsNumber(chunk) + "\n\n";
    if ("done".equals(event)) { wire += "data: [DONE]\n\n"; }
    return wire.getBytes(StandardCharsets.UTF_8);
  }
}
