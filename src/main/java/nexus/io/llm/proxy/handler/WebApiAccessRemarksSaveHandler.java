package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ApiAccessRemarkUpdate;
import nexus.io.llm.proxy.service.ApiAccessRemarkService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class WebApiAccessRemarksSaveHandler {
  public HttpResponse handle(HttpRequest request) {
    Aop.get(ApiAccessRemarkService.class).save(request.getBodyObject(ApiAccessRemarkUpdate.class));
    return TioRequestContext.getResponse().respond(RespBodyVo.ok());
  }
}
