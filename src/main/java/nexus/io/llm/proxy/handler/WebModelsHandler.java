package nexus.io.llm.proxy.handler;

import com.jfinal.kit.Kv;
import java.util.ArrayList;
import java.util.List;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.ManagementService;
import nexus.io.llm.proxy.service.ProxyJson;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class WebModelsHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    Kv models = Aop.get(ManagementService.class).models();
    List<Kv> result = new ArrayList<>();
    for (Object value : ProxyJson.list(models.get("data"))) {
      Kv item = ProxyJson.object(value);
      String name = item.getStr("id");
      if (name == null || name.isBlank()) {
        continue;
      }
      Kv model = Kv.create().set("name", name);
      String display = ProxyJson.text(item, "display_name", ProxyJson.text(item, "displayName", ""));
      if (!display.isBlank()) {
        model.set("displayName", display);
      }
      result.add(model);
    }
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(result));
  }
}
