package com.litongjava.llm.proxy.callback;

import java.io.IOException;

import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.http.common.sse.SsePacket;
import com.litongjava.tio.utils.SystemTimer;
import com.litongjava.tio.utils.hutool.StrUtil;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

@Slf4j
public class SSEProxyCallbackEventSourceListener extends EventSourceListener {

  private ChannelContext channelContext;
  private HttpResponse httpResponse;
  private long start;
  private boolean continueSend = true;

  public SSEProxyCallbackEventSourceListener(ChannelContext channelContext, HttpResponse httpResponse, long start) {
    this.channelContext = channelContext;
    this.httpResponse = httpResponse;
    this.start = start;
  }

  @Override
  public void onOpen(EventSource eventSource, Response response) {
    httpResponse.addServerSentEventsHeader();
    httpResponse.setSend(true);
    Tio.send(channelContext, httpResponse);
  }

  @Override
  public void onEvent(EventSource eventSource, String id, String type, String data) {

    if (StrUtil.notBlank(data)) {
      sendPacket(new SsePacket(type, data.getBytes()));
      // [DONE] 是open ai的数据标识
      if ("[DONE]".equals(data)) {
        finish(eventSource);
        return;
      }
    }

  }

  @Override
  public void onClosed(EventSource eventSource) {
    finish(eventSource);
  }

  @Override
  public void onFailure(EventSource eventSource, Throwable t, Response response) {
    log.error(t.getMessage(), t);
    try {
      int code = response.code();
      String string = response.body().string();
      httpResponse.status(code);
      httpResponse.body(string);

      httpResponse.setSend(true);
      Tio.send(channelContext, httpResponse);
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      response.close();
    }

    finish(eventSource);
  }

  private void finish(EventSource eventSource) {
    log.info("elapse:{}", SystemTimer.currTime - start);
    eventSource.cancel();
    Tio.close(channelContext, "finish");
  }

  /** 三次重试发送 SSE，遇断就放弃 */
  private void sendPacket(SsePacket packet) {
    if (!continueSend)
      return;
    if (!Tio.bSend(channelContext, packet)) {
      if (!Tio.bSend(channelContext, packet)) {
        if (!Tio.bSend(channelContext, packet)) {
          continueSend = false;
        }
      }
    }
  }
}
