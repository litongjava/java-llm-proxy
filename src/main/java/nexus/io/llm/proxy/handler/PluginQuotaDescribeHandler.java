package nexus.io.llm.proxy.handler;

import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.PluginService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class PluginQuotaDescribeHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    String index = request.getParam("auth_index", request.getParam("authIndex"));
    Object data = Aop.get(PluginService.class).quota(request.getParam("id"), index, index == null ? "GET" : "POST");
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
