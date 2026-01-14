package com.litongjava.llm.proxy.handler;

import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.websocket.common.WebSocketRequest;
import com.litongjava.tio.websocket.common.WebSocketResponse;
import com.litongjava.tio.websocket.common.WebSocketSessionContext;
import com.litongjava.tio.websocket.server.handler.IWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeminiLiveWsHandler implements IWebSocketHandler {
  public static final String CHARSET = "utf-8";

  /**
   * 握手成功后执行，绑定群组并通知其他用户
   */
  @Override
  public HttpResponse handshake(HttpRequest httpRequest, HttpResponse response, ChannelContext channelContext) throws Exception {
    log.info("请求信息: {}", httpRequest);
    return response;
  }

  /**
   * 握手完成后执行
   */
  @Override
  public void onAfterHandshaked(HttpRequest httpRequest, HttpResponse httpResponse, ChannelContext channelContext) throws Exception {
    log.info("握手完成: {}", httpRequest);
  }

  /**
   * 处理连接关闭请求，进行资源清理
   */
  @Override
  public Object onClose(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    Tio.remove(channelContext, "客户端主动关闭连接");
    return null;
  }

  /**
   * 处理二进制消息
   */
  @Override
  public Object onBytes(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    log.info("收到二进制消息，大小: {} bytes", bytes.length);
    return null;
  }

  /**
   * 处理文本消息
   */
  @Override
  public Object onText(WebSocketRequest wsRequest, String text, ChannelContext channelContext) throws Exception {
    WebSocketSessionContext wsSessionContext = (WebSocketSessionContext) channelContext.get();
    String path = wsSessionContext.getHandshakeRequest().getRequestLine().path;
    log.info("路径：{}，收到消息：{}", path, text);

    String message = "{user_id:'" + channelContext.userId + "',message:'" + text + "'}";
    WebSocketResponse wsResponse = WebSocketResponse.fromText(message, CHARSET);
    // 发送消息
    Tio.send(channelContext, wsResponse);
    return null; // 不需要额外的返回值
  }
}

