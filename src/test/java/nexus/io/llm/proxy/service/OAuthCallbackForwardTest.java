package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;
import nexus.io.llm.proxy.model.*;
import nexus.io.model.exception.BusinessException;
import org.junit.Test;

public class OAuthCallbackForwardTest {
  private static class StubOAuth extends OAuthService {
    OAuthCallbackInput received;
    @Override public Kv callback(OAuthCallbackInput input) {
      received = input;
      return Kv.create().set("status", "ok");
    }
  }
  @SuppressWarnings("unchecked")
  private static <T> T field(OAuthService service, String name) throws Exception {
    Field field = OAuthService.class.getDeclaredField(name);
    field.setAccessible(true);
    return (T) field.get(service);
  }
  private Kv send(StubOAuth service, String url) throws Exception {
    OAuthCallbackInput input = new OAuthCallbackInput(); input.redirect_url = url;
    return service.forwardCallback(input);
  }
  @Test public void forwardsOnlyWaitingMatchingLoopbackSessionAndPreservesParameters() throws Exception {
    StubOAuth service = new StubOAuth();
    int port;
    try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    OAuthSession session = new OAuthSession();
    session.state = "test-state";
    session.spec = new OAuthProviderService().get("codex");
    session.spec.provider = "codex";
    session.spec.redirectUri = "http://127.0.0.1:" + port + "/auth/callback";
    session.expiresAt = System.currentTimeMillis() + 60000;
    OAuthCallbackForwardTest.<ConcurrentHashMap<String, OAuthSession>>field(service, "sessions").put(session.state, session);
    Method start = OAuthService.class.getDeclaredMethod("startCallbackServer", String.class);
    start.setAccessible(true); start.invoke(service, session.spec.redirectUri);
    String url = session.spec.redirectUri + "?state=test-state&code=hello%2Bworld";
    try {
      assertEquals("ok", send(service, url).getStr("status"));
      assertEquals("hello+world", service.received.code);
      assertEquals("ok", send(service, url.replace("127.0.0.1", "localhost")).getStr("status"));
      assertEquals("hello+world", service.received.code);
      for (String invalid : new String[] {
          url.replace("127.0.0.1", "example.com"), url.replace("http:", "https:"),
          url.replace("/auth/callback", "/private"), url.replace(":" + port, ":1"),
          url.replace("test-state", "unknown"), url + "#fragment", url.replace("127.0.0.1", "user@127.0.0.1") }) {
        service.received = null;
        try { send(service, invalid); fail("Expected rejection"); } catch (BusinessException expected) { }
        assertNull(service.received);
      }
      session.status = "ok";
      try { send(service, url); fail("Consumed session accepted"); } catch (BusinessException expected) { }
      session.status = "wait"; session.expiresAt = 1;
      try { send(service, url); fail("Expired session accepted"); } catch (BusinessException expected) { }
      session.expiresAt = System.currentTimeMillis() + 60000;
      session.spec.provider = "trae";
      session.pluginMetadata = Kv.create().set("login_trace_id", "trace-1");
      String trae = session.spec.redirectUri + "?login_trace_id=trace-1&token=a%2Bb";
      send(service, trae);
      assertEquals(trae, service.received.redirect_url);
    } finally {
      OAuthCallbackForwardTest.<ConcurrentHashMap<Integer, HttpServer>>field(service, "callbacks").values().forEach(server -> server.stop(0));
      OAuthCallbackForwardTest.<java.util.concurrent.ScheduledExecutorService>field(service, "cleaner").shutdownNow();
      OAuthCallbackForwardTest.<java.util.concurrent.ExecutorService>field(service, "callbackExecutor").close();
    }
  }

  @Test public void pluginReceivesCompleteUrlWithoutMakingHttpRequests() throws Exception {
    StubOAuth service = new StubOAuth();
    OAuthSession session = new OAuthSession();
    session.state = "plugin-state";
    session.pluginId = "test-plugin";
    session.expiresAt = System.currentTimeMillis() + 60000;
    OAuthCallbackForwardTest.<ConcurrentHashMap<String, OAuthSession>>field(service, "sessions").put(session.state, session);
    try (ServerSocket trap = new ServerSocket(0)) {
      trap.setSoTimeout(150);
      String url = "http://127.0.0.1:" + trap.getLocalPort() + "/plugin/callback?state=plugin-state&code=a%2Bb&extra=%2F";
      assertEquals("ok", send(service, url).getStr("status"));
      assertEquals(url, service.received.redirect_url);
      try { trap.accept().close(); fail("Plugin forwarding must not issue HTTP"); }
      catch (java.net.SocketTimeoutException expected) { }
      for (String invalid : new String[] {url.replace("127.0.0.1", "example.com"), url.replace("plugin-state", "unknown")}) {
        service.received = null;
        try { send(service, invalid); fail("Invalid plugin callback accepted"); } catch (BusinessException expected) { }
        assertNull(service.received);
      }
      session.status = "ok";
      service.received = null;
      try { send(service, url); fail("Consumed plugin session accepted"); } catch (BusinessException expected) { }
      assertNull(service.received);
      session.status = "wait";
      session.expiresAt = 1;
      try { send(service, url); fail("Expired plugin session accepted"); } catch (BusinessException expected) { }
      assertNull(service.received);
    } finally {
      OAuthCallbackForwardTest.<java.util.concurrent.ScheduledExecutorService>field(service, "cleaner").shutdownNow();
      OAuthCallbackForwardTest.<java.util.concurrent.ExecutorService>field(service, "callbackExecutor").close();
    }
  }
}
