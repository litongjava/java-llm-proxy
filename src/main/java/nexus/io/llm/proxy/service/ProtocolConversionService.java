package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;

/** Converts chat payloads while preserving native payloads when protocols match. */
public class ProtocolConversionService {
  static String thoughtSignature(Kv tool) {
    Object signature = ProxyJson.object(ProxyJson.object(tool.get("extra_content")).get("google")).get("thought_signature");
    return signature instanceof String text && !text.isBlank() ? text : null;
  }

  static void withThoughtSignature(Kv tool, String signature) {
    if (signature != null && !signature.isBlank()) {
      tool.set("extra_content", Kv.create().set("google", Kv.create().set("thought_signature", signature)));
    }
  }

  private final GeminiUsageService geminiUsage = Aop.get(GeminiUsageService.class);
  public Kv request(Kv body, String from, String to, String model, boolean stream) {
    if (from.equals(to)) { return body; }
    Kv canonical = canonicalRequest(body, from);
    canonical.set("model", model).set("stream", stream);
    String effort = ReasoningEffortService.extract(body);
    if (effort != null) { canonical.set("reasoning_effort", effort); }
    if ("openai".equals(to)) { return canonical; }
    java.util.Map<String, String> callNames = new java.util.HashMap<>();
    for (Object value : ProxyJson.list(canonical.get("messages"))) {
      for (Object callValue : ProxyJson.list(ProxyJson.object(value).get("tool_calls"))) {
        Kv call = ProxyJson.object(callValue);
        callNames.put(call.getStr("id"), ProxyJson.object(call.get("function")).getStr("name"));
      }
    }
    List<Kv> messages = new ArrayList<>();
    List<Kv> system = new ArrayList<>();
    List<Kv> inputs = new ArrayList<>();
    for (Object value : ProxyJson.list(canonical.get("messages"))) {
      Kv message = ProxyJson.object(value);
      String role = message.getStr("role");
      // Empty assistant text often accompanies a tool call in Responses history.
      // Vertex/Claude rejects these blocks, even when a valid call follows them.
      List<Kv> parts = parts(message.get("content")).stream()
          .filter(part -> !(part.get("text") instanceof String text && text.isBlank())).toList();
      if ("system".equals(role) || "developer".equals(role)) { system.addAll(parts); continue; }
      if ("anthropic".equals(to)) {
        List<Kv> blocks = new ArrayList<>();
        if ("tool".equals(role)) {
          Object result = message.get("content");
          if (result instanceof List<?>) {
            result = parts.stream().map(this::anthropicPart).toList();
          }
          blocks.add(Kv.create().set("type", "tool_result").set("tool_use_id", message.get("tool_call_id")).set("content", result));
          role = "user";
        } else {
          for (Kv part : parts) { blocks.add(anthropicPart(part)); }
          for (Object toolValue : ProxyJson.list(message.get("tool_calls"))) {
            Kv tool = ProxyJson.object(toolValue);
            Kv function = ProxyJson.object(tool.get("function"));
            blocks.add(Kv.create().set("type", "tool_use").set("id", tool.get("id")).set("name", function.get("name"))
                .set("input", arguments(function.get("arguments"))));
          }
        }
        if (!blocks.isEmpty()) { messages.add(Kv.create().set("role", role).set("content", blocks)); }
      } else if ("gemini".equals(to)) {
        List<Kv> blocks = new ArrayList<>();
        for (Kv part : parts) {
          if (part.get("text") != null) { blocks.add(Kv.create().set("text", part.get("text"))); }
          else if (part.get("image_url") != null) { blocks.add(geminiImage(part)); }
        }
        if ("tool".equals(role)) {
          String id = message.getStr("tool_call_id");
          String name = callNames.getOrDefault(id, message.getStr("name"));
          Object result = message.get("content");
          blocks = new ArrayList<>();
          if (name == null) {
            blocks.add(Kv.create().set("text", "Tool result: " + JsonUtils.toJsonWithLongAsNumber(result)));
          } else {
            Kv functionResponse = Kv.create().set("id", id).set("name", name);
            List<Kv> media = new ArrayList<>();
            if (result instanceof List<?>) {
              List<Object> textResults = new ArrayList<>();
              for (Object item : ProxyJson.list(result)) {
                Kv block = ProxyJson.object(item);
                if ("input_image".equals(block.getStr("type")) || "image_url".equals(block.getStr("type"))) { media.add(geminiImage(block)); }
                else if (block.get("text") != null) { textResults.add(block.get("text")); }
                else { textResults.add(item); }
              }
              result = textResults;
            }
            functionResponse.set("response", Kv.create().set("result", result == null ? "" : result));
            if (!media.isEmpty()) { functionResponse.set("parts", media); }
            blocks.add(Kv.create().set("functionResponse", functionResponse));
          }
          role = "user";
        }
        for (Object toolValue : ProxyJson.list(message.get("tool_calls"))) {
          Kv tool = ProxyJson.object(toolValue);
          Kv function = ProxyJson.object(tool.get("function"));
          Kv part = Kv.create().set("functionCall", Kv.create().set("id", tool.get("id")).set("name", function.get("name")).set("args", arguments(function.get("arguments"))));
          String signature = thoughtSignature(tool);
          if (signature != null) { part.set("thoughtSignature", signature); }
          blocks.add(part);
        }
        if (!blocks.isEmpty()) { messages.add(Kv.create().set("role", "assistant".equals(role) ? "model" : role).set("parts", blocks)); }
      } else if ("responses".equals(to)) {
        if ("tool".equals(role)) {
          inputs.add(Kv.create().set("type", "function_call_output").set("call_id", message.get("tool_call_id")).set("output", responsesToolOutput(message.get("content"))));
          continue;
        }
        List<Kv> blocks = new ArrayList<>();
        for (Kv part : parts) {
          if (part.get("text") != null) { blocks.add(Kv.create().set("type", "assistant".equals(role) ? "output_text" : "input_text").set("text", part.get("text"))); }
          else if (part.get("image_url") != null) { blocks.add(Kv.create().set("type", "input_image").set("image_url", imageUrl(part))); }
        }
        if (!blocks.isEmpty()) { inputs.add(Kv.create().set("type", "message").set("role", role).set("content", blocks)); }
        for (Object toolValue : ProxyJson.list(message.get("tool_calls"))) {
          Kv tool = ProxyJson.object(toolValue);
          Kv function = ProxyJson.object(tool.get("function"));
          inputs.add(Kv.create().set("type", "function_call").set("call_id", tool.get("id")).set("name", function.get("name")).set("arguments", function.get("arguments")));
        }
      }
    }
    Kv output = Kv.create().set("model", model).set("stream", stream);
    if ("responses".equals(to) && effort != null) {
      output.set("reasoning", Kv.create().set("effort", effort));
    }
    for (String key : List.of("temperature", "top_p", "metadata")) { if (canonical.containsKey(key)) { output.set(key, canonical.get(key)); } }
    Object max = canonical.get("max_tokens");
    List<Kv> tools = new ArrayList<>();
    for (Object value : ProxyJson.list(canonical.get("tools"))) {
      Kv function = ProxyJson.object(ProxyJson.object(value).get("function"));
      if ("anthropic".equals(to)) { tools.add(Kv.create().set("name", function.get("name")).set("description", function.get("description")).set("input_schema", function.get("parameters"))); }
      else if ("gemini".equals(to)) {
        Kv declaration = Kv.create().set("name", function.get("name"));
        if (function.get("description") != null) { declaration.set("description", function.get("description")); }
        if (function.get("parameters") != null) { declaration.set("parametersJsonSchema", new GeminiSchemaService().convert(function.get("parameters"))); }
        tools.add(declaration);
      }
      else { tools.add(Kv.create().set(function).set("type", "responses".equals(to) ? "function" : null)); }
    }
    if ("anthropic".equals(to)) {
      output.set("messages", messages).set("max_tokens", max == null ? 8192 : max);
      if (!system.isEmpty()) { output.set("system", system); }
      if (!tools.isEmpty()) { output.set("tools", tools); }
      if (canonical.get("stop") != null) { output.set("stop_sequences", canonical.get("stop") instanceof String ? List.of(canonical.get("stop")) : canonical.get("stop")); }
    } else if ("gemini".equals(to)) {
      output.remove("model"); output.remove("stream"); output.remove("temperature"); output.remove("top_p"); output.remove("metadata");
      output.set("contents", new GeminiHistoryService().normalize(messages));
      if (!system.isEmpty()) { output.set("systemInstruction", Kv.create().set("parts", system.stream().map(part -> Kv.create().set("text", part.get("text"))).toList())); }
      Kv generation = Kv.create();
      if (max != null) { generation.set("maxOutputTokens", max); }
      if (canonical.get("temperature") != null) { generation.set("temperature", canonical.get("temperature")); }
      if (canonical.get("top_p") != null) { generation.set("topP", canonical.get("top_p")); }
      output.set("generationConfig", generation);
      if (!tools.isEmpty()) { tools.forEach(tool -> tool.remove("type")); output.set("tools", List.of(Kv.create().set("functionDeclarations", tools))); }
    } else if ("responses".equals(to)) {
      output.set("input", inputs);
      if (!system.isEmpty()) { output.set("instructions", system.stream().map(part -> ProxyJson.text(part, "text", "")).reduce((a,b) -> a + "\n" + b).orElse("")); }
      if (max != null) { output.set("max_output_tokens", max); }
      if (!tools.isEmpty()) { output.set("tools", tools); }
    }
    if ("responses".equals(to)) {
      if (canonical.containsKey("parallel_tool_calls")) { output.set("parallel_tool_calls", canonical.get("parallel_tool_calls")); }
      Object choice = canonical.get("tool_choice");
      if (choice instanceof String) { output.set("tool_choice", choice); }
      else if ("function".equals(ProxyJson.object(choice).getStr("type"))) {
        output.set("tool_choice", Kv.create().set("type", "function")
            .set("name", ProxyJson.object(ProxyJson.object(choice).get("function")).get("name")));
      }
    } else if ("anthropic".equals(to) && !tools.isEmpty()) {
      Object choice = canonical.get("tool_choice");
      Kv selected = Kv.create();
      if (choice instanceof String text) { selected.set("type", "required".equals(text) ? "any" : text); }
      else if ("function".equals(ProxyJson.object(choice).getStr("type"))) {
        selected.set("type", "tool").set("name", ProxyJson.object(ProxyJson.object(choice).get("function")).get("name"));
      }
      if (canonical.get("parallel_tool_calls") instanceof Boolean parallel && !"none".equals(selected.getStr("type"))) {
        if (selected.isEmpty()) { selected.set("type", "auto"); }
        selected.set("disable_parallel_tool_use", !parallel);
      }
      if (!selected.isEmpty()) { output.set("tool_choice", selected); }
    }
    return output;
  }

