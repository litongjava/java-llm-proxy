package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.DesktopConfigService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class ConfigYamlSaveHandler {
  public HttpResponse handle(HttpRequest request) {
    Aop.get(DesktopConfigService.class).replaceYaml(request.getBodyString());
    return TioRequestContext.getResponse().respond(RespBodyVo.ok());
  }
}
