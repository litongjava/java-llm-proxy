package nexus.io.llm.proxy.model;

public class LobsterAiTokenResponse {
  public Integer code;
  public Token data;
  public static class Token {
    public String accessToken;
    public String refreshToken;
    public Long expiresIn;
    public User user;
  }
  public static class User {
    public String id;
    public String userId;
    public String yid;
    public String nickname;
  }
}
