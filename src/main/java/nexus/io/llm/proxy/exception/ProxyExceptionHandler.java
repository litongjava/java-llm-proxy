package nexus.io.llm.proxy.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSONException;
import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;
import nexus.io.model.exception.BusinessException;
import nexus.io.model.exception.ParameterValidationException;
import nexus.io.tio.boot.exception.TioBootExceptionHandler;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.websocket.common.WebSocketRequest;
import nexus.io.tio.websocket.common.WebSocketResponse;

public class ProxyExceptionHandler implements TioBootExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ProxyExceptionHandler.class);

  @Override
  public Object handler(HttpRequest request, Throwable error) {
    int status = 500;
    String message = "Service unavailable";
    if (error instanceof ParameterValidationException || error instanceof JSONException) {
      status = 400;
      message = error instanceof JSONException ? "Invalid JSON body" : error.getMessage();
    } else if (error instanceof BusinessException business) {
      status = business.getStatus();
      message = business.getMessage();
    } else if (error instanceof java.io.InterruptedIOException) {
      status = 504;
      message = "Upstream request timed out. Check the outbound proxy and try again.";
      LOG.warn("Request timed out: {}", request.getRequestURI());
    } else if (error instanceof java.net.SocketException || error instanceof java.net.UnknownHostException
        || error instanceof javax.net.ssl.SSLException) {
      status = 502;
      message = "Upstream connection failed. Check the outbound proxy, network and certificate trust.";
      LOG.warn("Upstream connection failed: {} ({})", request.getRequestURI(), error.getClass().getSimpleName());
    } else {
      LOG.error("Request failed: {}", request.getRequestURI(), error);
    }
    TioRequestContext.getResponse().setStatus(status);
    return RespBodyVo.fail(status, message);
  }

  @Override
  public Object wsTextHandler(WebSocketRequest ws, String text, ChannelContext channel, HttpRequest request,
      Throwable error) {
    int status = error instanceof ParameterValidationException || error instanceof JSONException ? 400 : 500;
    return WebSocketResponse.fromText(JsonUtils.toJson(Kv.create().set("type", "error").set("error", Kv.create()
        .set("code", status).set("message", status == 400 ? error.getMessage() : "WebSocket request failed"))),
        "UTF-8");
  }

  @Override
  public Object wsBytesHandler(WebSocketRequest ws, byte[] bytes, ChannelContext channel, HttpRequest request,
      Throwable error) {
    return wsTextHandler(ws, null, channel, request, error);
  }
}
