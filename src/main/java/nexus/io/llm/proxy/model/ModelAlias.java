package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class ModelAlias {
  public String name;
  public String model;
  public String providerId;
  public String reasoningEffort;
}
