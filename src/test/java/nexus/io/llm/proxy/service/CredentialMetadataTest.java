package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.CredentialInfo;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CredentialMetadataTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  private String token(Kv auth) {
    String claims = JsonUtils.toJson(Kv.create().set("exp", 1999999999)
        .set("https://api.openai.com/auth", auth));
    return "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
  }

  private CredentialInfo listed(Kv value) throws Exception {
    Path root = directory.getRoot().toPath();
    Files.writeString(root.resolve("codex-test.json"), JsonUtils.toJson(value));
    CredentialService service = new CredentialService() {
      @Override public Path directory() { return root; }
    };
    return service.list().getFirst();
  }

  @Test public void listsSubscriptionExpiryWithoutExposingTokensOrOtherClaims() throws Exception {
    String expiry = "2026-10-15T02:09:15+00:00";
    String idToken = token(Kv.create().set("chatgpt_subscription_active_until", expiry)
        .set("unrelated_private_claim", "private-claim-value"));
    CredentialInfo info = listed(Kv.create().set("type", "codex").set("id_token", idToken)
        .set("access_token", "private-access-token").set("refresh_token", "private-refresh-token"));
    assertEquals(expiry, info.metadata.getStr("chatgpt_subscription_active_until"));
    assertEquals("active", info.status);
    String response = JsonUtils.toJson(info);
    for (String secret : new String[] {idToken, "private-access-token", "private-refresh-token", "private-claim-value"}) {
      assertFalse(response.contains(secret));
    }
  }

  @Test public void prefersExplicitSubscriptionMetadata() throws Exception {
    Kv credential = Kv.create().set("type", "codex")
        .set("metadata", Kv.create().set("subscription_active_until", "2030-01-01T00:00:00Z"))
        .set("id_token", token(Kv.create().set("chatgpt_subscription_active_until", "2026-10-15T02:09:15+00:00")));
    assertEquals("2030-01-01T00:00:00Z", listed(credential).metadata.getStr("chatgpt_subscription_active_until"));
  }

  @Test public void doesNotConfuseTokenExpiryWithSubscriptionExpiry() throws Exception {
    CredentialInfo info = listed(Kv.create().set("type", "codex").set("id_token", token(Kv.create()))
        .set("expired", "2030-01-01T00:00:00Z").set("expires_at", "2030-01-01T00:00:00Z"));
    assertFalse(info.metadata.containsKey("chatgpt_subscription_active_until"));
    assertEquals("active", info.status);
  }

  @Test public void missingMalformedOrNullSubscriptionClaimsRemainOptional() throws Exception {
    for (String token : new String[] {"", "bad.jwt", "header.!.signature", "header.e30.signature",
        token(Kv.create().set("chatgpt_subscription_active_until", null))}) {
      CredentialInfo info = listed(Kv.create().set("type", "codex").set("id_token", token));
      assertFalse(info.metadata.containsKey("chatgpt_subscription_active_until"));
      assertFalse(info.unavailable);
      assertEquals("active", info.status);
    }
  }
}
