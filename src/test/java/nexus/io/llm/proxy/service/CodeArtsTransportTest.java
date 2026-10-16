package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class CodeArtsTransportTest {
  @Test
  public void actualWireContentTypeMatchesSignedCanonicalHeader() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> contentType = new AtomicReference<>();
    AtomicReference<byte[]> wireBody = new AtomicReference<>();
    server.createContext("/chat/completions", exchange -> {
      contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      wireBody.set(exchange.getRequestBody().readAllBytes());
      exchange.sendResponseHeaders(204, -1); exchange.close();
    });
    server.start();
    try {
      String json = "{\"model\":\"test\",\"messages\":[]}";
      Request original = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions")
          .post(RequestBody.create(json, MediaType.get("application/json"))).build();
      Kv credential = Kv.create().set("access_key_id", "ak").set("secret_access_key", "sk").set("security_token", "st");
      Request signed = new CodeArtsTransportService().prepare(original, credential, "test");
      try (Response ignored = new OkHttpClient().newCall(signed).execute()) {
        assertEquals(signed.header("content-type"), contentType.get());
        assertEquals("application/json", contentType.get());
        assertEquals(signed.header("x-sdk-content-sha256"), new CodeArtsCryptoService().sha256(wireBody.get()));
      }
    } finally { server.stop(0); }
  }

  @Test
  public void dsmlSurvivesEveryCharacterBoundaryWithoutLeakingMarkers() throws Exception {
    CodeArtsDsmlParser parser = new CodeArtsDsmlParser();
    String source = "hello <thought>reason</thought><｜DSML｜tool_calls><｜DSML｜invoke name=\"write\"><｜DSML｜parameter name=\"content\" string=\"true\">a\nb</｜DSML｜parameter><｜DSML｜parameter name=\"count\">3</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls> done";
    StringBuilder text = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    List<Object> calls = new ArrayList<>();
    for (int i = 0; i < source.length(); i++) {
      Kv delta = parser.feed(source.substring(i, i + 1), "content", i == source.length() - 1);
      if (delta.get("content") != null) { text.append(delta.getStr("content")); }
      if (delta.get("reasoning_content") != null) { reasoning.append(delta.getStr("reasoning_content")); }
      calls.addAll(ProxyJson.list(delta.get("tool_calls")));
    }
    assertEquals("hello  done", text.toString());
    assertEquals("reason", reasoning.toString());
    assertEquals(1, calls.size());
    Kv function = ProxyJson.object(ProxyJson.object(calls.get(0)).get("function"));
    assertEquals("write", function.getStr("name"));
    Kv args = ProxyJson.parse(function.getStr("arguments"));
    assertEquals("a\nb", args.getStr("content"));
    assertEquals(3, ProxyJson.number(args, "count", 0));
  }

  @Test(expected = IOException.class)
  public void rejectsIncompleteDsmlInsteadOfExecutingPartialArguments() throws Exception {
    new CodeArtsDsmlParser().feed("<｜DSML｜tool_calls><｜DSML｜invoke", "content", true);
  }

  @Test
  public void convertsDeepSeekToolWireAndAggregatesResponse() throws Exception {
    CodeArtsTransportService transport = new CodeArtsTransportService();
    Kv body = Kv.create().set("messages", List.of(Kv.create().set("role", "user").set("content", "hi")))
        .set("tools", List.of(Kv.create().set("type", "function").set("function", Kv.create().set("name", "write"))));
    transport.payload(body, "deepseek-v4-flash", "/chat/completions");
    assertFalse(body.containsKey("tools"));
    assertTrue(JsonUtils.toJson(body.get("messages")).contains("DSML"));
    String dsml = "<｜DSML｜tool_calls><｜DSML｜invoke name=\"write\"><｜DSML｜parameter name=\"text\" string=\"true\">hi</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls>";
    Kv chunk = Kv.create().set("id", "id-1").set("choices", List.of(Kv.create().set("index", 0).set("delta", Kv.create().set("content", dsml)).set("finish_reason", "stop")));
    String wire = "data: " + JsonUtils.toJson(chunk) + "\n\ndata: [DONE]\n\n";
    try (Response response = transport.response(response(wire), false)) {
      Kv result = ProxyJson.parse(response.body().string());
      Kv choice = ProxyJson.object(ProxyJson.list(result.get("choices")).get(0));
      assertEquals("tool_calls", choice.getStr("finish_reason"));
      assertEquals(1, ProxyJson.list(ProxyJson.object(choice.get("message")).get("tool_calls")).size());
    }
  }

  @Test(expected = IOException.class)
  public void rejectsEmbeddedHuaweiError() throws Exception {
    try (Response response = new CodeArtsTransportService().response(response("data: {\"error_code\":\"InferHub.4291.200\",\"error_msg\":\"private\"}\n\n"), true)) {
      response.body().string();
    }
  }

  private Response response(String wire) {
    return new Response.Builder().request(new Request.Builder().url("http://localhost/").build()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Content-Type", "text/event-stream").body(ResponseBody.create(wire, MediaType.get("text/event-stream"))).build();
  }
}
