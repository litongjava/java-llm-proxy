package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.QoderPreparedRequest;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;

/** Qoder encrypted request and OpenAI-compatible response-envelope transport. */
public class QoderTransportService {
  public static boolean supports(String provider) {
    return "qoder".equals(provider) || "qodercn".equals(provider);
  }

  public Kv payload(String provider, Kv body, String model, String endpoint) {
    BusinessException.require("/chat/completions".equals(endpoint), 400, "QODER_ENDPOINT_UNSUPPORTED", "Qoder supports chat completions only");
    Kv info = Aop.get(QoderModelService.class).model(provider, model);
    List<Kv> messages = new ArrayList<>();
    StringBuilder system = new StringBuilder();
    String text = "";
    for (Object raw : ProxyJson.list(body.get("messages"))) {
      Kv message = ProxyJson.object(raw);
      String role = message.getStr("role");
      if ("system".equals(role) || "developer".equals(role)) {
        if (!system.isEmpty()) { system.append('\n'); }
        system.append(content(message.get("content")));
      } else {
        Object originalContent = message.get("content");
        boolean images = ProxyJson.list(originalContent).stream().anyMatch(part -> "image_url".equals(ProxyJson.object(part).getStr("type")));
        Kv normalized = Kv.create().set("role", role).set("content", images ? originalContent : content(originalContent));
        if ("assistant".equals(role) && message.get("tool_calls") != null) { normalized.set("tool_calls", message.get("tool_calls")); }
        if ("tool".equals(role) && message.get("tool_call_id") != null) { normalized.set("tool_call_id", message.get("tool_call_id")); }
        messages.add(normalized);
        if ("user".equals(role)) { text = content(message.get("content")); }
      }
    }
    boolean reasoning = Boolean.TRUE.equals(info.get("supportsThinking"));
    Kv parameters = Kv.create();
    Object maxTokens = body.get("max_completion_tokens") == null ? body.get("max_tokens") : body.get("max_completion_tokens");
    if (maxTokens != null) { parameters.set("max_tokens", maxTokens); }
    String effort = ProxyJson.text(body, "reasoning_effort", "");
    if (!effort.isBlank()) { parameters.set("reasoning_effort", effort).set("enable_thinking", !"none".equals(effort)); }
    if (body.get("context_length") != null) { parameters.set("context_length", body.get("context_length")); }
    Kv config = Kv.create().set("key", model).set("display_name", info.get("name")).set("model", "").set("format", "openai")
        .set("is_vl", Boolean.TRUE.equals(info.get("supportsImage"))).set("is_reasoning", reasoning).set("api_key", "").set("url", "")
        .set("source", "system").set("max_input_tokens", info.get("contextWindow"));
    // Qoder wire identifiers follow its UUID protocol; application record IDs remain Snowflake IDs.
    String id = UUID.randomUUID().toString();
    return Kv.create().set("request_id", id).set("request_set_id", id).set("chat_record_id", id)
        .set("session_id", UUID.randomUUID().toString()).set("stream", true).set("chat_task", "FREE_INPUT")
        .set("chat_context", Kv.create().set("text", text).set("features", List.of()).set("extra", Kv.create().set("context", List.of())
            .set("modelConfig", Kv.create().set("key", model).set("is_reasoning", reasoning)).set("originalContent", text)).set("chatPrompt", "").set("imageUrls", null))
        .set("is_reply", true).set("is_retry", false).set("source", 1).set("version", "3").set("agent_id", "agent_common").set("task_id", "common")
        .set("session_type", "qodercli").set("aliyun_user_type", "")
        .set("model_config", config).set("custom_model", null)
        .set("system", system.isEmpty() ? List.of() : List.of(Kv.create().set("type", "text").set("text", system.toString())))
        .set("messages", messages).set("tools", ProxyJson.list(body.get("tools"))).set("parameters", parameters).set("business", Kv.create().set("type", "agent"));
  }

