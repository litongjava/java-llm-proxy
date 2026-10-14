package nexus.io.llm.proxy.handler;

import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.ModelCatalogService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class CredentialModelsHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    Object data = Aop.get(ModelCatalogService.class).credentialModels(request.getParam("name"));
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
