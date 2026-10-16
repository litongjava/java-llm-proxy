package nexus.io.llm.proxy.model;

/** Device polling and refresh use different access-token field names. */
public class QoderTokenResponse {
  public String token;
  public String device_token;
  public String access_token;
  public String refresh_token;
  public String refreshToken;
  public String user_id;
  public String userId;
  public String user_name;
  public String userName;
}
