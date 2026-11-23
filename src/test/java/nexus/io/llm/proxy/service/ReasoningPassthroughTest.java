package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/** Chat-completions upstreams stream thinking outside the text; Responses clients expect it. */
public class ReasoningPassthroughTest {
  private final ProtocolConversionService converter = new ProtocolConversionService();

  private static String chunk(Kv delta, String finish) {
    Kv choice = Kv.create().set("index", 0).set("delta", delta).set("finish_reason", finish);
    return "data: " + JsonUtils.toJsonWithLongAsNumber(Kv.create().set("id", "gw-1")
        .set("object", "chat.completion.chunk").set("model", "fixture").set("choices", List.of(choice))) + "\n\n";
  }

  private static String toolChunk(String id, String name, String arguments, String finish) {
    Kv function = Kv.create().set("name", name).set("arguments", arguments);
    Kv call = Kv.create().set("index", 0).set("type", "function").set("id", id).set("function", function);
    return chunk(Kv.create().set("tool_calls", List.of(call)), finish);
  }

  private static String usage(int input, int output) {
    Kv choice = Kv.create().set("index", 0).set("delta", Kv.create()).set("finish_reason", "stop");
    Kv body = Kv.create().set("id", "gw-1").set("object", "chat.completion.chunk").set("model", "fixture")
        .set("choices", List.of(choice))
        .set("usage", Kv.create().set("prompt_tokens", input).set("completion_tokens", output).set("total_tokens", input + output));
    return "data: " + JsonUtils.toJsonWithLongAsNumber(body) + "\n\n";
  }

  private static String convert(String sse) throws Exception {
    ByteArrayInputStream stream = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
    return new String(new ProtocolEventStream(stream, "openai", "responses", "fixture").readAllBytes(), StandardCharsets.UTF_8);
  }

  private static List<Kv> events(String sse) {
    List<Kv> events = new ArrayList<>();
    for (String line : sse.split("\n")) {
      if (line.startsWith("data: ") && !line.contains("[DONE]")) { events.add(ProxyJson.parse(line.substring(6))); }
    }
    return events;
  }

  private static Kv completed(String sse) {
    for (Kv event : events(sse)) {
      if ("response.completed".equals(event.getStr("type"))) { return ProxyJson.object(event.get("response")); }
    }
    fail("No response.completed event");
    return null;
  }

  private static List<String> types(String sse) {
    List<String> types = new ArrayList<>();
    for (Kv event : events(sse)) { types.add(event.getStr("type")); }
    return types;
  }

  private static List<Integer> doneIndexes(String sse) {
    List<Integer> indexes = new ArrayList<>();
    for (Kv event : events(sse)) {
      if ("response.output_item.done".equals(event.getStr("type"))) { indexes.add((int) ProxyJson.number(event, "output_index", -1)); }
    }
    return indexes;
  }

  @Test
  public void toolOnlyTurnStreamsReasoningWithoutAnEmptyMessage() throws Exception {
    String sse = chunk(Kv.create().set("role", "assistant").set("content", ""), null)
        + chunk(Kv.create().set("reasoning_content", "think"), null)
        + chunk(Kv.create().set("reasoning_content", "ing"), null)
        + toolChunk("call_1", "Bash", "{\"cmd\":", null)
        + toolChunk(null, "Bash", "\"ls\"}", "tool_calls")
        + usage(10, 5) + "data: [DONE]\n\n";
    String converted = convert(sse);
    List<?> items = ProxyJson.list(completed(converted).get("output"));
    assertEquals(2, items.size());
    assertEquals("reasoning", ProxyJson.object(items.get(0)).getStr("type"));
    assertEquals("thinking", ProxyJson.object(ProxyJson.list(ProxyJson.object(items.get(0)).get("summary")).getFirst()).getStr("text"));
    assertEquals("function_call", ProxyJson.object(items.get(1)).getStr("type"));
    assertEquals("call_1", ProxyJson.object(items.get(1)).getStr("call_id"));
    assertEquals("{\"cmd\":\"ls\"}", ProxyJson.object(items.get(1)).getStr("arguments"));
    List<String> types = types(converted);
    assertTrue(types.contains("response.reasoning_summary_text.delta"));
    assertFalse(types.contains("response.output_text.delta"));
    assertEquals(List.of(0, 1), doneIndexes(converted));
  }

