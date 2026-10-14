package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class UpstreamTask {
  public String id;
  public String model;
  public String providerId;
  public String protocol;
  public String owner;
}