  private Kv canonicalRequest(Kv body, String from) {
    if ("openai".equals(from)) { return Kv.create().set(body); }
    List<Kv> messages = new ArrayList<>();
    List<Kv> tools = new ArrayList<>();
    Kv result = Kv.create();
    for (String key : List.of("temperature", "top_p", "metadata", "max_tokens", "stop")) { if (body.containsKey(key)) { result.set(key, body.get(key)); } }
    if ("anthropic".equals(from)) {
      Kv choice = ProxyJson.object(body.get("tool_choice"));
      String choiceType = choice.getStr("type");
      if ("tool".equals(choiceType)) {
        result.set("tool_choice", Kv.create().set("type", "function").set("function", Kv.create().set("name", choice.get("name"))));
      } else if (choiceType != null) { result.set("tool_choice", "any".equals(choiceType) ? "required" : choiceType); }
      if (choice.get("disable_parallel_tool_use") instanceof Boolean disabled) { result.set("parallel_tool_calls", !disabled); }
      if (body.get("system") != null) { messages.add(Kv.create().set("role", "system").set("content", body.get("system"))); }
      for (Object value : ProxyJson.list(body.get("messages"))) {
        Kv message = ProxyJson.object(value);
        List<Kv> content = new ArrayList<>();
        List<Kv> calls = new ArrayList<>();
        for (Kv part : parts(message.get("content"))) {
          switch (ProxyJson.text(part, "type", "text")) {
            case "tool_use" -> calls.add(call(part.getStr("id"), part.getStr("name"), JsonUtils.toJsonWithLongAsNumber(part.get("input"))));
            case "tool_result" -> messages.add(Kv.create().set("role", "tool").set("tool_call_id", part.get("tool_use_id")).set("content", part.get("content")));
            case "image" -> {
              Kv source = ProxyJson.object(part.get("source"));
              String url = source.getStr("url");
              if (url == null) { url = "data:" + source.getStr("media_type") + ";base64," + source.getStr("data"); }
              content.add(Kv.create().set("type", "image_url").set("image_url", Kv.create().set("url", url)));
            }
            default -> content.add(part);
          }
        }
        if (!content.isEmpty() || !calls.isEmpty()) {
          Kv converted = Kv.create().set("role", message.get("role")).set("content", content);
          if (!calls.isEmpty()) { converted.set("tool_calls", calls); }
          messages.add(converted);
        }
      }
      for (Object value : ProxyJson.list(body.get("tools"))) {
        Kv tool = ProxyJson.object(value);
        tools.add(Kv.create().set("type", "function").set("function", Kv.create().set("name", tool.get("name"))
            .set("description", tool.get("description")).set("parameters", tool.get("input_schema"))));
      }
    } else if ("responses".equals(from)) {
      if (body.get("instructions") != null) { messages.add(Kv.create().set("role", "system").set("content", body.get("instructions"))); }
      Object raw = body.get("input");
      List<?> input = raw instanceof String ? List.of(Kv.create().set("role", "user").set("content", raw)) : ProxyJson.list(raw);
      for (Object value : input) {
        Kv item = ProxyJson.object(value);
        String type = ProxyJson.text(item, "type", "message");
        if ("function_call".equals(type)) { messages.add(Kv.create().set("role", "assistant").set("tool_calls", List.of(call(item.getStr("call_id"), ResponsesToolMapping.name(item.getStr("namespace"), item.getStr("name")), item.getStr("arguments"))))); }
        else if ("function_call_output".equals(type)) { messages.add(Kv.create().set("role", "tool").set("tool_call_id", item.get("call_id")).set("content", item.get("output"))); }
        else if ("message".equals(type)) {
          List<Kv> blocks = new ArrayList<>();
          for (Kv part : parts(item.get("content"))) {
            if ("input_image".equals(part.getStr("type"))) { blocks.add(Kv.create().set("type", "image_url").set("image_url", Kv.create().set("url", imageUrl(part)))); }
            else if (part.get("text") != null) { blocks.add(Kv.create().set("type", "text").set("text", part.get("text"))); }
          }
          messages.add(Kv.create().set("role", item.get("role")).set("content", blocks));
        }
      }
      tools.addAll(new ResponsesToolMapping(body).tools());
      Object choice = body.get("tool_choice");
      if (choice instanceof String) { result.set("tool_choice", choice); }
      else if ("function".equals(ProxyJson.object(choice).getStr("type"))) {
        Kv selected = ProxyJson.object(choice);
        result.set("tool_choice", Kv.create().set("type", "function").set("function", Kv.create().set("name",
            ResponsesToolMapping.name(selected.getStr("namespace"), selected.getStr("name")))));
      }
      if (body.containsKey("parallel_tool_calls")) { result.set("parallel_tool_calls", body.get("parallel_tool_calls")); }
      if (body.get("max_output_tokens") != null) { result.set("max_tokens", body.get("max_output_tokens")); }
    } else if ("gemini".equals(from)) {
      Kv system = ProxyJson.object(body.get("systemInstruction"));
      if (system.isEmpty()) { system = ProxyJson.object(body.get("system_instruction")); }
      if (!system.isEmpty()) { messages.add(Kv.create().set("role", "system").set("content", ProxyJson.list(system.get("parts")))); }
      for (Object value : ProxyJson.list(body.get("contents"))) {
        Kv item = ProxyJson.object(value);
        List<Kv> content = new ArrayList<>();
        List<Kv> calls = new ArrayList<>();
        for (Object partValue : ProxyJson.list(item.get("parts"))) {
          Kv part = ProxyJson.object(partValue);
          if (part.get("text") != null) { content.add(Kv.create().set("type", "text").set("text", part.get("text"))); }
          else if (part.get("functionCall") != null) {
            Kv function = ProxyJson.object(part.get("functionCall"));
            Kv convertedCall = call(ProxyJson.text(function, "id", function.getStr("name")), function.getStr("name"), JsonUtils.toJsonWithLongAsNumber(function.get("args")));
            withThoughtSignature(convertedCall, part.getStr("thoughtSignature"));
            calls.add(convertedCall);
          } else if (part.get("functionResponse") != null) {
            Kv function = ProxyJson.object(part.get("functionResponse"));
            messages.add(Kv.create().set("role", "tool").set("tool_call_id", ProxyJson.text(function, "id", function.getStr("name")))
                .set("name", function.get("name")).set("content", JsonUtils.toJsonWithLongAsNumber(function.get("response"))));
          } else if (part.get("inlineData") != null) {
            Kv image = ProxyJson.object(part.get("inlineData"));
            content.add(Kv.create().set("type", "image_url").set("image_url", Kv.create().set("url", "data:" + image.getStr("mimeType") + ";base64," + image.getStr("data"))));
          }
        }
        if (!content.isEmpty() || !calls.isEmpty()) {
          Kv converted = Kv.create().set("role", "model".equals(item.getStr("role")) ? "assistant" : ProxyJson.text(item, "role", "user")).set("content", content);
          if (!calls.isEmpty()) { converted.set("tool_calls", calls); }
          messages.add(converted);
        }
      }
      for (Object value : ProxyJson.list(body.get("tools"))) {
        for (Object declaration : ProxyJson.list(ProxyJson.object(value).get("functionDeclarations"))) {
          Kv function = ProxyJson.object(declaration);
          if (function.get("parametersJsonSchema") != null) { function.set("parameters", function.remove("parametersJsonSchema")); }
          tools.add(Kv.create().set("type", "function").set("function", function));
        }
      }
      Kv generation = ProxyJson.object(body.get("generationConfig"));
      if (generation.get("maxOutputTokens") != null) { result.set("max_tokens", generation.get("maxOutputTokens")); }
      if (generation.get("temperature") != null) { result.set("temperature", generation.get("temperature")); }
    }
    result.set("messages", messages);
    if (!tools.isEmpty()) { result.set("tools", tools); }
    return result;
  }

