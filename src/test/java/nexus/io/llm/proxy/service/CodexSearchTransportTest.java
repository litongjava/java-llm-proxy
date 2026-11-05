package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;

import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class CodexSearchTransportTest {
  private final OAuthTransportService transport = new OAuthTransportService();

  private Provider provider() {
    Provider provider = new Provider();
    provider.authType = "oauth";
    provider.upstreamType = "codex";
    return provider;
  }

  private String event(String type, Kv fields) {
    return "data: " + JsonUtils.toJsonWithLongAsNumber(fields.set("type", type)) + "\n\n";
  }

  private Response stream(String body) {
    return new Response.Builder().request(new Request.Builder().url("http://localhost/responses").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Content-Type", "text/event-stream").header("Content-Length", "999")
        .body(ResponseBody.create(body, MediaType.get("text/event-stream"))).build();
  }

  @Test
  public void normalizesTextInputWithoutLosingSearchOptions() {
    Kv body = Kv.create().set("input", "搜索文档").set("tools", List.of(Kv.create().set("type", "web_search")))
        .set("include", List.of("web_search_call.action.sources"));
    Kv result = transport.payload(provider(), body, "gpt-6-astra", "/responses");
    Kv message = ProxyJson.object(ProxyJson.list(result.get("input")).get(0));
    assertEquals("user", message.getStr("role"));
    assertEquals("搜索文档", ProxyJson.object(ProxyJson.list(message.get("content")).get(0)).getStr("text"));
    assertEquals("web_search", ProxyJson.object(ProxyJson.list(result.get("tools")).get(0)).getStr("type"));
    assertEquals(List.of("web_search_call.action.sources"), result.get("include"));
  }

  @Test
  public void assemblesCompletedItemsInIndexOrderWithCitationsAndUsage() throws Exception {
    Kv search = Kv.create().set("type", "web_search_call").set("status", "completed")
        .set("action", Kv.create().set("sources", List.of(Kv.create().set("url", "https://example.com"))));
    Kv message = Kv.create().set("type", "message").set("content", List.of(Kv.create().set("type", "output_text")
        .set("text", "Result").set("annotations", List.of(Kv.create().set("type", "url_citation").set("url", "https://example.com")))));
    String body = event("response.output_item.done", Kv.create().set("output_index", 1).set("item", message))
        + event("response.output_item.done", Kv.create().set("output_index", 0).set("item", search))
        + event("response.output_item.done", Kv.create().set("output_index", 0).set("item", search))
        + event("response.completed", Kv.create().set("response", Kv.create().set("output", List.of())
            .set("usage", Kv.create().set("input_tokens", 42))));
    try (Response response = transport.response(provider(), stream(body), false)) {
      Kv result = ProxyJson.parse(response.body().string());
      assertEquals(JsonUtils.toJsonWithLongAsNumber(List.of(search, message)), JsonUtils.toJsonWithLongAsNumber(result.get("output")));
      assertEquals(42, ProxyJson.number(ProxyJson.object(result.get("usage")), "input_tokens", 0));
      assertNull(response.header("Content-Length"));
    }
  }

  @Test
  public void preservesTerminalOutputAndLeavesClientStreamsUntouched() throws Exception {
    Kv message = Kv.create().set("type", "message").set("content", List.of());
    String body = event("response.output_item.done", Kv.create().set("output_index", 0).set("item", Kv.create().set("type", "reasoning")))
        + event("response.completed", Kv.create().set("response", Kv.create().set("output", List.of(message))));
    try (Response response = transport.response(provider(), stream(body), false)) {
      assertEquals(JsonUtils.toJsonWithLongAsNumber(List.of(message)), JsonUtils.toJsonWithLongAsNumber(ProxyJson.parse(response.body().string()).get("output")));
    }
    try (Response upstream = stream(body)) {
      assertSame(upstream, transport.response(provider(), upstream, true));
    }
  }

  @Test
  public void neverTreatsPartialOutputAsSuccess() throws Exception {
    for (String ending : List.of("", event("response.failed", Kv.create()))) {
      String body = event("response.output_item.done", Kv.create().set("output_index", 0)
          .set("item", Kv.create().set("type", "web_search_call"))) + ending;
      try {
        transport.response(provider(), stream(body), false);
        fail("Incomplete streams must fail");
      } catch (nexus.io.model.exception.BusinessException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }
}
