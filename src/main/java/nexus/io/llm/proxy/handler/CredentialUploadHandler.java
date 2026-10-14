package nexus.io.llm.proxy.handler;

import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.CredentialService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class CredentialUploadHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    String name = Aop.get(CredentialService.class).importCredential(request.getParam("name"), request.getBodyObject(Kv.class));
    Object data = Kv.create().set("status", "ok").set("name", name);
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(data));
  }
}
