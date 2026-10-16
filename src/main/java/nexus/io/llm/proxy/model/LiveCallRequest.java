package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;
import lombok.Data;

@Data
public class LiveCallRequest {
  public String sdp;
  public String model;
  public Kv session;
  public Kv extensions = Kv.create();
}
