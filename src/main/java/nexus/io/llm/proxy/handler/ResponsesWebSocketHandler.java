package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.interceptor.ProxyTokenInterceptor;
import nexus.io.llm.proxy.service.ProxyWebSocketService;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.websocket.common.WebSocketRequest;
import nexus.io.tio.websocket.server.handler.IWebSocketHandler;

public class ResponsesWebSocketHandler implements IWebSocketHandler {
  private final ProxyTokenInterceptor token = new ProxyTokenInterceptor();

  public HttpResponse handshake(HttpRequest request, HttpResponse response, ChannelContext channel) {
    HttpResponse rejected = token.doBeforeHandler(request, request.getRequestLine(), response);
    return rejected == null ? response : rejected;
  }

  public void onAfterHandshaked(HttpRequest request, HttpResponse response, ChannelContext channel) {
    Aop.get(ProxyWebSocketService.class).opened(request, channel);
  }

  public Object onText(WebSocketRequest request, String text, ChannelContext channel) throws Exception {
    Aop.get(ProxyWebSocketService.class).text(channel, text);
    return null;
  }

  public Object onBytes(WebSocketRequest request, byte[] bytes, ChannelContext channel) {
    Aop.get(ProxyWebSocketService.class).bytes(channel, bytes);
    return null;
  }

  public Object onClose(WebSocketRequest request, byte[] bytes, ChannelContext channel) {
    Aop.get(ProxyWebSocketService.class).close(channel);
    return null;
  }
}