  public Kv response(Kv input, String from, String to, String model) {
    if (from.equals(to)) { return input; }
    Kv canonical;
    if ("openai".equals(from)) { canonical = input; }
    else {
      Kv request = switch (from) {
        case "anthropic" -> Kv.create().set("messages", List.of(Kv.create().set("role", "assistant").set("content", input.get("content"))));
        case "gemini" -> Kv.create().set("contents", ProxyJson.list(input.get("candidates")).stream().map(value -> ProxyJson.object(value).get("content")).toList());
        default -> Kv.create().set("input", input.get("output"));
      };
      List<?> converted = ProxyJson.list(canonicalRequest(request, from).get("messages"));
      Kv message = Kv.create().set("role", "assistant");
      StringBuilder text = new StringBuilder();
      List<Object> calls = new ArrayList<>();
      for (Object value : converted) {
        Kv item = ProxyJson.object(value);
        for (Kv part : parts(item.get("content"))) { text.append(ProxyJson.text(part, "text", "")); }
        calls.addAll(ProxyJson.list(item.get("tool_calls")));
      }
      message.set("content", text.toString());
      if (!calls.isEmpty()) { message.set("tool_calls", calls); }
      Kv usage = ProxyJson.object(input.get("usage"));
      if ("gemini".equals(from)) { usage = ProxyJson.object(input.get("usageMetadata")); }
      long in = ProxyJson.number(usage, "input_tokens", ProxyJson.number(usage, "promptTokenCount", 0));
      long out = ProxyJson.number(usage, "output_tokens", ProxyJson.number(usage, "candidatesTokenCount", 0));
      String sourceFinish = "anthropic".equals(from) ? input.getStr("stop_reason") : null;
      if ("gemini".equals(from)) {
        List<?> candidates = ProxyJson.list(input.get("candidates"));
        if (!candidates.isEmpty()) { sourceFinish = ProxyJson.object(candidates.getFirst()).getStr("finishReason"); }
        if (sourceFinish == null && ProxyJson.object(input.get("promptFeedback")).getStr("blockReason") != null) {
          sourceFinish = "OTHER_BLOCKED";
        }
      }
      String finish = sourceFinish == null || "STOP".equals(sourceFinish)
          ? message.containsKey("tool_calls") ? "tool_calls" : "stop" : ProtocolStopReason.chat(sourceFinish);
      canonical = Kv.create().set("id", ProxyJson.text(input, "id", "chatcmpl-" + SnowflakeIdUtils.id())).set("object", "chat.completion").set("model", model)
          .set("choices", List.of(Kv.create().set("index", 0).set("message", message).set("finish_reason", finish)))
          .set("usage", Kv.create().set("prompt_tokens", (int) in).set("completion_tokens", (int) out).set("total_tokens", (int) (in + out)));
      if ("gemini".equals(from)) {
        canonical.set("usage", geminiUsage.openAi(usage));
      }
    }
    if ("openai".equals(to)) { return canonical; }
    List<?> choices = ProxyJson.list(canonical.get("choices"));
    Kv message = choices.isEmpty() ? Kv.create().set("role", "assistant").set("content", "") : ProxyJson.object(ProxyJson.object(choices.getFirst()).get("message"));
    String finishReason = choices.isEmpty() ? "stop" : ProxyJson.object(choices.getFirst()).getStr("finish_reason");
    Kv converted = request(Kv.create().set("messages", List.of(message)), "openai", to, model, false);
    Kv usage = ProxyJson.object(canonical.get("usage"));
    int in = (int) ProxyJson.number(usage, "prompt_tokens", 0);
    int out = (int) ProxyJson.number(usage, "completion_tokens", 0);
    if ("anthropic".equals(to)) {
      return Kv.create().set("id", canonical.get("id")).set("type", "message").set("role", "assistant").set("model", model)
          .set("content", ProxyJson.list(converted.get("messages")).isEmpty() ? List.of() : ProxyJson.object(ProxyJson.list(converted.get("messages")).getFirst()).get("content"))
          .set("stop_reason", ProtocolStopReason.anthropic(finishReason, message.containsKey("tool_calls"))).set("stop_sequence", null)
          .set("usage", "gemini".equals(from) ? geminiUsage.anthropic(usage) : Kv.create().set("input_tokens", in).set("output_tokens", out));
    }
    if ("gemini".equals(to)) {
      return Kv.create().set("candidates", List.of(Kv.create().set("index", 0).set("content", ProxyJson.list(converted.get("contents")).getFirst()).set("finishReason", ProtocolStopReason.gemini(finishReason))))
          .set("usageMetadata", Kv.create().set("promptTokenCount", in).set("candidatesTokenCount", out).set("totalTokenCount", in + out)).set("modelVersion", model);
    }
    return ProtocolStopReason.response(Kv.create().set("id", canonical.get("id")).set("object", "response").set("status", "completed").set("model", model)
        .set("output", converted.get("input")).set("usage", "gemini".equals(from) ? geminiUsage.responses(usage)
            : responsesUsage(usage, in, out)), finishReason);
  }

