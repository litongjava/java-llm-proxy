package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.ApiKeyUsageService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class ApiKeyUsageHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    Object data = Aop.get(ApiKeyUsageService.class).usage();
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
