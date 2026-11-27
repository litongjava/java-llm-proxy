package nexus.io.llm.proxy.model;

import java.util.List;
import lombok.Data;

@Data
public class ApiAccessRemarkLocator {
  private String providerName;
  private String baseUrl;
  private List<String> apiKeys;
  private String configIdentity;
}
