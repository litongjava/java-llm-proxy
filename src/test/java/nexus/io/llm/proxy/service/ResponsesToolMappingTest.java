package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResponsesToolMappingTest {
  private Kv request() {
    return ProxyJson.parse("{\"tools\":[{\"type\":\"web_search\"},{\"type\":\"function\",\"name\":\"run\"},{\"type\":\"namespace\",\"name\":\"agent\",\"tools\":[{\"type\":\"function\",\"name\":\"run\",\"parameters\":{\"type\":\"object\"}}]}]}");
  }

  @Test public void filtersBuiltinsAndFlattensWithoutNameCollisions() {
    Kv source = request();
    Kv converted = new ProtocolConversionService().request(source, "responses", "openai", "test", true);
    assertEquals(2, ProxyJson.list(converted.get("tools")).size());
    String alias = ResponsesToolMapping.name("agent", "run");
    assertTrue(alias.matches("[A-Za-z0-9_-]{1,128}"));
    assertNotEquals("run", alias);
    assertEquals(3, ProxyJson.list(source.get("tools")).size());
    Kv function = ProxyJson.object(ProxyJson.object(ProxyJson.list(converted.get("tools")).get(1)).get("function"));
    assertEquals(alias, function.getStr("name"));
    assertFalse(function.containsKey("tools"));
  }

  @Test public void mapsHistoryAndExplicitChoice() {
    Kv source = request().set("input", java.util.List.of(Kv.create().set("type", "function_call").set("namespace", "agent").set("name", "run").set("call_id", "c1").set("arguments", "{}")))
        .set("tool_choice", Kv.create().set("type", "function").set("namespace", "agent").set("name", "run"));
    Kv converted = new ProtocolConversionService().request(source, "responses", "openai", "test", false);
    assertTrue(converted.toString().contains(ResponsesToolMapping.name("agent", "run")));
    assertEquals(ResponsesToolMapping.name("agent", "run"), ProxyJson.object(ProxyJson.object(converted.get("tool_choice")).get("function")).getStr("name"));
  }

  @Test public void restoresStreamAndCompletedOutput() throws Exception {
    String alias = ResponsesToolMapping.name("agent", "run");
    String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"" + alias + "\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
    try (ProtocolEventStream stream = new ProtocolEventStream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), "openai", "responses", "test", new ResponsesToolMapping(request()))) {
      String output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(output.contains(alias));
      assertTrue(output.contains("\"namespace\":\"agent\""));
      assertTrue(output.contains("response.completed"));
    }
  }

  @Test(expected = IllegalArgumentException.class) public void rejectsForcedUnsupportedTool() {
    new ResponsesToolMapping(request().set("tool_choice", Kv.create().set("type", "web_search")));
  }

  @Test public void preservesNativeResponsesTools() {
    Kv source = request();
    assertSame(source, new ProtocolConversionService().request(source, "responses", "responses", "test", false));
  }

  @Test public void restoresNonStreamingResponse() {
    String alias = ResponsesToolMapping.name("agent", "run");
    Kv upstream = ProxyJson.parse("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"" + alias + "\",\"arguments\":\"{}\"}}]}}]}");
    Kv converted = new ProtocolConversionService().response(upstream, "openai", "responses", "test");
    new ResponsesToolMapping(request()).restore(converted);
    Kv call = ProxyJson.object(ProxyJson.list(converted.get("output")).get(0));
    assertEquals("run", call.getStr("name"));
    assertEquals("agent", call.getStr("namespace"));
    assertEquals("c1", call.getStr("call_id"));
  }
}
