package nexus.io.llm.proxy.service;

import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.exception.BusinessException;

public class PublicModelService {
  public Kv geminiList() throws Exception {
    List<Kv> models = ProxyJson.list(Aop.get(ManagementService.class).models().get("data")).stream()
        .map(ProxyJson::object).map(this::gemini).toList();
    return Kv.create().set("models", models);
  }

  public Kv detail(String id, boolean gemini) throws Exception {
    String normalized = id.startsWith("models/") ? id.substring(7) : id;
    for (Object value : ProxyJson.list(Aop.get(ManagementService.class).models().get("data"))) {
      Kv model = ProxyJson.object(value);
      if (normalized.equals(model.getStr("id"))) { return gemini ? gemini(model) : model; }
    }
    throw new BusinessException(404, "MODEL_NOT_FOUND", "Model not found");
  }

  private Kv gemini(Kv model) {
    String id = model.getStr("id");
    return Kv.create().set("name", "models/" + id).set("displayName", id)
        .set("supportedGenerationMethods", List.of("generateContent"));
  }
}
