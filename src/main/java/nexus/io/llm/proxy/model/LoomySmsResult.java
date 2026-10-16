package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class LoomySmsResult {
  public String msgid;
  public int expires_in = 300;
}
