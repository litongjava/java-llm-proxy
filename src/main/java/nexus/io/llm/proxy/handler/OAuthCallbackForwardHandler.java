package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.OAuthCallbackInput;
import nexus.io.llm.proxy.service.OAuthService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class OAuthCallbackForwardHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    Object data = Aop.get(OAuthService.class).forwardCallback(request.getBodyObject(OAuthCallbackInput.class));
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
