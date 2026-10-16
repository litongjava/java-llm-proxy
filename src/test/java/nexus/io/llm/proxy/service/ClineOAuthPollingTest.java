package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.OAuthSession;
import org.junit.Test;

public class ClineOAuthPollingTest {
  private static class StubOAuth extends OAuthService {
    Kv response;
    int calls;
    @Override
    public Kv post(String url, Kv parameters, boolean json, String bearer) {
      calls++;
      assertEquals("urn:ietf:params:oauth:grant-type:device_code", parameters.getStr("grant_type"));
      assertFalse(parameters.containsKey("code_verifier"));
      return response;
    }
  }

  @SuppressWarnings("unchecked")
  private OAuthSession attach(StubOAuth oauth) throws Exception {
    OAuthSession session = new OAuthSession();
    session.spec = new OAuthProviderService().get("cline");
    session.state = "test-state";
    session.deviceCode = "test-device";
    session.expiresAt = System.currentTimeMillis() + 60000;
    Field field = OAuthService.class.getDeclaredField("sessions");
    field.setAccessible(true);
    ((ConcurrentHashMap<String, OAuthSession>) field.get(oauth)).put(session.state, session);
    return session;
  }

  @Test
  public void pendingAndSlowDownRespectPollIntervalsAndCancelStopsPolling() throws Exception {
    StubOAuth oauth = new StubOAuth();
    OAuthSession session = attach(oauth);
    oauth.response = Kv.create().set("error", "authorization_pending").set("http_status", 400);
    assertEquals("wait", oauth.status(session.state).getStr("status"));
    assertEquals(1, oauth.calls);
    oauth.status(session.state);
    assertEquals(1, oauth.calls);
    session.nextPoll = 0;
    oauth.response = Kv.create().set("error", "slow_down").set("http_status", 400);
    oauth.status(session.state);
    assertEquals(10, session.interval);
    assertTrue(session.nextPoll > System.currentTimeMillis() + 9000);
    oauth.cancel(session.state);
    session.nextPoll = 0;
    assertEquals("error", oauth.status(session.state).getStr("status"));
    assertEquals("cancelled", session.error);
    assertEquals(2, oauth.calls);
    assertNull(session.deviceCode);
  }

  @Test
  public void denialAndMissingTokensAreTerminalWithoutWritingCredentials() throws Exception {
    for (Kv response : new Kv[] { Kv.create().set("http_status", 200),
        Kv.create().set("http_status", 400).set("error", "access_denied"),
        Kv.create().set("http_status", 400).set("error", "expired_token") }) {
      StubOAuth oauth = new StubOAuth();
      OAuthSession session = attach(oauth);
      oauth.response = response;
      assertEquals("error", oauth.status(session.state).getStr("status"));
      assertNull(session.deviceCode);
      assertNull(session.credentialName);
      oauth.status(session.state);
      assertEquals(1, oauth.calls);
    }
  }
}
