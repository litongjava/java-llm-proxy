package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.IdRequest;
import nexus.io.llm.proxy.service.SettingsService;
import nexus.io.llm.proxy.validation.ProxyParameterValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class ProviderDeleteHandler {
  public HttpResponse handle(HttpRequest request) {
    Aop.get(SettingsService.class).deleteProvider(ProxyParameterValidator.id(request.getBodyObject(IdRequest.class).id));
    return TioRequestContext.getResponse().respond(RespBodyVo.ok());
  }
}
