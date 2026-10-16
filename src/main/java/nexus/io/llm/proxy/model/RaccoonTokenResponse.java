package nexus.io.llm.proxy.model;

/** Fixed fields returned by Raccoon's login and refresh endpoints. */
public class RaccoonTokenResponse {
  public String status;
  public String access_token;
  public String refresh_token;
  public String office_identity;
  public String expired_at;
}
