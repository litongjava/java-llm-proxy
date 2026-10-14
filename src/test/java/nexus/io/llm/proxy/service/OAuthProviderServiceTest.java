package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import nexus.io.llm.proxy.model.OAuthProviderSpec;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;

public class OAuthProviderServiceTest {
  private static final String PREFIX = "proxy.oauth.antigravity.";
  private final OAuthProviderService service = new OAuthProviderService();
  private String previousId;
  private String previousSecret;

  @Before
  public void configure() {
    previousId = EnvUtils.get(PREFIX + "client-id");
    previousSecret = EnvUtils.get(PREFIX + "client-secret");
    EnvUtils.set(PREFIX + "client-id", "test-client");
    EnvUtils.set(PREFIX + "client-secret", "test-secret");
  }

  @After
  public void restore() {
    EnvUtils.set(PREFIX + "client-id", previousId);
    EnvUtils.set(PREFIX + "client-secret", previousSecret);
  }

  @Test
  public void usesConfiguredCredentialsAndKeepsOAuthEndpoints() throws Exception {
    OAuthProviderSpec spec = service.get("antigravity");
    assertEquals("test-client", spec.clientId);
    assertEquals("test-secret", spec.clientSecret);
    assertEquals("https://accounts.google.com/o/oauth2/v2/auth", spec.authorizeUrl);
    assertEquals("https://oauth2.googleapis.com/token", spec.tokenUrl);
    assertEquals("http://localhost:51121/oauth-callback", spec.redirectUri);
  }

  @Test
  public void rejectsMissingIdWithoutLeakingCredentials() throws Exception {
    EnvUtils.set(PREFIX + "client-id", "");
    assertMissingCredentials();
  }

  @Test
  public void rejectsBlankSecretWithoutLeakingCredentials() throws Exception {
    EnvUtils.set(PREFIX + "client-secret", " ");
    assertMissingCredentials();
  }

  @Test
  public void otherProvidersDoNotRequireGoogleCredentials() throws Exception {
    EnvUtils.set(PREFIX + "client-id", "");
    EnvUtils.set(PREFIX + "client-secret", "");
    for (String provider : new String[] {"codex", "claude", "kimi", "kimi-ai", "meta", "devin"}) {
      assertNotNull(service.get(provider));
    }
  }

  private void assertMissingCredentials() throws Exception {
    try {
      service.get("antigravity");
      fail("Expected missing credentials to be rejected");
    } catch (BusinessException error) {
      assertEquals(500, error.getStatus());
      assertEquals("OAUTH_NOT_CONFIGURED", error.getCode());
      assertFalse(error.getMessage().contains("test-client"));
      assertFalse(error.getMessage().contains("test-secret"));
    }
  }
}
