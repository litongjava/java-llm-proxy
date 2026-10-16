package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;

/** Huawei signed OpenAI transport, including DeepSeek native DSML tool output. */
public class CodeArtsTransportService {
  public Kv payload(Kv body, String model, String endpoint) {
    BusinessException.require("/chat/completions".equals(endpoint), 400, "CODEARTS_ENDPOINT_UNSUPPORTED", "CodeArts supports chat completions only");
    body.set("stream", true).set("tool_stream", true);
    body.putIfAbsent("include", List.of("reasoning.encrypted_content"));
    body.putIfAbsent("reasoning_summary", "auto");
    body.putIfAbsent("max_tokens", 65536);
    String effort = body.getStr("reasoning_effort");
    if ("off".equals(effort) || "none".equals(effort)) { body.set("thinking", Kv.create().set("type", "disabled")); body.remove("reasoning_effort"); }
    List<?> tools = ProxyJson.list(body.get("tools"));
    if (model.toLowerCase(Locale.ROOT).startsWith("deepseek-v4") && !tools.isEmpty()) {
      List<Object> messages = new ArrayList<>(ProxyJson.list(body.get("messages")));
      String instructions = """
          Use the following tools when needed. Emit native DSML tool calls using this exact syntax:
          <｜DSML｜tool_calls><｜DSML｜invoke name="tool"><｜DSML｜parameter name="argument" string="true">value</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls>
          Use only names in the schemas below. Use string="true" only for string parameters; encode other parameter values as JSON.
          Multiple invoke elements may occur inside one tool_calls block. Preserve complete argument values.
          Available OpenAI function schemas:
          """ + JsonUtils.toJson(tools);
      int position = !messages.isEmpty() && "system".equals(ProxyJson.object(messages.get(0)).getStr("role")) ? 1 : 0;
      messages.add(position, Kv.create().set("role", "system").set("content", instructions));
      body.set("messages", messages);
      body.remove("tools");
      body.remove("tool_choice");
      body.remove("parallel_tool_calls");
    }
    return body;
  }

  /** Must run after the final URL, body and other request headers have been selected. */
  public Request prepare(Request original, Kv credential, String model) throws Exception {
    Buffer buffer = new Buffer();
    if (original.body() != null) { original.body().writeTo(buffer); }
    byte[] payload = buffer.readByteArray();
    Kv extra = Kv.create();
    if (Aop.get(CodeArtsService.class).benefit(credential, model)) { extra.set("maas_type", "benefit"); }
    Kv headers = Aop.get(CodeArtsCryptoService.class).sign(credential, original.method(), original.url().toString(), payload, extra);
    Request.Builder request = original.newBuilder().removeHeader("Authorization").removeHeader("maas_type");
    // OkHttp derives the wire Content-Type from RequestBody and may replace a manually set header.
    // A byte-backed body preserves the exact signed bytes without adding a charset parameter.
    if (original.body() != null) { request.method(original.method(), RequestBody.create(payload, MediaType.get("application/json"))); }
    headers.forEach((name, value) -> request.header(name.toString(), value.toString()));
    request.header("Accept", "text/event-stream").header("lang", "en");
    if (original.header("Chat-Id") == null) { request.header("Chat-Id", "chat-" + SnowflakeIdUtils.id()); }
    if (original.header("Session-Id") == null) { request.header("Session-Id", "session-" + SnowflakeIdUtils.id()); }
    return request.build();
  }

