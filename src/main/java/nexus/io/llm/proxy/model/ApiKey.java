package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class ApiKey {
  public String id;
  public String name;
  public String key;
  public boolean enabled = true;
}
