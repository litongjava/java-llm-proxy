package nexus.io.llm.proxy.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;

/** Incremental SSE translation; buffering is limited to the current event and tool arguments. */
public class ProtocolEventStream extends InputStream {
  private final GeminiUsageService geminiUsage = Aop.get(GeminiUsageService.class);
  private final BufferedReader reader;
  private final String from;
  private final String to;
  private final String model;
  private final ResponsesToolMapping responseTools;
  private final String id = "chatcmpl-" + SnowflakeIdUtils.id();
  private final Kv tools = Kv.create();
  private ByteArrayInputStream output = new ByteArrayInputStream(new byte[0]);
  private boolean started;
  private boolean finished;
  private boolean terminal;
  private String finishReason;
  private final Kv rawUsage = Kv.create();
  private int inputTokens;
  private int outputTokens;
  private final Kv geminiMetadata = Kv.create();
  private int sequence;
  private final StringBuilder text = new StringBuilder();

  public ProtocolEventStream(InputStream input, String from, String to, String model) {
    this(input, from, to, model, null);
  }

  public ProtocolEventStream(InputStream input, String from, String to, String model, ResponsesToolMapping responseTools) {
    this.responseTools = responseTools;
    reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    this.from = from; this.to = to; this.model = model;
  }
  @Override
  public int read() throws IOException {
    byte[] byteValue = new byte[1];
    return read(byteValue, 0, 1) < 0 ? -1 : byteValue[0] & 255;
  }
  @Override
  public int read(byte[] destination, int offset, int length) throws IOException {
    if (length == 0) { return 0; }
    int count;
    while ((count = output.read(destination, offset, length)) < 0) {
      if (finished) { return -1; }
      String data = nextEvent();
      String events;
      if (data == null || "[DONE]".equals(data)) {
        if (data == null && !terminal) { throw new IOException("Upstream stream ended before its completion event"); }
        events = finish(); finished = true;
      }
      else {
        try {
          events = translate(ProxyJson.parse(data));
          if (terminal && !"openai".equals(from)) { events += finish(); finished = true; }
        }
        catch (RuntimeException error) { throw new IOException("Invalid upstream SSE event", error); }
      }
      output = new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8));
    }
    return count;
  }
  private String nextEvent() throws IOException {
    StringBuilder data = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isEmpty()) { if (!data.isEmpty()) { return data.toString(); } continue; }
      if (line.startsWith("data:")) {
        if (!data.isEmpty()) { data.append('\n'); }
        data.append(line.substring(5).stripLeading());
        if (data.length() > 4 * 1024 * 1024) { throw new IOException("SSE event exceeds size limit"); }
      }
    }
    return data.isEmpty() ? null : data.toString();
  }
  private String translate(Kv event) throws IOException {
    if (event.get("error") != null || "error".equals(event.getStr("type")) || "response.failed".equals(event.getStr("type"))) { throw new IOException("Upstream stream returned an error"); }
    StringBuilder events = new StringBuilder(start());
    if ("openai".equals(from)) {
      usage(ProxyJson.object(event.get("usage")), "prompt_tokens", "completion_tokens");
      for (Object value : ProxyJson.list(event.get("choices"))) {
        Kv choice = ProxyJson.object(value);
        if (choice.get("finish_reason") != null) { terminal = true; finishReason = choice.getStr("finish_reason"); }
        Kv delta = ProxyJson.object(choice.get("delta"));
        if (delta.getStr("content") != null) { events.append(text(delta.getStr("content"))); }
        for (Object call : ProxyJson.list(delta.get("tool_calls"))) {
          Kv tool = ProxyJson.object(call);
          Kv function = ProxyJson.object(tool.get("function"));
          int index = (int) ProxyJson.number(tool, "index", 0);
          events.append(tool(index, tool.getStr("id"), function.getStr("name"), function.getStr("arguments")));
          String signature = ProtocolConversionService.thoughtSignature(tool);
          if (signature != null) { tools.set(String.valueOf(index), ProxyJson.object(tools.get(String.valueOf(index))).set("thoughtSignature", signature)); }
        }
      }
    } else if ("anthropic".equals(from)) {
      String type = event.getStr("type");
      if ("message_delta".equals(type)) { finishReason = ProxyJson.object(event.get("delta")).getStr("stop_reason"); }
      if ("message_stop".equals(type)) { terminal = true; }
      if ("message_start".equals(type)) { usage(ProxyJson.object(ProxyJson.object(event.get("message")).get("usage")), "input_tokens", "output_tokens"); }
      if ("content_block_start".equals(type)) {
        Kv block = ProxyJson.object(event.get("content_block"));
        if ("tool_use".equals(block.getStr("type"))) { events.append(tool((int) ProxyJson.number(event, "index", 0), block.getStr("id"), block.getStr("name"), "")); }
        else if (block.getStr("text") != null) { events.append(text(block.getStr("text"))); }
      }
      if ("content_block_delta".equals(type)) {
        Kv delta = ProxyJson.object(event.get("delta"));
        if (delta.getStr("text") != null) { events.append(text(delta.getStr("text"))); }
        if (delta.getStr("partial_json") != null) { events.append(tool((int) ProxyJson.number(event, "index", 0), null, null, delta.getStr("partial_json"))); }
      }
      usage(ProxyJson.object(event.get("usage")), "input_tokens", "output_tokens");
    } else if ("responses".equals(from)) {
      String type = event.getStr("type");
      if ("response.output_text.delta".equals(type)) { events.append(text(ProxyJson.text(event, "delta", ""))); }
      if ("response.output_item.added".equals(type)) {
        Kv item = ProxyJson.object(event.get("item"));
        if ("function_call".equals(item.getStr("type"))) { events.append(tool((int) ProxyJson.number(event, "output_index", 0), item.getStr("call_id"), item.getStr("name"), item.getStr("arguments"))); }
      }
      if ("response.function_call_arguments.delta".equals(type)) { events.append(tool((int) ProxyJson.number(event, "output_index", 0), null, null, event.getStr("delta"))); }
      if ("response.completed".equals(type)) { terminal = true; usage(ProxyJson.object(ProxyJson.object(event.get("response")).get("usage")), "input_tokens", "output_tokens"); }
    } else {
      geminiMetadata.set(ProxyJson.object(event.get("usageMetadata")));
      usage(geminiUsage.openAi(geminiMetadata), "prompt_tokens", "completion_tokens");
      if (ProxyJson.object(event.get("promptFeedback")).getStr("blockReason") != null) {
        terminal = true;
        finishReason = "OTHER_BLOCKED";
      }
      for (Object candidate : ProxyJson.list(event.get("candidates"))) {
        String reason = ProxyJson.object(candidate).getStr("finishReason");
        if (reason != null) {
          terminal = true;
          finishReason = "STOP".equals(reason) ? null : reason;
        }
        Kv content = ProxyJson.object(ProxyJson.object(candidate).get("content"));
        for (Object value : ProxyJson.list(content.get("parts"))) {
          Kv part = ProxyJson.object(value);
          if (part.getStr("text") != null && !Boolean.TRUE.equals(part.getBoolean("thought"))) { events.append(text(part.getStr("text"))); }
          if (part.get("functionCall") != null) {
            Kv function = ProxyJson.object(part.get("functionCall"));
            int index = tools.size();
            String translated = tool(index, function.getStr("id"), function.getStr("name"), JsonUtils.toJsonWithLongAsNumber(function.get("args")));
            String signature = part.getStr("thoughtSignature");
            if (signature != null) {
              tools.set(String.valueOf(index), ProxyJson.object(tools.get(String.valueOf(index))).set("thoughtSignature", signature));
              if ("openai".equals(to)) {
                Kv signed = Kv.create().set("index", index);
                ProtocolConversionService.withThoughtSignature(signed, signature);
                translated += openai(Kv.create().set("tool_calls", List.of(signed)), null, null);
              }
            }
            events.append(translated);
          }
        }
      }
    }
    return events.toString();
  }
  private String start() {
    if (started) { return ""; }
    started = true;
    return switch (to) {
      case "anthropic" -> event("message_start", Kv.create().set("message", Kv.create().set("id", id).set("type", "message").set("role", "assistant").set("model", model)
          .set("content", List.of()).set("usage", Kv.create().set("input_tokens", 0).set("output_tokens", 0))))
          + event("content_block_start", Kv.create().set("index", 0).set("content_block", Kv.create().set("type", "text").set("text", "")));
      case "responses" -> event("response.created", Kv.create().set("response", Kv.create().set("id", id).set("object", "response").set("status", "in_progress").set("model", model).set("output", List.of())))
          + event("response.output_item.added", Kv.create().set("output_index", 0).set("item", Kv.create().set("id", id + "-message").set("type", "message").set("role", "assistant").set("content", List.of())))
          + event("response.content_part.added", Kv.create().set("output_index", 0).set("item_id", id + "-message").set("content_index", 0)
              .set("part", Kv.create().set("type", "output_text").set("text", "").set("annotations", List.of())));
      default -> "";
    };
  }
  private String text(String delta) throws IOException {
    text.append(delta);
    if (text.length() > 32 * 1024 * 1024) { throw new IOException("Stream response exceeds size limit"); }
    return switch (to) {
      case "anthropic" -> event("content_block_delta", Kv.create().set("index", 0).set("delta", Kv.create().set("type", "text_delta").set("text", delta)));
      case "responses" -> event("response.output_text.delta", Kv.create().set("item_id", id + "-message").set("output_index", 0).set("content_index", 0).set("delta", delta));
      case "gemini" -> event(null, Kv.create().set("candidates", List.of(Kv.create().set("index", 0).set("content", Kv.create().set("role", "model").set("parts", List.of(Kv.create().set("text", delta)))))));
      default -> openai(Kv.create().set("content", delta), null, null);
    };
  }
  private String tool(int index, String callId, String name, String arguments) throws IOException {
    Kv existing = ProxyJson.object(tools.get(String.valueOf(index)));
    boolean first = existing.isEmpty();
    if (first) {
      existing.set("id", callId == null ? "call-" + SnowflakeIdUtils.id() : callId).set("name", name == null ? "" : name).set("arguments", "").set("index", tools.size() + 1);
    }
    if (name != null) { existing.set("name", name); }
    String fragment = arguments == null ? "" : arguments;
    existing.set("arguments", existing.getStr("arguments") + fragment);
    if (existing.getStr("arguments").length() > 4 * 1024 * 1024) { throw new IOException("Tool arguments exceed size limit"); }
    tools.set(String.valueOf(index), existing);
    int targetIndex = existing.getInt("index");
    String prefix = "";
    if ("anthropic".equals(to)) {
      // Parallel OpenAI tool fragments must become sequential Anthropic content blocks.
      return "";
    }
    if ("responses".equals(to)) {
      if (first) { prefix = event("response.output_item.added", Kv.create().set("output_index", targetIndex).set("item", Kv.create().set("id", existing.get("id")).set("call_id", existing.get("id")).set("type", "function_call").set("name", existing.get("name")).set("arguments", ""))); }
      return prefix + event("response.function_call_arguments.delta", Kv.create().set("item_id", existing.get("id")).set("output_index", targetIndex).set("delta", fragment));
    }
    if ("gemini".equals(to)) { return ""; }
    Kv function = Kv.create().set("arguments", fragment);
    if (name != null) { function.set("name", name); }
    Kv call = Kv.create().set("index", targetIndex - 1).set("function", function);
    if (first) { call.set("id", existing.get("id")).set("type", "function"); }
    return openai(Kv.create().set("tool_calls", List.of(call)), null, null);
  }
  private String finish() {
    StringBuilder result = new StringBuilder(start());
    Kv usage = Kv.create().set(rawUsage).set("prompt_tokens", inputTokens).set("completion_tokens", outputTokens).set("total_tokens", inputTokens + outputTokens);
    if ("gemini".equals(from)) {
      usage = geminiUsage.openAi(geminiMetadata);
    } else if ("anthropic".equals(from)) {
      ProtocolConversionService.copyClaudeThinkingTokens(rawUsage, usage);
    }
    if ("anthropic".equals(to)) {
      result.append(event("content_block_stop", Kv.create().set("index", 0)));
      for (Object value : tools.values()) {
        Kv call = ProxyJson.object(value);
        result.append(event("content_block_start", Kv.create().set("index", call.get("index")).set("content_block", Kv.create().set("type", "tool_use")
            .set("id", call.get("id")).set("name", call.get("name")).set("input", Kv.create()))));
        result.append(event("content_block_delta", Kv.create().set("index", call.get("index")).set("delta", Kv.create().set("type", "input_json_delta").set("partial_json", call.get("arguments")))));
        result.append(event("content_block_stop", Kv.create().set("index", call.get("index"))));
      }
      result.append(event("message_delta", Kv.create().set("delta", Kv.create().set("stop_reason", ProtocolStopReason.anthropic(finishReason, !tools.isEmpty())).set("stop_sequence", null))
          .set("usage", "gemini".equals(from) ? geminiUsage.anthropic(usage)
              : Kv.create().set("input_tokens", inputTokens).set("output_tokens", outputTokens))));
      result.append(event("message_stop", Kv.create()));
    } else if ("gemini".equals(to)) {
      List<Kv> parts = new ArrayList<>();
      for (Object value : tools.values()) {
        Kv call = ProxyJson.object(value);
        Kv part = Kv.create().set("functionCall", Kv.create().set("id", call.get("id")).set("name", call.get("name")).set("args", ProxyJson.parse(ProxyJson.text(call, "arguments", "{}"))));
        if (call.getStr("thoughtSignature") != null) { part.set("thoughtSignature", call.get("thoughtSignature")); }
        parts.add(part);
      }
      result.append(event(null, Kv.create().set("candidates", List.of(Kv.create().set("index", 0).set("content", Kv.create().set("role", "model").set("parts", parts)).set("finishReason", ProtocolStopReason.gemini(finishReason))))
          .set("usageMetadata", Kv.create().set("promptTokenCount", inputTokens).set("candidatesTokenCount", outputTokens).set("totalTokenCount", inputTokens + outputTokens))));
    } else if ("responses".equals(to)) {
      Kv part = Kv.create().set("type", "output_text").set("text", text.toString()).set("annotations", List.of());
      result.append(event("response.output_text.done", Kv.create().set("item_id", id + "-message").set("output_index", 0).set("content_index", 0).set("text", text.toString())));
      result.append(event("response.content_part.done", Kv.create().set("item_id", id + "-message").set("output_index", 0).set("content_index", 0).set("part", part)));
      List<Kv> outputs = new ArrayList<>();
      outputs.add(Kv.create().set("id", id + "-message").set("type", "message").set("role", "assistant").set("status", "completed")
          .set("content", List.of(Kv.create().set("type", "output_text").set("text", text.toString()).set("annotations", List.of()))));
      for (Object value : tools.values()) {
        Kv call = ProxyJson.object(value);
        outputs.add(Kv.create().set("type", "function_call").set("call_id", call.get("id")).set("name", call.get("name")).set("arguments", call.get("arguments")));
        result.append(event("response.function_call_arguments.done", Kv.create().set("item_id", call.get("id")).set("output_index", call.get("index")).set("arguments", call.get("arguments"))));
      }
      for (int index = 0; index < outputs.size(); index++) { result.append(event("response.output_item.done", Kv.create().set("output_index", index).set("item", outputs.get(index)))); }
      Kv response = ProtocolStopReason.response(Kv.create().set("id", id).set("object", "response").set("status", "completed")
          .set("model", model).set("output", outputs).set("usage", "gemini".equals(from) ? geminiUsage.responses(usage)
              : ProtocolConversionService.responsesUsage(usage, inputTokens, outputTokens)), finishReason);
      result.append(event("response." + response.getStr("status"), Kv.create().set("response", response)));
    } else { result.append(openai(Kv.create(), finishReason == null ? tools.isEmpty() ? "stop" : "tool_calls" : ProtocolStopReason.chat(finishReason), usage)).append("data: [DONE]\n\n"); }
    return result.toString();
  }
  private String openai(Kv delta, String finish, Kv usage) {
    Kv body = Kv.create().set("id", id).set("object", "chat.completion.chunk").set("model", model)
        .set("choices", List.of(Kv.create().set("index", 0).set("delta", delta).set("finish_reason", finish)));
    if (usage != null) { body.set("usage", usage); }
    return event(null, body);
  }
  private String event(String type, Kv value) {
    if (responseTools != null) { responseTools.restore(value); }
    if (type != null) { value.set("type", type); }
    if ("responses".equals(to)) { value.set("sequence_number", sequence++); }
    return (type == null ? "" : "event: " + type + "\n") + "data: " + JsonUtils.toJsonWithLongAsNumber(value) + "\n\n";
  }
  private void usage(Kv usage, String input, String output) {
    rawUsage.set(usage);
    inputTokens = (int) ProxyJson.number(usage, input, inputTokens);
    outputTokens = (int) ProxyJson.number(usage, output, outputTokens);
  }
  @Override
  public void close() throws IOException { reader.close(); }
}
