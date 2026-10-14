package nexus.io.llm.proxy.model;

import java.util.List;
import lombok.Data;

@Data
public class UsageAcknowledgement {
  private List<String> ids;
}
