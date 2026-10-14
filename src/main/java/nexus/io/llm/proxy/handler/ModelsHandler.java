package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.ManagementService;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class ModelsHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    return TioRequestContext.getResponse().setJson(Aop.get(ManagementService.class).models());
  }
}
