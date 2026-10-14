package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;
import lombok.Data;

@Data
public class VertexImportInput {
  public String name;
  public String location;
  public Kv credential;
}
