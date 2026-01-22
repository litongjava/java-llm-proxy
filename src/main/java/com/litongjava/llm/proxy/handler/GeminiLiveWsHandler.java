package com.litongjava.llm.proxy.handler;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.litongjava.llm.proxy.mode.GoogleWsConnectParam;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.websocket.common.WebSocketRequest;
import com.litongjava.tio.websocket.common.WebSocketSessionContext;
import com.litongjava.tio.websocket.server.handler.IWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeminiLiveWsHandler implements IWebSocketHandler {

  /** 下游转发目标（构造函数传入） */
  private final String forwardWsUri;

  /**
   * 上游连接 -> 下游连接 的映射 key 用 channelKey（尽量稳定唯一）
   */
  private final Map<String, Downstream> downstreamMap = new ConcurrentHashMap<>();

  public GeminiLiveWsHandler(String forwardWsUri) {
    this.forwardWsUri = Objects.requireNonNull(forwardWsUri, "forwardWsUri must not be null");
  }

  /**
   * 握手成功后执行
   */
  @Override
  public HttpResponse handshake(HttpRequest httpRequest, HttpResponse response, ChannelContext channelContext)
      throws Exception {
    log.info("请求信息: {}", httpRequest);
    return response;
  }

  /**
   * 握手完成后执行：建立到下游的 websocket 连接
   */
  @Override
  public void onAfterHandshaked(HttpRequest httpRequest, HttpResponse httpResponse, ChannelContext channelContext)
      throws Exception {
    String key = channelKey(channelContext);

    String apiKey = httpRequest.getHeader("x-goog-api-key");
    String apiClient = httpRequest.getHeader("x-goog-api-client");
    String userAgent = httpRequest.getHeader("user-agent");
    GoogleWsConnectParam googleWsConnectParam = new GoogleWsConnectParam(apiKey, apiClient, userAgent);

    // 避免重复创建
    downstreamMap.computeIfAbsent(key, k -> {
      Downstream ds = new Downstream(forwardWsUri, googleWsConnectParam, channelContext);
      try {
        ds.connect(60, TimeUnit.SECONDS);
        log.info("上游{}握手完成，已连接下游: {}", k, forwardWsUri);
      } catch (Exception e) {
        log.error("上游{}握手完成，但连接下游失败: {}", k, forwardWsUri, e);
        // 下游连不上时，你可以选择直接踢掉上游
        Tio.remove(channelContext, "无法连接下游转发端: " + e.getMessage());
      }
      return ds;
    });

    WebSocketSessionContext wsSessionContext = (WebSocketSessionContext) channelContext.get();
    String path = wsSessionContext != null && wsSessionContext.getHandshakeRequest() != null
        ? wsSessionContext.getHandshakeRequest().getRequestLine().path
        : "";
    log.info("握手完成: path={}, forward={}", path, forwardWsUri);
  }

  /**
   * 处理连接关闭请求：关闭下游并清理
   */
  @Override
  public Object onClose(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    String key = channelKey(channelContext);
    Downstream ds = downstreamMap.remove(key);
    if (ds != null) {
      ds.close();
    }
    Tio.remove(channelContext, "客户端主动关闭连接");
    log.info("上游连接关闭: {}", key);
    return null;
  }

  /**
   * 处理二进制消息：原样转发到下游
   */
  @Override
  public Object onBytes(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    String key = channelKey(channelContext);
    Downstream ds = downstreamMap.get(key);

    if (ds == null || !ds.isOpen()) {
      log.warn("收到二进制消息但下游未就绪，上游={}", key);
      return null;
    }

    // 转发到下游
    ds.sendBytes(bytes);
    log.debug("转发二进制消息到下游: upstream={}, size={}", key, bytes.length);
    return null;
  }

  /**
   * 处理文本消息：原样转发到下游
   */
  @Override
  public Object onText(WebSocketRequest wsRequest, String text, ChannelContext channelContext) throws Exception {
    String key = channelKey(channelContext);
    Downstream ds = downstreamMap.get(key);

    WebSocketSessionContext wsSessionContext = (WebSocketSessionContext) channelContext.get();
    String path = wsSessionContext != null && wsSessionContext.getHandshakeRequest() != null
        ? wsSessionContext.getHandshakeRequest().getRequestLine().path
        : "";

    log.info("上游收到文本: upstream={}, path={}, text={}", key, path, text);

    if (ds == null || !ds.isOpen()) {
      log.warn("下游未就绪，无法转发文本，上游={}", key);
      // 你也可以选择给上游回一条错误提示
      // Tio.send(channelContext, WebSocketResponse.fromText("{\"error\":\"downstream
      // not ready\"}", CHARSET));
      return null;
    }

    // 原样转发到下游
    ds.sendText(text);
    return null;
  }

  /**
   * 生成上游连接 key 如果你的 ChannelContext 有 getId()，建议用 getId()； 这里用更兼容的方式：clientNode +
   * hash
   */
  private String channelKey(ChannelContext ctx) {
    String client = ctx.getClientNode() != null ? ctx.getClientNode().toString() : "unknown";
    return client + "#" + System.identityHashCode(ctx);
  }
}
