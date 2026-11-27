package nexus.io.llm.proxy.model;

import java.util.List;
import lombok.Data;

@Data
public class ApiAccessRemarkResolveInput {
  private List<ApiAccessRemarkQuery> queries;
}
