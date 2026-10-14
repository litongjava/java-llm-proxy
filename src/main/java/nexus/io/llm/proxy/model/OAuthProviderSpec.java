package nexus.io.llm.proxy.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OAuthProviderSpec {
  public String provider;
  public String clientId;
  public String clientSecret;
  public String authorizeUrl;
  public String tokenUrl;
  public String deviceUrl;
  public String scope;
  public String redirectUri;
  public boolean jsonToken;
}
