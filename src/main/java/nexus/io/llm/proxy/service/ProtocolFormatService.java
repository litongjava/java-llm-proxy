package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.FormattedRequest;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.tio.utils.json.JsonUtils;

public class ProtocolFormatService {
  public FormattedRequest format(RequestRecord record) {
    Kv request = object(record.requestBody);
    String protocol = record.protocol;
    if (request.containsKey("contents")) {
      protocol = "gemini";
    } else if (request.containsKey("input")) {
      protocol = "responses";
    } else if (request.containsKey("system") || record.path.contains("messages")) {
      protocol = "anthropic";
    }
    StringBuilder prompt = new StringBuilder();
    appendSection(prompt, "SYSTEM", request.get("system"));
    appendSection(prompt, "SYSTEM", request.get("systemInstruction"));
    appendSection(prompt, "INSTRUCTIONS", request.get("instructions"));
    Object messages = request.get("messages");
    if (messages == null) {
      messages = request.get("contents");
    }
    if (messages == null) {
      messages = request.get("input");
    }
    if (messages == null) {
      messages = request.get("prompt");
    }
    if (messages instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> message) {
          Object role = message.get("role");
          Object content = message.get("content");
          if (content == null) {
            content = message.get("parts");
          }
          appendSection(prompt, role == null ? "INPUT" : role.toString().toUpperCase(), content == null ? item : content);
          appendSection(prompt, "TOOL CALLS", message.get("tool_calls"));
        } else {
          appendSection(prompt, "INPUT", item);
        }
      }
    } else {
      appendSection(prompt, "USER", messages);
    }
    appendSection(prompt, "TOOLS", request.get("tools"));
    StringBuilder response = new StringBuilder();
    for (Kv event : events(record.responseBody)) {
      appendResponse(response, event, record.stream);
    }
    if (response.isEmpty() && record.responseBody != null) {
      response.append(record.responseBody);
    }
    return new FormattedRequest(protocol, prompt.toString().strip(), response.toString().strip());
  }

  public void usage(RequestRecord record) {
    usage(record, record.responseBody);
  }

  public void usage(RequestRecord record, String body) {
    for (Kv event : events(body)) {
      String responseModel = event.getStr("model");
      if (responseModel == null) {
        responseModel = asObject(event.get("message")).getStr("model");
      }
      if (responseModel == null) {
        responseModel = asObject(event.get("response")).getStr("model");
      }
      if (responseModel == null) {
        responseModel = event.getStr("modelVersion");
      }
      if (responseModel != null && !responseModel.isBlank()) {
        record.responseModel = responseModel;
      }
      Kv usage = asObject(event.get("usage"));
      if (usage.isEmpty()) {
        usage = asObject(asObject(event.get("message")).get("usage"));
      }
      if (usage.isEmpty()) {
        usage = asObject(asObject(event.get("response")).get("usage"));
      }
      if (usage.isEmpty()) {
        usage = asObject(event.get("usageMetadata"));
      }
      record.inputTokens = Math.max(record.inputTokens, number(usage, "prompt_tokens", "input_tokens", "promptTokenCount"));
      record.outputTokens = Math.max(record.outputTokens, number(usage, "completion_tokens", "output_tokens", "candidatesTokenCount"));
      record.cachedTokens = Math.max(record.cachedTokens, number(usage, "cache_read_input_tokens", "cachedContentTokenCount"));
      record.cachedTokens = Math.max(record.cachedTokens, number(usage, "prompt_cache_hit_tokens"));
      record.cachedTokens = Math.max(record.cachedTokens, number(asObject(usage.get("prompt_tokens_details")), "cached_tokens"));
      record.cachedTokens = Math.max(record.cachedTokens, number(asObject(usage.get("input_tokens_details")), "cached_tokens"));
      record.reasoningTokens = Math.max(record.reasoningTokens, number(usage, "thoughtsTokenCount"));
      record.reasoningTokens = Math.max(record.reasoningTokens, number(asObject(usage.get("completion_tokens_details")), "reasoning_tokens"));
      record.reasoningTokens = Math.max(record.reasoningTokens, number(asObject(usage.get("output_tokens_details")), "reasoning_tokens"));
    }
  }

  private void appendResponse(StringBuilder out, Kv event, boolean stream) {
    Object choices = event.get("choices");
    if (choices instanceof List<?> list) {
      for (Object item : list) {
        Kv choice = asObject(item);
        Kv message = asObject(choice.get(stream ? "delta" : "message"));
        appendText(out, message.get("reasoning_content"));
        appendText(out, message.get("content"));
        appendText(out, choice.get("text"));
        appendSection(out, "TOOL CALLS", message.get("tool_calls"));
      }
    }
    appendText(out, event.get("content"));
    Object candidates = event.get("candidates");
    if (candidates instanceof List<?> list) {
      for (Object item : list) {
        appendText(out, asObject(asObject(item).get("content")).get("parts"));
      }
    }
    String type = String.valueOf(event.get("type"));
    Object delta = event.get("delta");
    if (delta instanceof String) {
      out.append(delta);
    } else if (delta instanceof Map<?, ?> map) {
      appendText(out, map.get("text"));
      appendText(out, map.get("thinking"));
      appendText(out, map.get("partial_json"));
    }
    if ("content_block_start".equals(type)) {
      Kv block = asObject(event.get("content_block"));
      if ("tool_use".equals(block.get("type"))) {
        appendSection(out, "TOOL " + block.get("name"), block.get("input"));
      } else {
        appendText(out, block.get("text"));
      }
    }
    if (!stream) {
      appendText(out, event.get("output"));
      appendText(out, event.get("output_text"));
    }
    appendSection(out, "ERROR", event.get("error"));
  }

  private void appendSection(StringBuilder out, String role, Object value) {
    if (value != null) {
      out.append("\n\n## ").append(role).append("\n");
      appendText(out, value);
    }
  }

  private void appendText(StringBuilder out, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String text) {
      out.append(text);
    } else if (value instanceof List<?> list) {
      for (Object item : list) {
        appendText(out, item);
      }
    } else if (value instanceof Map<?, ?> map) {
      if (map.containsKey("text")) {
        appendText(out, map.get("text"));
      } else if (map.containsKey("content")) {
        appendText(out, map.get("content"));
      } else if (map.containsKey("parts")) {
        appendText(out, map.get("parts"));
      } else {
        out.append('\n').append(JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat)).append('\n');
      }
    } else {
      out.append(value);
    }
  }

  public List<Kv> events(String body) {
    List<Kv> result = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return result;
    }
    if (body.stripLeading().startsWith("{")) {
      result.add(object(body));
      return result;
    }
    for (String block : body.replace("\r\n", "\n").split("\n\n")) {
      StringBuilder data = new StringBuilder();
      for (String line : block.split("\n")) {
        if (line.startsWith("data:")) {
          if (!data.isEmpty()) {
            data.append('\n');
          }
          String fragment = line.substring(5);
          data.append(fragment.startsWith(" ") ? fragment.substring(1) : fragment);
        }
      }
      if (!data.isEmpty() && !"[DONE]".contentEquals(data)) {
        result.add(object(data.toString()));
      }
    }
    return result;
  }

  private Kv object(String json) {
    try {
      return json == null ? Kv.create() : asObject(JSON.parse(json));
    } catch (RuntimeException e) {
      return Kv.create();
    }
  }

  private Kv asObject(Object value) {
    return value instanceof Map<?, ?> map ? Kv.create().set(map) : Kv.create();
  }

  private long number(Kv value, String... names) {
    for (String name : names) {
      Object raw = value.get(name);
      if (raw instanceof Number number) {
        return number.longValue();
      }
    }
    return 0;
  }
}
