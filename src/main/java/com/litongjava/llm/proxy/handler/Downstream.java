package com.litongjava.llm.proxy.handler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.litongjava.llm.proxy.mode.GoogleWsConnectParam;
import com.litongjava.tio.consts.TioConst;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.proxy.ProxyInfo;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.litongjava.tio.websocket.client.WebSocket;
import com.litongjava.tio.websocket.client.WebsocketClient;
import com.litongjava.tio.websocket.client.config.WebsocketClientConfig;
import com.litongjava.tio.websocket.client.event.CloseEvent;
import com.litongjava.tio.websocket.client.event.ErrorEvent;
import com.litongjava.tio.websocket.client.event.MessageEvent;
import com.litongjava.tio.websocket.client.event.OpenEvent;
import com.litongjava.tio.websocket.common.WebSocketResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 每个上游连接对应一个下游连接 下游事件回调里把消息转发回上游
 */
@Slf4j
public class Downstream {
  private String uri;
  private ChannelContext upstreamCtx;

  private WebsocketClient client;
  private WebSocket ws;
  private GoogleWsConnectParam googleWsConnectParam;

  private final CountDownLatch openLatch = new CountDownLatch(1);

  public Downstream(String uri, ChannelContext upstreamCtx) {
    this.uri = uri;
    this.upstreamCtx = upstreamCtx;
  }

  public Downstream(String uri, GoogleWsConnectParam googleWsConnectParam, ChannelContext upstreamCtx) {
    this.uri = uri;
    this.googleWsConnectParam = googleWsConnectParam;
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
          WebSocketResponse resp = WebSocketResponse.fromText(text, TioConst.CHARSET_NAME);
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
    String proxyHost = EnvUtils.getStr("http.proxyHost");
    int proxyPort = EnvUtils.getInt("http.proxyPort");
    if (proxyHost != null) {
      config.setProxyInfo(new ProxyInfo(proxyHost, proxyPort));
    }

    Map<String, String> headers = new HashMap<>();

    if (googleWsConnectParam != null && googleWsConnectParam.getUserAgent() != null) {
      headers.put("User-Agent", googleWsConnectParam.getUserAgent());
    }

    // 例：把 apiKey 放到 Authorization（按你下游协议要求决定）
    if (googleWsConnectParam != null && googleWsConnectParam.getApiKey() != null) {
      headers.put("x-goog-api-key", googleWsConnectParam.getApiKey());
    }

    // 例：其它自定义头
    if (googleWsConnectParam != null && googleWsConnectParam.getApiClient() != null) {
      headers.put("x-goog-api-client", googleWsConnectParam.getApiClient());
    }

    // 注意：Host / Upgrade / Connection / Sec-WebSocket-* 这些在 handshake() 里会被设置，
    client = WebsocketClient.create(uri, headers, config);
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