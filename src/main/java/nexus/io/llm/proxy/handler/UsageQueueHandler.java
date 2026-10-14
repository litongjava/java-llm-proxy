package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.DesktopUsageService;
import nexus.io.llm.proxy.validation.ProxyParameterValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class UsageQueueHandler {
  public HttpResponse handle(HttpRequest request) {
    DesktopUsageService service = Aop.get(DesktopUsageService.class);
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(service.queue(ProxyParameterValidator.queueCount(request))));
  }
}
