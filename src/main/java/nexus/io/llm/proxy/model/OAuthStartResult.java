package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class OAuthStartResult {
  public String url;
  public String state;
  public String user_code;
  public String flow;
  public int expires_in;
}
