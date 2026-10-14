package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class OAuthCallbackInput {
  public String provider;
  public String redirect_url;
  public String state;
  public String code;
  public String error;
}
