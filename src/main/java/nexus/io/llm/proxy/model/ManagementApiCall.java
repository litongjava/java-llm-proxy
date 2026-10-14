package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;
import lombok.Data;

@Data
public class ManagementApiCall {
  public String authIndex;
  public String auth_index;
  public String method;
  public String url;
  public String proxy_url;
  public Kv header = Kv.create();
  public String data;
}
