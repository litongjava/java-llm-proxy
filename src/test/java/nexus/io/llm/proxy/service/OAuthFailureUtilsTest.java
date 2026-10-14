package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.net.SocketTimeoutException;
import org.junit.Test;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.exception.OAuthRequestException;

public class OAuthFailureUtilsTest {
  @Test
  public void invalidGrantIncludesRecoveryWithoutUpstreamSecrets() {
    try {
      OAuthFailureUtils.requireSuccess(Kv.create().set("http_status", 400).set("error", "invalid_grant")
          .set("error_description", "secret-code-and-token"), "Token exchange");
      fail("Expected rejection");
    } catch (OAuthRequestException error) {
      assertTrue(error.getMessage().contains("invalid_grant"));
      assertTrue(error.getMessage().contains("Start a new sign-in"));
      assertFalse(error.getMessage().contains("secret-code-and-token"));
    }
  }

  @Test
  public void rejectsRedirectAndUnknownErrorWithoutEchoingPayload() {
    for (int status : new int[] {200, 302, 403}) {
      try {
        OAuthFailureUtils.requireSuccess(Kv.create().set("http_status", status).set("error", "private-token"), "Project setup");
        fail("Expected rejection");
      } catch (OAuthRequestException error) {
        assertFalse(error.getMessage().contains("private-token"));
        assertTrue(error.getMessage().contains("HTTP " + status));
      }
    }
  }

  @Test
  public void recognizesGooglePermissionError() {
    try {
      OAuthFailureUtils.requireSuccess(Kv.create().set("http_status", 403)
          .set("error", Kv.create().set("status", "PERMISSION_DENIED").set("message", "private-data")), "loadCodeAssist");
      fail("Expected rejection");
    } catch (OAuthRequestException error) {
      assertTrue(error.getMessage().contains("Access denied"));
      assertFalse(error.getMessage().contains("private-data"));
    }
  }

  @Test
  public void timeoutsAndUnexpectedExceptionsKeepStageAndHideMessage() {
    assertTrue(OAuthFailureUtils.message("Account setup", new SocketTimeoutException("private-url"))
        .contains("Account setup failed: Request timed out"));
    assertFalse(OAuthFailureUtils.message("Token exchange", new IllegalStateException("private-token"))
        .contains("private-token"));
  }
}