  static Kv responsesUsage(Kv usage, int input, int output) {
    Kv result = Kv.create().set("input_tokens", input).set("output_tokens", output).set("total_tokens", input + output);
    Kv details = ProxyJson.object(usage.get("output_tokens_details"));
    if (!details.containsKey("reasoning_tokens")) { details = ProxyJson.object(usage.get("completion_tokens_details")); }
    Object reasoning = details.containsKey("reasoning_tokens") ? details.get("reasoning_tokens") : usage.get("reasoning_tokens");
    if (reasoning != null) { result.set("output_tokens_details", Kv.create().set("reasoning_tokens", reasoning)); }
    return result;
  }

  private Object responsesToolOutput(Object value) {
    if (!(value instanceof List<?>)) { return value == null ? "" : value; }
    List<Kv> output = new ArrayList<>();
    for (Kv part : parts(value)) {
      String type = part.getStr("type");
      if (part.get("text") != null) {
        output.add(Kv.create().set("type", "input_text").set("text", part.get("text")));
      } else if ("image".equals(type)) {
        Kv source = ProxyJson.object(part.get("source"));
        String url = source.getStr("url");
        if (url == null) { url = "data:" + source.getStr("media_type") + ";base64," + source.getStr("data"); }
        output.add(Kv.create().set("type", "input_image").set("image_url", url));
      } else if ("image_url".equals(type)) {
        output.add(Kv.create().set("type", "input_image").set("image_url", imageUrl(part)));
      } else if ("input_image".equals(type) || "input_file".equals(type)) {
        output.add(Kv.create().set(part));
      } else {
        output.add(Kv.create().set("type", "input_text").set("text", JsonUtils.toJsonWithLongAsNumber(part)));
      }
    }
    return output;
  }

