package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import org.junit.Test;
import com.jfinal.kit.Kv;
import nexus.io.model.exception.BusinessException;

public class LobsterAiServiceTest {
  private final LobsterAiService service = new LobsterAiService();

  @Test
  public void refreshPreservesInstallationAndRotatesTokens() throws Exception {
    Kv original = Kv.create().set("uuid", "install").set("first_keyfrom", "10").set("latest_keyfrom", "20")
        .set("refresh_token", "old-refresh").set("uid", "account").set("user_id", "yid");
    Kv response = Kv.create().set("code", 0).set("data", Kv.create().set("accessToken", "new-access").set("expiresIn", 3600));
    Kv refreshed = service.normalizeToken(response, original, false);
    assertEquals("install", refreshed.getStr("uuid"));
    assertEquals("20", refreshed.getStr("latest_keyfrom"));
    assertEquals("old-refresh", refreshed.getStr("refresh_token"));
    assertEquals("account", refreshed.getStr("uid"));
    assertNotNull(refreshed.getStr("expired"));
  }

  @Test
  public void initialIdentityUsesUserIdAndRefreshIdentityUsesYid() throws Exception {
    Kv data = Kv.create().set("accessToken", "access").set("refreshToken", "refresh")
        .set("user", Kv.create().set("id", "uid").set("yid", "yid").set("nickname", "name"));
    Kv credential = service.normalizeToken(Kv.create().set("code", 0).set("data", data), Kv.create(), true);
    assertEquals("uid", credential.getStr("account_id"));
    assertEquals("yid", credential.getStr("user_id"));
    assertEquals("name", credential.getStr("label"));
  }

  @Test(expected = BusinessException.class)
  public void rejectsSuccessEnvelopeWithoutToken() throws Exception {
    service.normalizeToken(Kv.create().set("code", 0).set("data", Kv.create()), Kv.create(), true);
  }

  @Test
  public void forcesUpstreamStreamWithoutChangingClientInput() {
    Kv original = Kv.create().set("stream", false).set("tool_choice", "none").set("prompt_cache_key", "key");
    Kv payload = service.payload(original);
    assertEquals(Boolean.TRUE, payload.getBoolean("stream"));
    assertFalse(payload.containsKey("tool_choice"));
    assertFalse(payload.containsKey("prompt_cache_key"));
    assertEquals(Boolean.FALSE, original.getBoolean("stream"));
  }
}
