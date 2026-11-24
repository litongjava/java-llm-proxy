package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;

import java.util.UUID;
import nexus.io.llm.proxy.model.Provider;
import okhttp3.Request;
import org.junit.Test;

public class OpenCodeSessionHeaderTest {
  private Request forward(String host, String dcl, String nativeSession) {
    Request.Builder builder = new Request.Builder().url("https://" + host + "/inference/go/openai/v1/chat/completions");
    if (dcl != null) { builder.header("X-Dcl-Session-Id", dcl); }
    if (nativeSession != null) { builder.header("X-OpenCode-Session", nativeSession); }
    new OAuthTransportService().httpHeaders(new Provider(), builder);
    return builder.build();
  }

  @Test
  public void renamesDclSessionAndPreservesNativeSession() {
    Request mapped = forward("opencode.ai", "session-a", null);
    assertEquals("session-a", mapped.header("x-opencode-session"));
    assertNull(mapped.header("x-dcl-session-id"));
    Request nativeRequest = forward("opencode.ai", "session-a", "native-session");
    assertEquals("native-session", nativeRequest.header("x-opencode-session"));
    assertNull(nativeRequest.header("x-dcl-session-id"));
  }

  @Test
  public void handlesMissingAndBlankSessionIds() {
    String first = forward("opencode.ai", null, null).header("x-opencode-session");
    assertNotNull(first);
    assertEquals(first, UUID.fromString(first).toString());
    assertNotEquals(first, forward("opencode.ai", null, null).header("x-opencode-session"));
    assertEquals("dcl", forward("opencode.ai", "dcl", " ").header("x-opencode-session"));
  }

  @Test
  public void leavesOtherUpstreamsUnchanged() {
    Request request = forward("opencode.ai.example.com", "session-a", null);
    assertEquals("session-a", request.header("x-dcl-session-id"));
    assertNull(request.header("x-opencode-session"));
  }
}
