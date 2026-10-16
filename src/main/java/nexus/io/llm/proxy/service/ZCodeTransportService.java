package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import com.jfinal.kit.Kv;
import nexus.io.model.exception.BusinessException;
import nexus.io.jfinal.aop.Aop;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Anthropic streaming with explicit, user-actionable captcha and access-policy failures. */
public class ZCodeTransportService {
  public void headers(Kv credential, Request.Builder request) {
    request.removeHeader("x-api-key").removeHeader("anthropic-beta");
    Aop.get(ZCodeService.class).headers(credential).forEach((key, value) -> request.header(key.toString(), value.toString()));
    request.header("Authorization", "Bearer " + Aop.get(ZCodeService.class).accessToken(credential));
    request.header("Accept", "text/event-stream");
    String param = request.build().header("x-aliyun-captcha-verify-param");
    String region = request.build().header("x-aliyun-captcha-verify-region");
    BusinessException.require((param == null && region == null) || (param != null && !param.isBlank() && param.length() <= 16384
        && region != null && region.matches("[a-zA-Z0-9_-]{1,32}")), 400, "ZCODE_CAPTCHA_INPUT",
        "Supply both captcha verification headers from a user-completed verification");
  }

  public Kv payload(Kv body, String endpoint) {
    BusinessException.require("/messages".equals(endpoint), 400, "ZCODE_ENDPOINT_UNSUPPORTED", "ZCode supports Anthropic Messages");
    // Preserve caller system content; account access checks must not be bypassed by impersonation.
    body.set("stream", true);
    body.remove("stream_options");
    String effort = ProxyJson.text(body, "reasoning_effort", ProxyJson.object(body.get("reasoning")).getStr("effort"));
    if (effort != null) {
      if ("none".equals(effort)) {
        body.set("thinking", Kv.create().set("type", "disabled"));
      } else {
        body.set("thinking", Kv.create().set("type", "adaptive"));
        body.set("output_config", ProxyJson.object(body.get("output_config")).set("effort", effort));
      }
    }
    body.remove("reasoning_effort");
    body.remove("reasoning");
    return body;
  }

  /** Call immediately after every HTTP attempt, before request or credential retry decisions. */
  public void rejectAccessChallenge(Response response) throws IOException {
    if (response.body() != null && (!response.isSuccessful() || response.header("Content-Type", "").contains("application/json"))) {
      String preview = response.peekBody(65536).string();
      Kv error;
      try {
        error = ProxyJson.parse(preview);
      } catch (RuntimeException ignored) {
        error = Kv.create();
      }
      long code = ProxyJson.number(error, "code", 0);
      if (code == 3007 || code == 3012) {
        response.close();
        BusinessException.require(false, 403, code == 3007 ? "ZCODE_CAPTCHA_REQUIRED" : "ZCODE_ACCESS_RESTRICTED",
            code == 3007 ? "ZCode requires a new user-completed captcha. Supply the verification param and region headers for this request."
                : "ZCode rejected this request under its account access policy. Use the official client or contact the provider; automatic retries are disabled.");
      }
    }
  }

  public Response response(Response response, boolean clientStream) throws IOException {
    rejectAccessChallenge(response);
    if (!response.isSuccessful() || response.body() == null || clientStream) {
      return response;
    }
    if (!response.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("text/event-stream")) {
      response.close();
      throw new IOException("ZCode returned an unexpected response format");
    }
    int maximum = Math.max(1024, EnvUtils.getInt("proxy.oauth.zcode.max-response-bytes", 32 * 1024 * 1024));
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
          throw new IOException("ZCode response exceeds the configured limit");
        }
        String data = data(frame);
        if (data.isBlank()) {
          continue;
        }
        Kv event;
        try {
          event = ProxyJson.parse(data);
        } catch (RuntimeException error) {
          throw new IOException("ZCode returned invalid stream data");
        }
        String type = event.getStr("type");
        if ("error".equals(type) || event.containsKey("error")) {
          throw new IOException("ZCode reported a stream error");
        }
        if ("message_start".equals(type)) {
          message = ProxyJson.object(event.get("message"));
        } else if ("content_block_start".equals(type)) {
          blocks.put(index(event), ProxyJson.object(event.get("content_block")));
        } else if ("content_block_delta".equals(type)) {
          int index = index(event);
          Kv block = blocks.get(index);
          if (block == null) {
            throw new IOException("ZCode stream delta has no content block");
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
      throw new IOException("ZCode stream ended before completion");
    }
    for (Integer index : inputs.keySet()) {
      try {
        blocks.get(index).set("input", ProxyJson.parse(inputs.get(index).toString()));
      } catch (RuntimeException error) {
        throw new IOException("ZCode returned invalid tool arguments");
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
      throw new IOException("ZCode returned an invalid content block index");
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
