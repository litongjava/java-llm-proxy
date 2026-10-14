package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/** Devin Connect-RPC wire adapter, based on the upstream MIT-licensed field schema. */
public class DevinTransportService {
  public Request prepare(Request original, Provider provider, String payload) {
    Kv body = ProxyJson.parse(payload);
    byte[] fingerprint = new byte[366];
    new SecureRandom().nextBytes(fingerprint);
    String version = EnvUtils.get("proxy.oauth.devin.client-version", "3000.10.21");
    ProtoWire metadata = new ProtoWire().text(1, "chisel").text(2, version).text(3, provider.apiKey).text(4, "en")
        .text(5, PluginService.os()).text(7, version).text(12, "chisel").text(31, HexFormat.of().formatHex(fingerprint));
    ProtoWire request = new ProtoWire().bytes(1, metadata.bytes());
    StringBuilder system = new StringBuilder();
    int lastRole = 1;
    for (Object value : ProxyJson.list(body.get("messages"))) {
      Kv message = ProxyJson.object(value);
      String role = message.getStr("role");
      Object content = message.get("content");
      List<?> parts = content instanceof String ? List.of(Kv.create().set("text", content)) : ProxyJson.list(content);
      StringBuilder text = new StringBuilder();
      for (Object part : parts) { text.append(ProxyJson.text(ProxyJson.object(part), "text", "")); }
      if ("system".equals(role) || "developer".equals(role)) { system.append(text).append('\n'); continue; }
      lastRole = "assistant".equals(role) ? 2 : "tool".equals(role) ? 4 : 1;
      ProtoWire prompt = new ProtoWire().text(1, String.valueOf(SnowflakeIdUtils.id())).number(2, lastRole).text(3, text.toString());
      for (Object part : parts) {
        String url = ProxyJson.object(ProxyJson.object(part).get("image_url")).getStr("url");
        if (url != null && url.startsWith("data:") && url.contains(";base64,")) {
          String[] image = url.substring(5).split(";base64,", 2);
          prompt.bytes(10, new ProtoWire().text(1, image[1]).text(2, image[0]).bytes());
        }
      }
      for (Object callValue : ProxyJson.list(message.get("tool_calls"))) {
        Kv call = ProxyJson.object(callValue);
        Kv function = ProxyJson.object(call.get("function"));
        prompt.bytes(6, new ProtoWire().text(1, call.getStr("id")).text(2, function.getStr("name")).text(3, function.getStr("arguments")).bytes());
      }
      if (message.get("tool_call_id") != null) { prompt.text(7, message.getStr("tool_call_id")); }
      if (message.get("reasoning_content") != null) { prompt.text(11, message.getStr("reasoning_content")); }
      request.bytes(3, prompt.bytes());
    }
    if (!system.isEmpty()) { request.text(2, system.toString()); }
    double temperature = body.get("temperature") instanceof Number number ? number.doubleValue() : 1.0;
    request.number(7, 5).bytes(8, new ProtoWire().number(1, 1).number(2, ProxyJson.number(body, "max_tokens", 128000))
        .number(3, 400).decimal(5, temperature).number(7, 40).decimal(8, 0.95f).bytes());
    for (Object value : ProxyJson.list(body.get("tools"))) {
      Kv tool = ProxyJson.object(ProxyJson.object(value).get("function"));
      request.bytes(10, new ProtoWire().text(1, tool.getStr("name")).text(2, tool.getStr("description")).text(3, JsonUtils.toJsonWithLongAsNumber(tool.get("parameters"))).bytes());
    }
    String session = ProxyJson.text(body, "prompt_cache_key", String.valueOf(SnowflakeIdUtils.id()));
    ProtoWire thread = new ProtoWire().text(1, session).number(3, 4);
    if (lastRole == 1) { thread.number(4, 14); }
    String model = body.getStr("model");
    if ("claude-haiku-4-5".equals(model)) { model = "MODEL_PRIVATE_11"; }
    else if ("gpt-4-1".equals(model)) { model = "MODEL_CHAT_GPT_4_1_2025_04_14"; }
    else if ("claude-sonnet-4-5".equals(model)) { model = body.get("reasoning_effort") == null ? "MODEL_PRIVATE_2" : "MODEL_PRIVATE_3"; }
    request.bytes(15, thread.bytes()).text(16, session).number(20, 1).text(21, model);
    byte[] protobuf = request.bytes();
    byte[] frame = ByteBuffer.allocate(protobuf.length + 5).put((byte) 0).putInt(protobuf.length).put(protobuf).array();
    return original.newBuilder().url(provider.baseUrl + "/exa.api_server_pb.ApiServerService/GetChatMessage")
        .header("Authorization", "Basic " + provider.apiKey + "-" + provider.apiKey).header("Connect-Protocol-Version", "1")
        .header("Content-Type", "application/connect+proto").header("User-Agent", "").header("Accept", "*/*")
        .post(RequestBody.create(frame, MediaType.get("application/connect+proto"))).build();
  }

