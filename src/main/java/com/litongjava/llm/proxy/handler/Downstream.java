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

@Slf4j
public class Downstream {
  private final String uri;
  private final ChannelContext upstreamCtx;

  private WebsocketClient client;
  private WebSocket ws;
  private final GoogleWsConnectParam googleWsConnectParam;

  private final CountDownLatch openLatch = new CountDownLatch(1);
  private volatile Throwable connectError;

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
      try {
        if (e == null || e.data == null) {
          return;
        }
        String text = e.data.getWsBodyText();
        if (text != null) {
          WebSocketResponse resp = WebSocketResponse.fromText(text, TioConst.CHARSET_NAME);
          Tio.send(upstreamCtx, resp);
        } else {
          byte[] bs = e.data.getBody();
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
      connectError = new RuntimeException("closed before open: code=" + e.code + ", reason=" + e.reason);
      openLatch.countDown();
      Tio.remove(upstreamCtx, "下游连接关闭: " + e.reason);
    };

    java.util.function.Consumer<ErrorEvent> onError = e -> {
      String msg = (e != null ? e.msg : "unknown");
      log.error("下游错误: {}", msg);
      connectError = new RuntimeException("error before open: " + msg);
      openLatch.countDown();
      Tio.remove(upstreamCtx, "下游错误: " + msg);
    };

    java.util.function.Consumer<Throwable> onThrows = t -> {
      log.error("下游异常", t);
      connectError = t;
      openLatch.countDown();
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
    if (googleWsConnectParam != null && googleWsConnectParam.getApiKey() != null) {
      headers.put("x-goog-api-key", googleWsConnectParam.getApiKey());
    }
    if (googleWsConnectParam != null && googleWsConnectParam.getApiClient() != null) {
      headers.put("x-goog-api-client", googleWsConnectParam.getApiClient());
    }

    client = WebsocketClient.create(uri, headers, config);
    ws = client.connect();

    boolean finished = openLatch.await(timeout, unit);
    if (!finished) {
      throw new RuntimeException("连接下游超时: " + uri);
    }
    if (connectError != null) {
      throw new RuntimeException("连接下游失败: " + uri, connectError);
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
      w.send(bytes);
    }
  }

  void close() {
    try {
      if (ws != null) {
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