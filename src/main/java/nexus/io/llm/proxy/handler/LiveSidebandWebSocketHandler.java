package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.interceptor.ProxyTokenInterceptor;
import nexus.io.llm.proxy.service.CodexLiveService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.model.exception.BusinessException;
import nexus.io.model.exception.ParameterValidationException;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.websocket.common.WebSocketRequest;
import nexus.io.tio.websocket.server.handler.IWebSocketHandler;

public class LiveSidebandWebSocketHandler implements IWebSocketHandler {
  private final ProxyTokenInterceptor token = new ProxyTokenInterceptor();

  public HttpResponse handshake(HttpRequest request, HttpResponse response, ChannelContext channel) throws Exception {
    HttpResponse rejected = token.doBeforeHandler(request, request.getRequestLine(), response);
    if (rejected != null) {
      return rejected;
    }
    try {
      return Aop.get(CodexLiveService.class).handshake(request, response, channel);
    } catch (ParameterValidationException error) {
      response.setStatus(400);
      return response.respond(RespBodyVo.fail(400, error.getMessage()));
    } catch (BusinessException error) {
      response.setStatus(error.getStatus());
      return response.respond(RespBodyVo.fail(error.getStatus(), error.getMessage()));
    } catch (Exception error) {
      response.setStatus(502);
      return response.respond(RespBodyVo.fail(502, "Live upstream connection failed"));
    }
  }
  public void onAfterHandshaked(HttpRequest request, HttpResponse response, ChannelContext channel) {
    Aop.get(CodexLiveService.class).opened(channel);
  }
  public Object onText(WebSocketRequest request, String text, ChannelContext channel) {
    Aop.get(CodexLiveService.class).send(channel, text, null);
    return null;
  }
  public Object onBytes(WebSocketRequest request, byte[] bytes, ChannelContext channel) {
    Aop.get(CodexLiveService.class).send(channel, null, bytes);
    return null;
  }
  public Object onClose(WebSocketRequest request, byte[] bytes, ChannelContext channel) {
    Aop.get(CodexLiveService.class).close(channel);
    return null;
  }
}
