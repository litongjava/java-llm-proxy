package nexus.io.llm.proxy.model;

public class CodeArtsTokenResponse {
  public Credentials credentials;
  public String refresh_token;
  public String error;
  public String error_code;
  public static class Credentials {
    public String access_key_id;
    public String secret_access_key;
    public String security_token;
    public String expiration;
  }
}
