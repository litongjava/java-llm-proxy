package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class ApiAccessRemark {
  private String providerSection;
  private String apiKeyHash;
  private String recordHash;
  private String remark;
}