  public Response execute(OkHttpClient client, Request request, String model, boolean streaming) throws Exception {
    Response response = UniChatClient.execute(client, request);
    if (!response.isSuccessful() || response.body() == null) { return response; }
    DevinStream stream = new DevinStream(response.body().byteStream(), model);
    if (!streaming) {
      try (response; stream) { while (stream.read(new byte[8192]) != -1) { } }
      return response.newBuilder().removeHeader("Content-Length").header("Content-Type", "application/json")
          .body(ResponseBody.create(JsonUtils.toJsonWithLongAsNumber(stream.completed()), MediaType.get("application/json"))).build();
    }
    BufferedSource source = Okio.buffer(Okio.source(stream));
    ResponseBody body = new ResponseBody() {
      public MediaType contentType() { return MediaType.get("text/event-stream"); }
      public long contentLength() { return -1; }
      public BufferedSource source() { return source; }
    };
    return response.newBuilder().removeHeader("Content-Length").header("Content-Type", "text/event-stream").body(body).build();
  }

  public static class DevinStream extends InputStream {
    private final DataInputStream input;
    private final String model;
    private final String id = "devin-" + SnowflakeIdUtils.id();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Kv calls = Kv.create();
    private ByteArrayInputStream output = new ByteArrayInputStream(new byte[0]);
    private boolean done;
    private int promptTokens;
    private int outputTokens;
    private int cachedTokens;
    public DevinStream(InputStream input, String model) { this.input = new DataInputStream(input); this.model = model; }
    public int read() throws IOException { byte[] data = new byte[1]; return read(data, 0, 1) < 0 ? -1 : data[0] & 255; }
    public int read(byte[] target, int start, int length) throws IOException {
      if (length == 0) { return 0; }
      int count;
      while ((count = output.read(target, start, length)) < 0) {
        if (done) { return -1; }
        int flag = input.read();
        if (flag < 0) { throw new IOException("Devin stream ended before Connect trailer"); }
        int size = input.readInt();
        if (flag > 3 || size < 0 || size > 16 * 1024 * 1024) { throw new IOException("Invalid Connect frame"); }
        byte[] payload = input.readNBytes(size);
        if (payload.length != size) { throw new IOException("Truncated Connect frame"); }
        if ((flag & 1) != 0) {
          try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) { payload = gzip.readNBytes(32 * 1024 * 1024 + 1); }
          if (payload.length > 32 * 1024 * 1024) { throw new IOException("Connect frame exceeds decompression limit"); }
        }
        String data;
        if ((flag & 2) != 0) {
          Kv trailer = ProxyJson.parse(new String(payload, StandardCharsets.UTF_8));
          if (trailer.get("error") != null) { throw new IOException("Devin Connect trailer reported an error"); }
          done = true;
          data = event(Kv.create(), calls.isEmpty() ? "stop" : "tool_calls", usage()) + "data: [DONE]\n\n";
        } else { data = frame(payload); }
        output = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
      }
      return count;
    }
    private String frame(byte[] payload) throws IOException {
      Kv delta = Kv.create();
      List<Kv> toolDeltas = new ArrayList<>();
      for (ProtoWire.Field field : ProtoWire.parse(payload)) {
        if (field.type() == 2 && field.number() == 3) { text.append(field.text()); delta.set("content", ProxyJson.text(delta, "content", "") + field.text()); }
        else if (field.type() == 2 && field.number() == 9) { reasoning.append(field.text()); delta.set("reasoning_content", field.text()); }
        else if (field.type() == 2 && field.number() == 6) {
          Kv tool = Kv.create();
          for (ProtoWire.Field item : ProtoWire.parse(field.bytes())) {
            if (item.number() == 1) { tool.set("id", item.text()); }
            else if (item.number() == 2) { tool.set("name", item.text()); }
            else if (item.number() == 3) { tool.set("arguments", item.text()); }
          }
          String toolId = ProxyJson.text(tool, "id", "call-" + Math.max(0, calls.size() - 1));
          Kv accumulated = ProxyJson.object(calls.get(toolId));
          boolean first = accumulated.isEmpty();
          if (first) { accumulated.set("id", toolId).set("index", calls.size()).set("name", "").set("arguments", ""); }
          if (tool.get("name") != null) { accumulated.set("name", tool.get("name")); }
          accumulated.set("arguments", accumulated.getStr("arguments") + ProxyJson.text(tool, "arguments", ""));
          calls.set(toolId, accumulated);
          Kv function = Kv.create().set("arguments", ProxyJson.text(tool, "arguments", ""));
          if (first) { function.set("name", accumulated.get("name")); }
          Kv emitted = Kv.create().set("index", accumulated.get("index")).set("function", function);
          if (first) { emitted.set("id", toolId).set("type", "function"); }
          toolDeltas.add(emitted);
        } else if (field.type() == 2 && field.number() == 7) {
          int uncached = 0; int written = 0;
          for (ProtoWire.Field item : ProtoWire.parse(field.bytes())) {
            if (item.type() != 0) { continue; }
            if (item.number() == 2) { uncached = (int) item.numberValue(); }
            else if (item.number() == 3) { outputTokens = (int) item.numberValue(); }
            else if (item.number() == 4) { written = (int) item.numberValue(); }
            else if (item.number() == 5) { cachedTokens = (int) item.numberValue(); }
          }
          promptTokens = uncached + written + cachedTokens;
        }
      }
      if (text.length() + reasoning.length() > 32 * 1024 * 1024) { throw new IOException("Devin response exceeds size limit"); }
      if (!toolDeltas.isEmpty()) { delta.set("tool_calls", toolDeltas); }
      return delta.isEmpty() ? "" : event(delta, null, null);
    }
    private String event(Kv delta, String finish, Kv usage) {
      Kv body = Kv.create().set("id", id).set("object", "chat.completion.chunk").set("model", model)
          .set("choices", List.of(Kv.create().set("index", 0).set("delta", delta).set("finish_reason", finish)));
      if (usage != null) { body.set("usage", usage); }
      return "data: " + JsonUtils.toJsonWithLongAsNumber(body) + "\n\n";
    }
    private Kv usage() { return Kv.create().set("prompt_tokens", promptTokens).set("completion_tokens", outputTokens).set("total_tokens", promptTokens + outputTokens).set("prompt_tokens_details", Kv.create().set("cached_tokens", cachedTokens)); }
    public Kv completed() {
      Kv message = Kv.create().set("role", "assistant").set("content", text.toString());
      if (!reasoning.isEmpty()) { message.set("reasoning_content", reasoning.toString()); }
      if (!calls.isEmpty()) {
        message.set("tool_calls", calls.values().stream().map(value -> {
          Kv tool = ProxyJson.object(value);
          return Kv.create().set("id", tool.get("id")).set("type", "function").set("function", Kv.create().set("name", tool.get("name")).set("arguments", tool.get("arguments")));
        }).toList());
      }
      return Kv.create().set("id", id).set("object", "chat.completion").set("model", model)
          .set("choices", List.of(Kv.create().set("index", 0).set("message", message).set("finish_reason", calls.isEmpty() ? "stop" : "tool_calls"))).set("usage", usage());
    }
    public void close() throws IOException { input.close(); }
  }
}
