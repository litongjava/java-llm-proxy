package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.LoomySmsInput;
import nexus.io.llm.proxy.service.LoomyService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

/** POST /v8/management/oauth/loomy/login */
public class LoomyLoginHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    LoomyService service = Aop.get(LoomyService.class);
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(service.loginAndSave(request.getBodyObject(LoomySmsInput.class))));
  }
}
