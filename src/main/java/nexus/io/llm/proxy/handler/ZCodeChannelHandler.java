package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ZCodeChannelInput;
import nexus.io.llm.proxy.model.ZCodeChannelResult;
import nexus.io.llm.proxy.service.ZCodeChannelService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

/** POST /v8/management/zcode/channel; authentication is provided by the management interceptor. */
public class ZCodeChannelHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    ZCodeChannelInput input = request.getBodyObject(ZCodeChannelInput.class);
    ZCodeChannelResult result = Aop.get(ZCodeChannelService.class).change(input);
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(result));
  }
}
