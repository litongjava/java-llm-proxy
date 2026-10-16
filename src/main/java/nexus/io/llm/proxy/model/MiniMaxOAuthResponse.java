package nexus.io.llm.proxy.model;

/** MiniMax device authorization and token endpoint response fields. */
public class MiniMaxOAuthResponse {
  public String device_code;
  public String user_code;
  public String verification_uri;
  public String verification_url;
  public String verification_uri_complete;
  public Long expires_in;
  public Integer interval;
  public String access_token;
  public String refresh_token;
  public String token_type;
  public String scope;
  public String status;
  public String error;
}