  private String content(Object content) {
    if (content instanceof String text) { return text; }
    StringBuilder text = new StringBuilder();
    for (Object part : ProxyJson.list(content)) {
      Kv item = ProxyJson.object(part);
      if ("text".equals(item.getStr("type"))) { text.append(ProxyJson.text(item, "text", "")); }
    }
    return text.toString();
  }

  public Request prepare(Request request, String provider, Kv credential, String model) throws Exception {
    BusinessException.require(credential != null && !ProxyJson.text(credential, "uid", "").isBlank()
        && !ProxyJson.text(credential, "machine_id", "").isBlank(), 400, "QODER_CREDENTIAL_INVALID", "Qoder credentials require uid and machine_id");
    Buffer buffer = new Buffer();
    request.body().writeTo(buffer);
    QoderPreparedRequest prepared = QoderWasmRuntime.prepare(credential, Aop.get(QoderService.class).apiBaseUrl(provider), buffer.readUtf8(), model);
    Request.Builder builder = request.newBuilder().url(prepared.url())
        .post(RequestBody.create(prepared.body().getBytes(StandardCharsets.UTF_8), MediaType.get("application/json")))
        .removeHeader("Content-Length").header("Accept", "text/event-stream");
    prepared.headers().forEach((key, value) -> builder.header(key.toString(), value.toString()));
    return builder.build();
  }

  public Response response(Response original, boolean clientStream) throws IOException {
    if (!original.isSuccessful() || original.body() == null) { return original; }
    if (!original.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("text/event-stream")) {
      original.close(); throw new IOException("Qoder returned an unexpected response format");
    }
    int maximum = Math.max(1024, EnvUtils.getInt("proxy.oauth.qoder.max-event-bytes", 4 * 1024 * 1024));
    SseFrameInputStream stream = new SseFrameInputStream(original.body().byteStream(), maximum, this::unwrap);
    BufferedSource source = Okio.buffer(Okio.source(stream));
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() { return MediaType.get("text/event-stream"); }
      @Override public long contentLength() { return -1; }
      @Override public BufferedSource source() { return source; }
    };
    Response normalized = original.newBuilder().removeHeader("Content-Length").body(body).build();
    return clientStream ? normalized : Aop.get(ClineTransportService.class).response(normalized, false);
  }

  private byte[] unwrap(byte[] frame) throws IOException {
    StringBuilder data = new StringBuilder();
    for (String line : new String(frame, StandardCharsets.UTF_8).split("\\r?\\n")) {
      if (line.startsWith("data:")) { data.append(line.substring(5).stripLeading()).append('\n'); }
    }
    String text = data.toString().trim();
    if (text.isBlank()) { return frame; }
    if ("[DONE]".equals(text)) { return "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8); }
    try {
      Kv chunk = ProxyJson.parse(text);
      long status = ProxyJson.number(chunk, "statusCodeValue", 200);
      if (status < 200 || status >= 300) { throw new IOException("Qoder envelope status " + status); }
      if (chunk.containsKey("body")) {
        Object wrapped = chunk.get("body");
        if (wrapped == null || wrapped.toString().isBlank()) { return new byte[0]; }
        if ("[DONE]".equals(wrapped.toString().trim())) { return "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8); }
        chunk = wrapped instanceof String value ? ProxyJson.object(JsonUtils.parse(value, Object.class)) : ProxyJson.object(wrapped);
      }
      if (chunk.isEmpty()) { return new byte[0]; }
      if (!(chunk.get("choices") instanceof List<?>) && !chunk.containsKey("usage")) {
        if (chunk.containsKey("error") || chunk.containsKey("code") || chunk.containsKey("message")
            || chunk.containsKey("statusCodeValue") || chunk.containsKey("type")) {
          throw new IOException("Qoder reported an upstream stream error (code " + ProxyJson.number(chunk, "code", 0) + ")");
        }
        return new byte[0];
      }
      return ("data: " + JsonUtils.toJson(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException error) { throw new IOException("Qoder returned an invalid stream event", error); }
  }
}
