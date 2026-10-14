package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ManagementApiResponse {
  private int status_code;
  private Kv header;
  private String body;
}
