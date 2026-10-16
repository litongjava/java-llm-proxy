package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.NativeQuotaService;
import nexus.io.llm.proxy.validation.PluginParameterValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class CredentialQuotaHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(
        Aop.get(NativeQuotaService.class).fetch(PluginParameterValidator.authIndex(request))));
  }
}
