package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.llm.proxy.model.OAuthSession;
import nexus.io.model.exception.BusinessException;

public class TraeServiceTest {
  private final TraeService service = new TraeService();

  @Test
  public void loginContainsLocalCallbackAndTraceBinding() {
    OAuthSession session = new OAuthSession();
    session.spec = new OAuthProviderSpec("trae", "", null, null, null, null, "", "http://127.0.0.1:18080/authorize", false);
    String url = service.initialize(session);
    assertTrue(url.contains("auth_callback_url=http%3A%2F%2F127.0.0.1%3A18080%2Fauthorize"));
    assertTrue(url.contains("login_trace_id=" + session.pluginMetadata.getStr("login_trace_id")));
    assertEquals(32, session.pluginMetadata.getStr("machine_id").length());
  }

  @Test(expected = BusinessException.class)
  public void rejectsUncorrelatedCallbackBeforeNetwork() throws Exception {
    OAuthSession session = new OAuthSession();
    session.pluginMetadata = Kv.create().set("login_trace_id", "expected");
    service.exchange(Kv.create().set("login_trace_id", "other"), session);
  }

  @Test
  public void refreshRotatesTokenAndPreservesDevice() {
    Kv previous = Kv.create().set("machine_id", "device").set("refresh_token", "old");
    Kv next = service.normalizeToken(Kv.create().set("Result", Kv.create().set("Token", "access").set("RefreshToken", "new").set("TokenExpireAt", 2000000000000L)), previous);
    assertEquals("new", next.getStr("refresh_token"));
    assertEquals("device", next.getStr("machine_id"));
    assertEquals("2033-05-18T03:33:20Z", next.getStr("expired"));
  }

  @Test
  public void translatesToolHistoryAndSchemaWithoutMutatingOriginal() {
    Kv function = Kv.create().set("name", "lookup").set("parameters", Kv.create().set("type", "object"));
    Kv input = Kv.create().set("model", "glm-5.2__dev").set("messages", List.of(Kv.create().set("role", "assistant")
        .set("content", "hello").set("tool_calls", List.of(Kv.create().set("function", Kv.create().set("name", "lookup"))))))
        .set("tools", List.of(Kv.create().set("type", "function").set("function", function)));
    Kv payload = service.payload(input);
    assertEquals("glm-5.2", payload.getStr("config_name"));
    assertEquals(Boolean.TRUE, payload.getBoolean("stream"));
    Kv tool = ProxyJson.object(ProxyJson.list(payload.get("tools")).get(0));
    assertTrue(ProxyJson.object(tool.get("function")).get("parameters") instanceof String);
    assertTrue(function.get("parameters") instanceof Kv);
  }

  @Test
  public void convertsSoloContentAndDoneFrames() throws Exception {
    TraeTransportService transport = new TraeTransportService();
    String output = new String(transport.convertFrame("event: output\ndata: {\"response\":\"hello\",\"reasoning_content\":\"reason\"}\n\n".getBytes(StandardCharsets.UTF_8), "id", "model"), StandardCharsets.UTF_8);
    assertTrue(output.contains("hello"));
    assertTrue(output.contains("reasoning_content"));
    String done = new String(transport.convertFrame("event: done\ndata: {\"finish_reason\":\"tool_calls\"}\n\n".getBytes(StandardCharsets.UTF_8), "id", "model"), StandardCharsets.UTF_8);
    assertTrue(done.endsWith("data: [DONE]\n\n"));
    assertTrue(done.contains("tool_calls"));
  }
}
