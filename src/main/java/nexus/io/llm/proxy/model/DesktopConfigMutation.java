package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class DesktopConfigMutation {
  private String operation;
  private Object value;
}
