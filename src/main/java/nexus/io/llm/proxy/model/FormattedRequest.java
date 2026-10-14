package nexus.io.llm.proxy.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FormattedRequest {
  private String protocol;
  private String prompt;
  private String response;
}
