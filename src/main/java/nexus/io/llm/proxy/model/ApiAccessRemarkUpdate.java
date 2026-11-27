package nexus.io.llm.proxy.model;

import java.util.List;
import lombok.Data;

@Data
public class ApiAccessRemarkUpdate {
  private String providerSection;
  private List<ApiAccessRemarkLocator> previousRecords;
  private List<ApiAccessRemarkLocator> records;
  private List<ApiAccessRemarkLocator> allRecords;
  private String remark;
}
