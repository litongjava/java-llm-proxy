package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.DesktopConfigMutation;
import nexus.io.llm.proxy.service.DesktopConfigService;
import nexus.io.llm.proxy.validation.ProxyParameterValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class DesktopConfigSaveHandler {
  public HttpResponse handle(HttpRequest request) {
    DesktopConfigMutation input = request.getBodyObject(DesktopConfigMutation.class);
    String node = request.getRequestURI().substring("/v8/management/config".length()).replaceFirst("^/", "");
    ProxyParameterValidator.configMutation(node, input);
    Aop.get(DesktopConfigService.class).mutate(node, input.getOperation(), input.getValue());
    return TioRequestContext.getResponse().respond(RespBodyVo.ok());
  }
}
