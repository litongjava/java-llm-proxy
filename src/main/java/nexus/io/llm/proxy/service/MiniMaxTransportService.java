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
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** MiniMax uses Anthropic Messages and requires adaptive thinking on M3.1. */
public class MiniMaxTransportService {
  public void headers(Request.Builder request) {
    request.removeHeader("x-api-key").removeHeader("anthropic-version").removeHeader("anthropic-beta")
        .header("Accept", "text/event-stream").header("Content-Type", "application/json");
  }

  public Kv payload(Kv body, String model, String endpoint) {
    BusinessException.require("/messages".equals(endpoint), 400, "MINIMAX_ENDPOINT_UNSUPPORTED", "MiniMax Code supports Anthropic Messages");
    body.set("model", model).set("stream", true);
    body.remove("stream_options");
    String effort = ProxyJson.text(body, "reasoning_effort", ProxyJson.object(body.get("reasoning")).getStr("effort"));
    body.remove("reasoning_effort");
    body.remove("reasoning");
    boolean requiresAdaptive = model.startsWith("MiniMax-M3.1");
    boolean forced = requiresAdaptive || model.startsWith("MiniMax-M2.7");
    Kv thinking = ProxyJson.object(body.get("thinking"));
    BusinessException.require(!forced || (!"none".equals(effort) && !"disabled".equals(thinking.getStr("type"))),
        400, "MINIMAX_THINKING_REQUIRED", "This MiniMax model requires thinking and cannot disable it");
    if (requiresAdaptive || (effort != null && !"none".equals(effort))) {
      body.set("thinking", Kv.create().set("type", "adaptive"));
      if (effort != null && !"on".equals(effort)) {
        body.set("output_config", ProxyJson.object(body.get("output_config")).set("effort", effort));
      }
    } else if ("none".equals(effort)) {
      body.set("thinking", Kv.create().set("type", "disabled"));
    }
    // Parallel tool results must form one user message after the assistant call batch.
    List<Kv> messages = new ArrayList<>();
    for (Object entry : ProxyJson.list(body.get("messages"))) {
      Kv message = Kv.create().set(ProxyJson.object(entry));
      if (!messages.isEmpty() && "user".equals(message.getStr("role"))
          && "user".equals(messages.getLast().getStr("role"))) {
        List<Object> content = blocks(messages.getLast().get("content"));
        content.addAll(blocks(message.get("content")));
        messages.getLast().set("content", content);
      } else {
        messages.add(message);
      }
    }
    body.set("messages", messages);
    return body;
  }

  private List<Object> blocks(Object content) {
    if (content instanceof String text) {
      return new ArrayList<>(List.of(Kv.create().set("type", "text").set("text", text)));
    }
    return new ArrayList<>(ProxyJson.list(content));
  }

  public Response response(Response response, boolean clientStream) throws IOException {
    if (!response.isSuccessful() || response.body() == null || clientStream) {
      return response;
    }
    if (!response.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("text/event-stream")) {
      response.close();
      throw new IOException("MiniMax returned an unexpected response format");
    }
    int maximum = Math.max(1024, EnvUtils.getInt("proxy.oauth.minimax.max-response-bytes", 32 * 1024 * 1024));
    Kv message = null;
    TreeMap<Integer, Kv> blocks = new TreeMap<>();
    TreeMap<Integer, StringBuilder> inputs = new TreeMap<>();
    boolean done = false;
    long total = 0;
    try (response; SseFrameInputStream frames = new SseFrameInputStream(response.body().byteStream(), maximum, frame -> frame)) {
      byte[] frame;
      while ((frame = frames.nextFrame()) != null) {
        total += frame.length;
        if (total > maximum) {
          throw new IOException("MiniMax response exceeds the configured limit");
        }
        String data = data(frame);
        if (data.isBlank()) {
          continue;
        }
        Kv event;
        try {
          event = ProxyJson.parse(data);
        } catch (RuntimeException error) {
          throw new IOException("MiniMax returned invalid stream data");
        }
        String type = event.getStr("type");
        if ("error".equals(type) || event.containsKey("error")) {
          throw new IOException("MiniMax reported a stream error");
        }
        if ("message_start".equals(type)) {
          message = ProxyJson.object(event.get("message"));
        } else if ("content_block_start".equals(type)) {
          blocks.put(index(event), ProxyJson.object(event.get("content_block")));
        } else if ("content_block_delta".equals(type)) {
          int index = index(event);
          Kv block = blocks.get(index);
          if (block == null) {
            throw new IOException("MiniMax stream delta has no content block");
          }
          Kv delta = ProxyJson.object(event.get("delta"));
          switch (ProxyJson.text(delta, "type", "")) {
          case "text_delta" -> append(block, delta, "text");
          case "thinking_delta" -> append(block, delta, "thinking");
          case "signature_delta" -> append(block, delta, "signature");
          case "input_json_delta" -> inputs.computeIfAbsent(index, ignored -> new StringBuilder()).append(ProxyJson.text(delta, "partial_json", ""));
          default -> { }
          }
        } else if ("message_delta".equals(type) && message != null) {
          message.set(ProxyJson.object(event.get("delta")));
          message.set("usage", ProxyJson.object(message.get("usage")).set(ProxyJson.object(event.get("usage"))));
        } else if ("message_stop".equals(type)) {
          done = true;
          break;
        }
      }
    }
    if (!done || message == null || message.getStr("stop_reason") == null) {
      throw new IOException("MiniMax stream ended before completion");
    }
    for (Integer index : inputs.keySet()) {
      try {
        blocks.get(index).set("input", ProxyJson.parse(inputs.get(index).toString()));
      } catch (RuntimeException error) {
        throw new IOException("MiniMax returned invalid tool arguments");
      }
    }
    message.set("content", new ArrayList<>(blocks.values()));
    return response.newBuilder().removeHeader("Content-Length").removeHeader("Transfer-Encoding")
        .header("Content-Type", "application/json")
        .body(ResponseBody.create(JsonUtils.toJsonWithLongAsNumber(message), MediaType.get("application/json"))).build();
  }

  private int index(Kv event) throws IOException {
    long value = ProxyJson.number(event, "index", -1);
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new IOException("MiniMax returned an invalid content block index");
    }
    return (int) value;
  }

  private void append(Kv block, Kv delta, String field) {
    block.set(field, ProxyJson.text(block, field, "") + ProxyJson.text(delta, field, ""));
  }

  private String data(byte[] frame) {
    StringBuilder result = new StringBuilder();
    for (String line : new String(frame, StandardCharsets.UTF_8).split("\\r?\\n")) {
      if (line.startsWith("data:")) {
        if (!result.isEmpty()) {
          result.append('\n');
        }
        result.append(line.substring(5).stripLeading());
      }
    }
    return result.toString();
  }
}
