package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.WebUsageQuery;
import nexus.io.llm.proxy.service.StorageService;
import nexus.io.llm.proxy.validation.ProxyParameterValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class WebUsageEventsHandler {
  public HttpResponse handle(HttpRequest request) {
    WebUsageQuery query = ProxyParameterValidator.webUsageQuery(request);
    Object data = Aop.get(StorageService.class).webUsageEvents(query);
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