  public Response response(Response original, boolean clientStream) throws IOException {
    if (!original.isSuccessful() || original.body() == null) { return original; }
    if (!original.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("text/event-stream")) {
      original.close(); throw new IOException("CodeArts returned an unexpected response format");
    }
    TreeMap<Long, CodeArtsDsmlParser> contentParsers = new TreeMap<>();
    TreeMap<Long, CodeArtsDsmlParser> reasoningParsers = new TreeMap<>();
    TreeMap<Long, AtomicInteger> toolIndices = new TreeMap<>();
    int maximum = Math.max(1024, EnvUtils.getInt("proxy.oauth.codearts.max-event-bytes", 4 * 1024 * 1024));
    SseFrameInputStream frames = new SseFrameInputStream(original.body().byteStream(), maximum, frame -> {
      StringBuilder text = new StringBuilder();
      for (String line : new String(frame, StandardCharsets.UTF_8).split("\\r?\\n")) {
        if (line.startsWith("data:")) { text.append(line.substring(5).stripLeading()).append('\n'); }
      }
      String data = text.toString().trim();
      if (data.isEmpty()) { return frame; }
      if ("[DONE]".equals(data)) {
        for (CodeArtsDsmlParser parser : contentParsers.values()) { parser.feed("", "content", true); }
        for (CodeArtsDsmlParser parser : reasoningParsers.values()) { parser.feed("", "reasoning_content", true); }
        return frame;
      }
      Kv chunk;
      try { chunk = ProxyJson.parse(data); }
      catch (RuntimeException error) { throw new IOException("CodeArts returned an invalid stream event"); }
      if (chunk.get("error") != null || !ProxyJson.text(chunk, "error_code", "").isBlank()) { throw new IOException("CodeArts reported a stream error"); }
      List<Kv> choices = new ArrayList<>();
      for (Object raw : ProxyJson.list(chunk.get("choices"))) {
        Kv choice = ProxyJson.object(raw);
        long index = ProxyJson.number(choice, "index", 0);
        choice.set("index", index);
        Kv delta = ProxyJson.object(choice.get("delta"));
        AtomicInteger toolIndex = toolIndices.computeIfAbsent(index, key -> new AtomicInteger());
        for (Object rawCall : ProxyJson.list(delta.get("tool_calls"))) {
          int nativeIndex = (int) ProxyJson.number(ProxyJson.object(rawCall), "index", 0);
          toolIndex.accumulateAndGet(nativeIndex + 1, Math::max);
        }
        CodeArtsDsmlParser content = contentParsers.computeIfAbsent(index, key -> new CodeArtsDsmlParser(toolIndex));
        CodeArtsDsmlParser reasoning = reasoningParsers.computeIfAbsent(index, key -> new CodeArtsDsmlParser(toolIndex));
        boolean finished = choice.get("finish_reason") != null;
        Kv converted = content.feed(delta.get("content") == null ? "" : delta.getStr("content"), "content", finished);
        Kv convertedReasoning = reasoning.feed(delta.get("reasoning_content") == null ? "" : delta.getStr("reasoning_content"), "reasoning_content", finished);
        delta.remove("content"); delta.remove("reasoning_content");
        merge(delta, converted); merge(delta, convertedReasoning);
        choice.set("delta", delta);
        if (finished && (content.hasTools() || reasoning.hasTools()) && !"length".equals(choice.getStr("finish_reason"))) { choice.set("finish_reason", "tool_calls"); }
        choices.add(choice);
      }
      if (chunk.containsKey("choices")) { chunk.set("choices", choices); }
      return ("data: " + JsonUtils.toJsonWithLongAsNumber(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8);
    });
    BufferedSource source = Okio.buffer(Okio.source(frames));
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() { return MediaType.get("text/event-stream"); }
      @Override public long contentLength() { return -1; }
      @Override public BufferedSource source() { return source; }
    };
    Response normalized = original.newBuilder().removeHeader("Content-Length").body(body).build();
    return clientStream ? normalized : Aop.get(ClineTransportService.class).response(normalized, false);
  }

  private void merge(Kv destination, Kv delta) {
    delta.forEach((key, value) -> {
      if ("tool_calls".equals(key)) {
        List<Object> calls = new ArrayList<>(ProxyJson.list(destination.get(key)));
        calls.addAll(ProxyJson.list(value)); destination.set(key, calls);
      } else { destination.set(key, (destination.get(key) == null ? "" : destination.get(key).toString()) + value); }
    });
  }
}
