package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.CodexLiveService;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class RealtimeCallsCreateHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    return Aop.get(CodexLiveService.class).create(request);
  }
}
