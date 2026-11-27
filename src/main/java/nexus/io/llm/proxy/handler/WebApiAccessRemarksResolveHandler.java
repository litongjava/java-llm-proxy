package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ApiAccessRemarkResolveInput;
import nexus.io.llm.proxy.service.ApiAccessRemarkService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class WebApiAccessRemarksResolveHandler {
  public HttpResponse handle(HttpRequest request) {
    ApiAccessRemarkResolveInput input = request.getBodyObject(ApiAccessRemarkResolveInput.class);
    BusinessException.require(input != null, 400, "INVALID_REMARKS", "Remark queries are required");
    Object data = Aop.get(ApiAccessRemarkService.class).resolve(input.getQueries());
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
