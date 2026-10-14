package nexus.io.llm.proxy.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;

@Data
public class Provider {
  public String id;
  public String name;
  public String protocol = "openai";
  public String baseUrl;
  public String apiKey;
  public boolean enabled = true;
  public List<String> models = new ArrayList<>();
  public Kv headers = Kv.create();
  public double inputPrice;
  public double outputPrice;
  public String credentialName;
  public String authType = "api-key";
  public String upstreamType;
  public Kv metadata = Kv.create();
}
