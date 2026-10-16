package nexus.io.llm.proxy.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;

/** Product catalog snapshots adapted from channel-pack (see docs/licenses). */
public class QoderModelService {
  public List<Kv> models(String provider) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/qoder-models.json")) {
      if (input == null) { throw new IllegalStateException("Qoder model snapshot is missing"); }
      List<Kv> models = new ArrayList<>();
      for (Object item : ProxyJson.list(ProxyJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)).get(provider))) {
        Kv model = ProxyJson.object(item).set("object", "model").set("owned_by", provider).set("catalog_source", "snapshot");
        model.set("context_length", model.get("contextWindow"));
        // Snapshot promotion prices must not be presented as current billing guarantees.
        model.remove("priceFactor"); model.remove("originalPriceFactor"); model.remove("promotion");
        models.add(model);
      }
      return models;
    }
  }

  public Kv model(String provider, String id) {
    try {
      for (Kv model : models(provider)) {
        if (id.equals(model.getStr("id"))) { return model; }
      }
    } catch (Exception error) { throw new IllegalStateException("Cannot read Qoder model snapshot", error); }
    throw new IllegalArgumentException("Unknown Qoder model key: " + id);
  }
}
