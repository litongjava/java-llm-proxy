package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.UsageAcknowledgement;
import nexus.io.llm.proxy.service.StorageService;
import nexus.io.llm.proxy.validation.ProxyParameterValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class UsageAcknowledgeHandler {
  public HttpResponse handle(HttpRequest request) {
    UsageAcknowledgement input = request.getBodyObject(UsageAcknowledgement.class);
    ProxyParameterValidator.acknowledgement(input);
    Aop.get(StorageService.class).acknowledgeUsage(input.getIds());
    return TioRequestContext.getResponse().respond(RespBodyVo.ok());
  }
}
