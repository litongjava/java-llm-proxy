package nexus.io.llm.proxy.model;

/** Fixed fields returned by Cline registration and token rotation. */
public class ClineTokenResponse {
  public Boolean success;
  public Token data;
  public String accessToken;
  public String refreshToken;
  public String expiresAt;
  public String tokenType;
  public UserInfo userInfo;

  public static class Token {
    public String accessToken;
    public String refreshToken;
    public String expiresAt;
    public String tokenType;
    public UserInfo userInfo;
  }

  public static class UserInfo {
    public String clineUserId;
    public String email;
  }
}
