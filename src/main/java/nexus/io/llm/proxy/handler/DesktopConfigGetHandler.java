package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.DesktopConfigService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class DesktopConfigGetHandler {
  public HttpResponse handle(HttpRequest request) {
    String node = request.getRequestURI().substring("/v8/management/config".length()).replaceFirst("^/", "");
    Object data = Aop.get(DesktopConfigService.class).get(node);
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
