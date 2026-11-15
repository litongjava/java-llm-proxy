package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;

import java.util.UUID;
import nexus.io.llm.proxy.model.Provider;
import okhttp3.Request;
import org.junit.Test;

public class CodexHeaderDefaultsTest {
  private final OAuthTransportService transport = new OAuthTransportService();

  private Provider provider(String upstreamType, String authType) {
    Provider provider = new Provider();
    provider.upstreamType = upstreamType;
    provider.authType = authType;
    return provider;
  }

  private Request.Builder request() {
    return new Request.Builder().url("http://localhost/responses");
  }

  private Request build(Provider provider, Request.Builder builder) {
    transport.httpHeaders(provider, builder);
    return builder.build();
  }

  @Test
  public void addsMissingHeaders() {
    Request request = build(provider("codex", "oauth"), request());
    String session = request.header("session-id");
    assertNotNull(session);
    assertEquals(session, request.header("thread-id"));
    assertEquals(session, UUID.fromString(session).toString());
    String clientRequest = request.header("x-client-request-id");
    assertNotNull(clientRequest);
    assertEquals(clientRequest, UUID.fromString(clientRequest).toString());
    assertNotEquals(session, clientRequest);
    assertEquals("remote_compaction_v2", request.header("x-codex-beta-features"));
    assertNotNull(request.header("user-agent"));
  }

  @Test
  public void generatesUserAgentForCurrentPlatform() {
    String userAgent = build(provider("codex", "oauth"), request()).header("user-agent");
    String osName = System.getProperty("os.name", "");
    assertTrue(userAgent.startsWith("codex-tui/0.166.0 ("));
    if (osName.startsWith("Windows")) {
      assertTrue(userAgent.contains("(Windows "));
    } else if (osName.startsWith("Mac")) {
      assertTrue(userAgent.contains("(Mac OS "));
    } else {
      assertTrue(userAgent.contains("(Linux "));
    }
    assertTrue(userAgent.contains("; x86_64)") || userAgent.contains("; arm64)"));
    assertTrue(userAgent.endsWith("xterm-256color (codex-tui; 0.167.0)"));
  }

  @Test
  public void preservesClientValues() {
    Request request = build(provider("codex", "oauth"), request()
        .header("user-agent", "custom-agent")
        .header("session-id", "client-session")
        .header("thread-id", "client-thread")
        .header("x-client-request-id", "client-request")
        .header("x-codex-beta-features", "client-features"));
    assertEquals("client-session", request.header("session-id"));
    assertEquals("client-thread", request.header("thread-id"));
    assertEquals("client-request", request.header("x-client-request-id"));
    assertEquals("client-features", request.header("x-codex-beta-features"));
    assertNotEquals("custom-agent", request.header("user-agent"));
    assertTrue(request.header("user-agent").startsWith("codex-tui/0.166.0 ("));
  }

  @Test
  public void reusesThreadIdForSessionId() {
    Request request = build(provider("codex", "oauth"), request().header("thread-id", "client-thread"));
    assertEquals("client-thread", request.header("session-id"));
    assertEquals("client-thread", request.header("thread-id"));
    assertNotEquals("client-thread", request.header("x-client-request-id"));
  }

  @Test
  public void ignoresNonCodexOAuthChannels() {
    for (Provider provider : new Provider[] { provider("codex", "api-key"), provider("openai", "oauth") }) {
      Request request = build(provider, request());
      assertNull(request.header("session-id"));
      assertNull(request.header("thread-id"));
      assertNull(request.header("x-client-request-id"));
      assertNull(request.header("x-codex-beta-features"));
      assertNull(request.header("user-agent"));
    }
  }

  @Test
  public void removesDclClientHeaders() {
    Request request = build(provider("codex", "oauth"), request()
        .header("x-dcl-arch", "x86_64")
        .header("x-dcl-os", "windows")
        .header("x-dcl-retry-count", "0")
        .header("x-dcl-runtime", "rust"));
    assertNull(request.header("x-dcl-arch"));
    assertNull(request.header("x-dcl-os"));
    assertNull(request.header("x-dcl-retry-count"));
    assertNull(request.header("x-dcl-runtime"));
  }

  @Test
  public void websocketHeadersDoNotAddDefaults() {
    Request.Builder builder = request();
    transport.headers(provider("codex", "oauth"), builder);
    Request request = builder.build();
    assertNull(request.header("session-id"));
    assertNull(request.header("thread-id"));
    assertNull(request.header("x-client-request-id"));
    assertNull(request.header("x-codex-beta-features"));
    assertNull(request.header("user-agent"));
    assertEquals("codex_cli_rs", request.header("originator"));
  }
}
