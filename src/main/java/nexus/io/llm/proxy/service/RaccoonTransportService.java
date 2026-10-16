package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import com.jfinal.kit.Kv;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Adapts Raccoon's required streaming transport to OpenAI chat responses. */
public class RaccoonTransportService {
  public Kv payload(Kv body, String endpoint) {
    BusinessException.require("/chat/completions".equals(endpoint), 400, "RACCOON_ENDPOINT_UNSUPPORTED",
        "Raccoon supports the chat completions endpoint only");
    String effort = ProxyJson.text(body, "reasoning_effort", ProxyJson.object(body.get("reasoning")).getStr("effort"));
    body.remove("reasoning_effort");
    body.remove("reasoning");
    Kv extra = ProxyJson.object(body.get("extra_body"));
    if (effort != null) {
      BusinessException.require(List.of("on", "off", "none").contains(effort), 400, "RACCOON_REASONING_UNSUPPORTED",
          "Raccoon supports thinking on or off, not reasoning intensity levels");
      extra.set("thinking", Kv.create().set("type", "on".equals(effort) ? "enabled" : "disabled"));
    } else if (body.get("thinking") != null) {
      extra.set("thinking", body.get("thinking"));
    }
    body.remove("thinking");
    if (!extra.isEmpty()) {
      body.set("extra_body", extra);
    }
    return body.set("stream", true).set("stream_options", ProxyJson.object(body.get("stream_options")).set("include_usage", true));
  }

  public Response response(Response response, boolean clientStream) throws IOException {
    if (!response.isSuccessful() || response.body() == null || clientStream) { return response; }
    if (!response.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("text/event-stream")) {
      response.close();
      throw new IOException("Raccoon returned an unexpected response format");
    }
    int maximum = Math.max(1024, EnvUtils.getInt("proxy.oauth.raccoon.max-response-bytes", 32 * 1024 * 1024));
    Kv result = Kv.create().set("object", "chat.completion");
    TreeMap<Integer, Choice> choices = new TreeMap<>();
    boolean done = false;
    long total = 0;
    try (response; SseFrameInputStream frames = new SseFrameInputStream(response.body().byteStream(), maximum, frame -> frame)) {
      byte[] frame;
      while ((frame = frames.nextFrame()) != null) {
        total += frame.length;
        if (total > maximum) { throw new IOException("Raccoon response exceeds the configured limit"); }
        String data = data(frame);
        if (data.isBlank()) { continue; }
        if ("[DONE]".equals(data.trim())) { done = true; break; }
        Kv chunk;
        try { chunk = ProxyJson.parse(data); }
        catch (Exception error) { throw new IOException("Raccoon returned an invalid stream event"); }
        if (chunk.containsKey("error") || "error".equals(chunk.getStr("type"))) {
          throw new IOException("Raccoon reported a stream error");
        }
        for (String field : List.of("id", "created", "model", "system_fingerprint", "service_tier", "usage")) {
          if (chunk.get(field) != null) { result.set(field, chunk.get(field)); }
        }
        for (Object value : ProxyJson.list(chunk.get("choices"))) {
          Kv incoming = ProxyJson.object(value);
          int index = index(incoming);
          Choice choice = choices.computeIfAbsent(index, Choice::new);
          choice.merge(incoming);
        }
      }
    }
    if (!done || choices.isEmpty() || choices.values().stream().anyMatch(choice -> choice.finishReason == null)) {
      throw new IOException("Raccoon stream ended before completion");
    }
    result.set("choices", choices.values().stream().map(Choice::result).toList());
    return response.newBuilder().removeHeader("Content-Length").removeHeader("Transfer-Encoding")
        .header("Content-Type", "application/json")
        .body(ResponseBody.create(JsonUtils.toJsonWithLongAsNumber(result), MediaType.get("application/json"))).build();
  }

  private static int index(Kv value) throws IOException {
    long index = ProxyJson.number(value, "index", -1);
    if (index < 0 || index > Integer.MAX_VALUE) { throw new IOException("Raccoon returned an invalid choice or tool index"); }
    return (int) index;
  }

  private static String data(byte[] frame) {
    StringBuilder data = new StringBuilder();
    for (String line : new String(frame, StandardCharsets.UTF_8).split("\\r?\\n")) {
      if (line.startsWith("data:")) {
        if (!data.isEmpty()) { data.append('\n'); }
        String value = line.substring(5);
        data.append(value.startsWith(" ") ? value.substring(1) : value);
      }
    }
    return data.toString();
  }

  private static void append(Kv target, Kv source, String field) {
    if (source.get(field) != null) {
      Object previous = target.get(field);
      target.set(field, (previous == null ? "" : previous.toString()) + source.get(field));
    }
  }

  private static class Choice {
    final int index;
    final Kv message = Kv.create().set("role", "assistant").set("content", null);
    final TreeMap<Integer, Kv> tools = new TreeMap<>();
    final List<Object> contentLogprobs = new ArrayList<>();
    final List<Object> refusalLogprobs = new ArrayList<>();
    String finishReason;
    boolean hasLogprobs;

    Choice(int index) { this.index = index; }

    void merge(Kv chunk) throws IOException {
      Kv delta = ProxyJson.object(chunk.get("delta"));
      if (delta.get("role") != null) { message.set("role", delta.get("role")); }
      for (String field : List.of("content", "reasoning", "reasoning_content", "refusal")) { append(message, delta, field); }
      if (delta.get("function_call") != null) {
        Kv function = ProxyJson.object(message.get("function_call"));
        Kv incoming = ProxyJson.object(delta.get("function_call"));
        append(function, incoming, "name");
        append(function, incoming, "arguments");
        message.set("function_call", function);
      }
      for (Object value : ProxyJson.list(delta.get("tool_calls"))) {
        Kv incoming = ProxyJson.object(value);
        Kv tool = tools.computeIfAbsent(index(incoming), key -> Kv.create().set("type", "function"));
        append(tool, incoming, "id");
        if (incoming.get("type") != null) { tool.set("type", incoming.get("type")); }
        Kv function = ProxyJson.object(tool.get("function"));
        Kv incomingFunction = ProxyJson.object(incoming.get("function"));
        append(function, incomingFunction, "name");
        append(function, incomingFunction, "arguments");
        tool.set("function", function);
      }
      if (chunk.get("finish_reason") != null) { finishReason = chunk.getStr("finish_reason"); }
      if (chunk.get("logprobs") != null) {
        hasLogprobs = true;
        Kv logprobs = ProxyJson.object(chunk.get("logprobs"));
        contentLogprobs.addAll(ProxyJson.list(logprobs.get("content")));
        refusalLogprobs.addAll(ProxyJson.list(logprobs.get("refusal")));
      }
    }

    Kv result() {
      if (!tools.isEmpty()) { message.set("tool_calls", new ArrayList<>(tools.values())); }
      Kv result = Kv.create().set("index", index).set("message", message).set("finish_reason", finishReason).set("logprobs", null);
      if (hasLogprobs) { result.set("logprobs", Kv.create().set("content", contentLogprobs).set("refusal", refusalLogprobs)); }
      return result;
    }
  }
}
