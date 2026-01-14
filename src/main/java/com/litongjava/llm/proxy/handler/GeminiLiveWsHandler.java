package com.litongjava.llm.proxy.handler;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.websocket.client.WebSocket;
import com.litongjava.tio.websocket.client.WebsocketClient;
import com.litongjava.tio.websocket.client.config.WebsocketClientConfig;
import com.litongjava.tio.websocket.client.event.CloseEvent;
import com.litongjava.tio.websocket.client.event.ErrorEvent;
import com.litongjava.tio.websocket.client.event.MessageEvent;
import com.litongjava.tio.websocket.client.event.OpenEvent;
import com.litongjava.tio.websocket.common.WebSocketRequest;
import com.litongjava.tio.websocket.common.WebSocketResponse;
import com.litongjava.tio.websocket.common.WebSocketSessionContext;
import com.litongjava.tio.websocket.server.handler.IWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeminiLiveWsHandler implements IWebSocketHandler {
  public static final String CHARSET = "utf-8";

  /** 下游转发目标（构造函数传入） */
  private String forwardWsUri;

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

    // 避免重复创建
    downstreamMap.computeIfAbsent(key, k -> {
      Downstream ds = new Downstream(forwardWsUri, channelContext);
      try {
        ds.connect(8, TimeUnit.SECONDS);
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

  /**
   * 每个上游连接对应一个下游连接 下游事件回调里把消息转发回上游
   */
  private static class Downstream {
    private final String uri;
    private final ChannelContext upstreamCtx;

    private volatile WebsocketClient client;
    private volatile WebSocket ws;

    private final CountDownLatch openLatch = new CountDownLatch(1);

    Downstream(String uri, ChannelContext upstreamCtx) {
      this.uri = uri;
      this.upstreamCtx = upstreamCtx;
    }

    void connect(long timeout, TimeUnit unit) throws Exception {
      java.util.function.Consumer<OpenEvent> onOpen = e -> {
        log.info("下游连接已建立: {}", uri);
        openLatch.countDown();
      };

      java.util.function.Consumer<MessageEvent> onMessage = e -> {
        // 下游返回的数据 -> 原样转发给上游
        try {
          if (e == null || e.data == null) {
            return;
          }

          // 你的 demo 里用的是 WebSocketPacket#getWsBodyText()
          // 这里优先当作文本取；如果你有二进制字段，也可以再扩展
          String text = e.data.getWsBodyText();
          if (text != null) {
            WebSocketResponse resp = WebSocketResponse.fromText(text, CHARSET);
            Tio.send(upstreamCtx, resp);
          } else {
            // 如果你的 WebSocketPacket 支持二进制获取（例如 getBodyBytes / getWsBodyBytes 等）
            // 这里按你实际 API 改一下
            byte[] bs = e.data.getBody(); // 若无此方法，请替换成你项目实际的二进制获取方法
            if (bs != null) {
              WebSocketResponse resp = WebSocketResponse.fromBytes(bs);
              Tio.send(upstreamCtx, resp);
            }
          }
        } catch (Exception ex) {
          log.error("下游消息转发到上游失败", ex);
          Tio.remove(upstreamCtx, "下游消息转发失败: " + ex.getMessage());
        }
      };

      java.util.function.Consumer<CloseEvent> onClose = e -> {
        log.info("下游连接关闭: code={}, reason={}, clean={}", e.code, e.reason, e.wasClean);
        // 下游断了，你可以选择同时踢掉上游
        Tio.remove(upstreamCtx, "下游连接关闭: " + e.reason);
      };

      java.util.function.Consumer<ErrorEvent> onError = e -> {
        log.error("下游错误: {}", e != null ? e.msg : "unknown");
        Tio.remove(upstreamCtx, "下游错误: " + (e != null ? e.msg : "unknown"));
      };

      java.util.function.Consumer<Throwable> onThrows = t -> {
        log.error("下游异常", t);
        Tio.remove(upstreamCtx, "下游异常: " + t.getMessage());
      };

      WebsocketClientConfig config = new WebsocketClientConfig(onOpen, onMessage, onClose, onError, onThrows);
      client = WebsocketClient.create(uri, config);
      ws = client.connect();

      boolean opened = openLatch.await(timeout, unit);
      if (!opened) {
        throw new RuntimeException("连接下游超时: " + uri);
      }
    }

    boolean isOpen() {
      return ws != null;
    }

    void sendText(String text) {
      WebSocket w = this.ws;
      if (w != null) {
        w.send(text);
      }
    }

    void sendBytes(byte[] bytes) {
      WebSocket w = this.ws;
      if (w != null) {
        // 如果你的 WebSocket 客户端不是 send(byte[])，请按实际方法名修改
        w.send(bytes);
      }
    }

    void close() {
      try {
        if (ws != null) {
          // 如果你的 WebSocket 有 close(code, reason) / close()，按实际改
          ws.close();
        }
      } catch (Exception e) {
        log.warn("关闭下游 ws 失败", e);
      } finally {
        ws = null;
        client = null;
      }
    }
  }
}
