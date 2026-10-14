package nexus.io.llm.proxy.exception;

import nexus.io.model.exception.BusinessException;

/** Contains only locally generated, credential-safe OAuth diagnostics. */
public class OAuthRequestException extends BusinessException {
  private static final long serialVersionUID = 1L;

  public OAuthRequestException(String message) {
    super(502, "OAUTH_REQUEST_FAILED", message);
  }
}
