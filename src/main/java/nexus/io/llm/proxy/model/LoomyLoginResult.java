package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class LoomyLoginResult {
  public String status = "ok";
  public String name;
}
