package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.ManagementService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class RequestLogGetHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    Object data = Aop.get(ManagementService.class).detail(request.getParam("id"));
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
