package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.ProxyService;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class ResponsesCompactHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    return Aop.get(ProxyService.class).forward(request, "responses", "/responses/compact");
  }
}

