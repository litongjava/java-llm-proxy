package nexus.io.llm.proxy.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;

@Data
public class ProxySettings {
  public List<Provider> providers = new ArrayList<>();
  public List<ApiKey> apiKeys = new ArrayList<>();
  public List<ModelAlias> aliases = new ArrayList<>();
  public String routing = "round-robin";
  public boolean captureBodies = true;
  public int retentionDays = 30;
  public Kv payloadDefaults = Kv.create();
  public Kv payloadOverrides = Kv.create();
  public List<String> payloadRemove = new ArrayList<>();
  public List<String> sensitiveWords = new ArrayList<>();
}