  @Test
  public void textTurnKeepsReasoningThenMessage() throws Exception {
    String sse = chunk(Kv.create().set("reasoning_content", "pondering"), null)
        + chunk(Kv.create().set("content", "## "), null)
        + chunk(Kv.create().set("content", "答案"), null)
        + usage(10, 6) + "data: [DONE]\n\n";
    String converted = convert(sse);
    List<?> items = ProxyJson.list(completed(converted).get("output"));
    assertEquals(2, items.size());
    assertEquals("reasoning", ProxyJson.object(items.get(0)).getStr("type"));
    assertEquals("message", ProxyJson.object(items.get(1)).getStr("type"));
    Kv part = ProxyJson.object(ProxyJson.list(ProxyJson.object(items.get(1)).get("content")).getFirst());
    assertEquals("## 答案", part.getStr("text"));
    List<String> types = types(converted);
    assertTrue(types.contains("response.reasoning_summary_text.delta"));
    assertTrue(types.contains("response.output_text.delta"));
    assertEquals(List.of(0, 1), doneIndexes(converted));
  }

  @Test
  public void whitespaceOnlyTextNeverOpensAMessage() throws Exception {
    String sse = chunk(Kv.create().set("content", " "), null)
        + chunk(Kv.create().set("content", "\n"), null)
        + toolChunk("call_2", "Bash", "{}", "tool_calls")
        + usage(10, 4) + "data: [DONE]\n\n";
    String converted = convert(sse);
    List<?> items = ProxyJson.list(completed(converted).get("output"));
    assertEquals(1, items.size());
    assertEquals("function_call", ProxyJson.object(items.get(0)).getStr("type"));
    assertFalse(types(converted).contains("response.output_text.delta"));
    assertEquals(List.of(0), doneIndexes(converted));
  }

  @Test
  public void reasoningItemsFromHistoryNeverReachConvertedUpstreams() {
    Kv input = ProxyJson.parse("""
        {"input":[
          {"type":"reasoning","id":"rs-1","summary":[{"type":"summary_text","text":"internal thinking"}],
           "encrypted_content":"opaque-from-another-model"},
          {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
          {"type":"message","role":"assistant","content":[{"type":"output_text","text":"previous answer"}]},
          {"type":"function_call","call_id":"call_1","name":"Bash","arguments":"{}"},
          {"type":"function_call_output","call_id":"call_1","output":"ok"}
        ]}
        """);
    for (String target : List.of("openai", "anthropic", "gemini")) {
      Kv converted = converter.request(input, "responses", target, "fixture", true);
      String json = JsonUtils.toJsonWithLongAsNumber(converted);
      assertFalse(target + " must not receive thinking text", json.contains("internal thinking"));
      assertFalse(target + " must not receive the foreign state", json.contains("opaque-from-another-model"));
      assertFalse(target, json.contains("\"reasoning\""));
    }
    assertEquals(4, ProxyJson.list(converter.request(input, "responses", "openai", "fixture", true).get("messages")).size());
  }

  @Test
  public void nonStreamResponseCarriesReasoningBeforeTheMessage() {
    Kv chat = ProxyJson.parse("""
        {"id":"chatcmpl-1","object":"chat.completion","model":"fixture","choices":[{"index":0,"finish_reason":"stop",
          "message":{"role":"assistant","content":"done","reasoning_content":"pondering"}}],
         "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
        """);
    Kv response = converter.response(chat, "openai", "responses", "fixture");
    List<?> items = ProxyJson.list(response.get("output"));
    assertEquals(2, items.size());
    assertEquals("reasoning", ProxyJson.object(items.get(0)).getStr("type"));
    assertEquals("pondering", ProxyJson.object(ProxyJson.list(ProxyJson.object(items.get(0)).get("summary")).getFirst()).getStr("text"));
    assertEquals("message", ProxyJson.object(items.get(1)).getStr("type"));
    assertEquals("done", ProxyJson.object(ProxyJson.list(ProxyJson.object(items.get(1)).get("content")).getFirst()).getStr("text"));
  }
}
