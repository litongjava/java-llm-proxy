package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import javax.net.ssl.SSLException;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import nexus.io.model.exception.BusinessException;

public final class OAuthFailureUtils {
  private OAuthFailureUtils() {
  }

  public static void requireSuccess(Kv response, String operation) {
    long status = ProxyJson.number(response, "http_status", 200);
    if (status >= 200 && status < 300 && response.get("error") == null) {
      return;
    }
    Object error = response.get("error");
    String code = error instanceof String text ? text : ProxyJson.object(error).getStr("status");
    String reason = switch (code == null ? "" : code) {
    case "invalid_grant" -> "invalid_grant: Start a new sign-in; the authorization code is invalid, expired or already used.";
    case "invalid_client", "unauthorized_client" -> "invalid_client: Check the configured OAuth client ID and secret.";
    case "redirect_uri_mismatch" -> "redirect_uri_mismatch: Check the OAuth callback address.";
    case "access_denied", "PERMISSION_DENIED" -> "Access denied: Check account access and Antigravity eligibility.";
    case "invalid_scope" -> "invalid_scope: Check the configured OAuth scopes.";
    case "UNAUTHENTICATED" -> "Authentication rejected: Start a new sign-in.";
    default -> "Request rejected. Check the account access and outbound proxy.";
    };
    // Never echo upstream descriptions, bodies or unknown error values.
    throw new OAuthRequestException(operation + " failed (HTTP " + status + "). " + reason);
  }

  public static String message(String stage, Exception error) {
    if (error instanceof OAuthRequestException) {
      return error.getMessage();
    }
    if (error instanceof SSLException) {
      return stage + " failed: TLS connection failed. Check the outbound proxy and certificate trust.";
    }
    if (error instanceof InterruptedIOException) {
      return stage + " failed: Request timed out. Check the outbound proxy and retry sign-in.";
    }
    if (error instanceof IOException) {
      return stage + " failed: Network or local I/O error. Check the outbound proxy and kernel file permissions.";
    }
    if (error instanceof BusinessException business) {
      if ("PROJECT_NOT_FOUND".equals(business.getCode())) {
        return "Antigravity project setup failed: No active project was returned. Check account access and complete Antigravity onboarding.";
      }
      if ("NO_ACCESS_TOKEN".equals(business.getCode())) {
        return "Token exchange failed: The OAuth server returned no access token.";
      }
    }
    return stage + " failed: Unexpected response or local processing error. Check the kernel log.";
  }
}
