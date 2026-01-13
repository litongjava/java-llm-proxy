package com.litongjava.llm.proxy.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;
import com.litongjava.cerebras.CerebrasConst;
import com.litongjava.claude.ClaudeClient;
import com.litongjava.gemini.GeminiClient;
import com.litongjava.llm.proxy.callback.SSEProxyCallbackEventSourceListener;
import com.litongjava.model.body.RespBodyVo;
import com.litongjava.openai.client.OpenAiClient;
import com.litongjava.openrouter.OpenRouterConst;
import com.litongjava.proxy.AiChatProxyClient;
import com.litongjava.tio.boot.http.TioRequestContext;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HeaderName;
import com.litongjava.tio.http.common.HeaderValue;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.http.common.utils.HttpIpUtils;
import com.litongjava.tio.http.server.handler.HttpRequestHandler;
import com.litongjava.tio.http.server.util.CORSUtils;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.litongjava.tio.utils.hutool.StrUtil;
import com.litongjava.tio.utils.json.FastJson2Utils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSourceListener;

@Slf4j
public class LLMProxyHandler implements HttpRequestHandler {

  @Override
  public HttpResponse handle(HttpRequest httpRequest) throws Exception {
    long start = System.currentTimeMillis();
    HttpResponse httpResponse = TioRequestContext.getResponse();
    CORSUtils.enableCORS(httpResponse);

    Long id = httpRequest.getId();
    String requestURI = httpRequest.getRequestURI();

    String bodyString = httpRequest.getBodyString();

    if (StrUtil.isBlank(bodyString)) {
      return httpResponse.setJson(RespBodyVo.fail("empty body"));
    }

    String realIp = HttpIpUtils.getRealIp(httpRequest);
    log.info("id:{},from:{},requestURI:{}", id, realIp, requestURI);
    Boolean stream = false;
    String url = null;
    Map<String, String> headers = new HashMap<>();
    if (requestURI.startsWith("/openai")) {
      url = OpenAiClient.OPENAI_API_URL + "/chat/completions";
      headers.put("authorization", httpRequest.getAuthorization());

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }

    } else if (requestURI.startsWith("/openrouter")) {
      url = OpenRouterConst.API_PREFIX_URL + "/chat/completions";

      headers.put("authorization", httpRequest.getAuthorization());

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }

    } else if (requestURI.startsWith("/cerebras")) {
      url = CerebrasConst.API_PREFIX_URL + "/chat/completions";

      headers.put("authorization", httpRequest.getAuthorization());

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }

    } else if (requestURI.startsWith("/anthropic")) {
      url = ClaudeClient.CLAUDE_API_URL + "/messages";
      headers.put("x-api-key", httpRequest.getHeader("x-api-key"));
      headers.put("anthropic-version", httpRequest.getHeader("anthropic-version"));

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }

    } else if (requestURI.startsWith("/google")) {
      String key = httpRequest.getParam("key");
      String modelName1 = requestURI.substring(requestURI.lastIndexOf('/') + 1, requestURI.indexOf(':'));
      if (requestURI.endsWith("streamGenerateContent")) {
        url = GeminiClient.GEMINI_API_URL + modelName1 + ":streamGenerateContent?alt=sse&key=" + key;
        stream = true;
      } else {
        url = GeminiClient.GEMINI_API_URL + modelName1 + ":generateContent?key=" + key;
      }
    }

    // String authorization = httpRequest.getHeader("authorization");

    if (stream != null && stream) {
      // 告诉默认的处理器不要将消息体发送给客户端,因为后面会手动发送
      httpResponse.setSend(false);
      ChannelContext channelContext = httpRequest.getChannelContext();
      EventSourceListener openAIProxyCallback = new SSEProxyCallbackEventSourceListener(id, channelContext,
          httpResponse, start);
      AiChatProxyClient.stream(url, headers, bodyString, openAIProxyCallback);
    } else {
      try (Response response = AiChatProxyClient.generate(url, headers, bodyString)) {
        // OkHttpResponseUtils.toTioHttpResponse(response, httpResponse);
        int code = response.code();
        httpResponse.setStatus(code);

        try {
          String resposneBody = response.body().string();
          httpResponse.setString(resposneBody, "utf-8", "application/json");
          httpResponse.setSkipGzipped(true);
          if (EnvUtils.getBoolean("app.debug", false)) {
            log.info("chat:{},{}", bodyString, resposneBody);
          }

        } catch (IOException e) {
          e.printStackTrace();
        }
        long end = System.currentTimeMillis();
        log.info("finish llm in {} (ms):", (end - start));
      }
    }

    return httpResponse;
  }
}
