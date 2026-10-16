package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.CodexLiveService;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.websocket.common.WebSocketRequest;
import nexus.io.tio.websocket.server.handler.IWebSocketHandler;

/** Existing call sidebands and standalone Realtime sessions use distinct upstreams. */
public class RealtimeWebSocketHandler implements IWebSocketHandler {
  private final LiveSidebandWebSocketHandler live = new LiveSidebandWebSocketHandler();
  private final ResponsesWebSocketHandler direct = new ResponsesWebSocketHandler();

  public HttpResponse handshake(HttpRequest request, HttpResponse response, ChannelContext channel) throws Exception {
    if (request.getParam("call_id") != null) {
      return live.handshake(request, response, channel);
    }
    return direct.handshake(request, response, channel);
  }
  public void onAfterHandshaked(HttpRequest request, HttpResponse response, ChannelContext channel) {
    if (request.getParam("call_id") != null) {
      live.onAfterHandshaked(request, response, channel);
    } else {
      direct.onAfterHandshaked(request, response, channel);
    }
  }
  public Object onText(WebSocketRequest request, String text, ChannelContext channel) throws Exception {
    return Aop.get(CodexLiveService.class).owns(channel) ? live.onText(request, text, channel) : direct.onText(request, text, channel);
  }
  public Object onBytes(WebSocketRequest request, byte[] bytes, ChannelContext channel) {
    return Aop.get(CodexLiveService.class).owns(channel) ? live.onBytes(request, bytes, channel) : direct.onBytes(request, bytes, channel);
  }
  public Object onClose(WebSocketRequest request, byte[] bytes, ChannelContext channel) {
    live.onClose(request, bytes, channel);
    return direct.onClose(request, bytes, channel);
  }
}
