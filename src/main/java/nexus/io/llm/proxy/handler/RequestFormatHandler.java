package nexus.io.llm.proxy.handler;

import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.*;
import nexus.io.llm.proxy.service.*;
import nexus.io.llm.proxy.validation.ProxyParameterValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class RequestFormatHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    Object data = Aop.get(ProtocolFormatService.class).format(Aop.get(ManagementService.class).detail(ProxyParameterValidator.id(request.getParam("id"))));
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}