  private List<Kv> parts(Object value) {
    if (value instanceof String text) { return List.of(Kv.create().set("type", "text").set("text", text)); }
    return ProxyJson.list(value).stream().map(ProxyJson::object).toList();
  }
  private Kv call(String id, String name, String arguments) { return Kv.create().set("id", id).set("type", "function").set("function", Kv.create().set("name", name).set("arguments", arguments)); }
  private Kv arguments(Object value) {
    if (value instanceof String text) {
      try { return ProxyJson.parse(text); } catch (RuntimeException error) { return Kv.create().set("result", text); }
    }
    return ProxyJson.object(value);
  }
  private String imageUrl(Kv part) {
    Object image = part.get("image_url");
    Object raw = image instanceof String ? image : ProxyJson.object(image).get("url");
    BusinessException.require(raw instanceof String && !((String) raw).isBlank(), 400, "INVALID_IMAGE",
        part.get("file_id") != null ? "Image file_id cannot be converted to this upstream protocol; provide image_url"
            : "Image requires a non-empty image_url string or image_url.url");
    String url = ((String) raw).trim();
    if (url.startsWith("data:")) {
      int separator = url.indexOf(";base64,");
      BusinessException.require(url.startsWith("data:image/") && separator > 11 && separator + 8 < url.length(),
          400, "INVALID_IMAGE", "Image data URL must contain an image MIME type and base64 data");
    } else {
      boolean valid = false;
      try {
        java.net.URI uri = java.net.URI.create(url);
        valid = ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) || "gs".equals(uri.getScheme()))
            && uri.getHost() != null;
      } catch (IllegalArgumentException ignored) { }
      BusinessException.require(valid, 400, "INVALID_IMAGE", "Unsupported image URL; provide an HTTP(S), gs, or base64 image URL");
    }
    return url;
  }
  private Kv anthropicPart(Kv part) {
    if ("input_text".equals(part.getStr("type")) || "output_text".equals(part.getStr("type"))) {
      return Kv.create().set("type", "text").set("text", part.get("text"));
    }
    if ("input_image".equals(part.getStr("type"))) { imageUrl(part); }
    if (part.get("image_url") == null) { return part; }
    String url = imageUrl(part);
    Kv source = Kv.create().set("type", "url").set("url", url);
    if (url.startsWith("data:") && url.contains(";base64,")) {
      String[] segments = url.substring(5).split(";base64,", 2);
      source = Kv.create().set("type", "base64").set("media_type", segments[0]).set("data", segments[1]);
    }
    return Kv.create().set("type", "image").set("source", source);
  }
  private Kv geminiImage(Kv part) {
    String url = imageUrl(part);
    if (url.startsWith("data:") && url.contains(";base64,")) {
      String[] segments = url.substring(5).split(";base64,", 2);
      return Kv.create().set("inlineData", Kv.create().set("mimeType", segments[0]).set("data", segments[1]));
    }
    return Kv.create().set("fileData", Kv.create().set("fileUri", url));
  }
}
