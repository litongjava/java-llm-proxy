package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.PublicModelService;
import nexus.io.llm.proxy.service.ToolScopeService;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class GeminiModelDetailHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    String path = request.getRequestURI();
    return TioRequestContext.getResponse().setJson(Aop.get(PublicModelService.class).detail(path.substring(path.indexOf("/models/") + 8), true, Aop.get(ToolScopeService.class).tool(request)));
  }
}
