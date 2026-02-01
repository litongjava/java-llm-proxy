package com.litongjava.llm.proxy.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.litongjava.cerebras.CerebrasConst;
import com.litongjava.claude.ClaudeClient;
import com.litongjava.gemini.GeminiClient;
import com.litongjava.openai.client.OpenAiClient;
import com.litongjava.openrouter.OpenRouterConst;
import com.litongjava.proxy.AiChatProxyClient;
import com.litongjava.tio.boot.http.TioRequestContext;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.http.common.utils.HttpIpUtils;
import com.litongjava.tio.http.server.handler.HttpRequestHandler;
import com.litongjava.tio.http.server.util.CORSUtils;
import com.litongjava.tio.utils.environment.EnvUtils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;

@Slf4j
public class LLModelProxyHandler implements HttpRequestHandler {

  @Override
  public HttpResponse handle(HttpRequest httpRequest) throws Exception {
    long start = System.currentTimeMillis();
    HttpResponse httpResponse = TioRequestContext.getResponse();
    CORSUtils.enableCORS(httpResponse);

    Long id = httpRequest.getId();
    String requestURI = httpRequest.getRequestURI();

    String realIp = HttpIpUtils.getRealIp(httpRequest);
    log.info("id:{},from:{},requestURI:{}", id, realIp, requestURI);
    String url = null;
    Map<String, String> headers = new HashMap<>();
    String authorization = httpRequest.getAuthorization();
    if (requestURI.startsWith("/openai")) {
      url = OpenAiClient.OPENAI_API_URL + "/models";
      headers.put("authorization", authorization);
    } else if (requestURI.startsWith("/openrouter")) {
      url = OpenRouterConst.API_PREFIX_URL + "/models";

      headers.put("authorization", authorization);

    } else if (requestURI.startsWith("/cerebras")) {
      url = CerebrasConst.API_PREFIX_URL + "/models";

    } else if (requestURI.startsWith("/anthropic") && requestURI.endsWith("completions")) {
      url = ClaudeClient.CLAUDE_API_URL + "/models";

    } else if (requestURI.startsWith("/anthropic") && requestURI.endsWith("messages")) {
      url = ClaudeClient.CLAUDE_API_URL + "/modles";
      headers.put("x-api-key", httpRequest.getHeader("x-api-key"));
      headers.put("anthropic-version", httpRequest.getHeader("anthropic-version"));

    } else if (requestURI.startsWith("/google")) {
      String key = httpRequest.getParam("key");
      String modelName1 = requestURI.substring(requestURI.lastIndexOf('/') + 1, requestURI.indexOf(':'));
      if (key != null) {
        if (requestURI.endsWith("streamGenerateContent")) {
          url = GeminiClient.GEMINI_API_URL + modelName1 + ":streamGenerateContent?alt=sse&key=" + key;
        } else {
          url = GeminiClient.GEMINI_API_URL + modelName1 + ":generateContent?key=" + key;
        }
      } else {
        if (requestURI.endsWith("streamGenerateContent")) {
          url = GeminiClient.GEMINI_API_URL + modelName1 + ":streamGenerateContent?alt=sse";
        } else {
          url = GeminiClient.GEMINI_API_URL + modelName1 + ":generateContent";
        }
        if (authorization != null) {
          headers.put("authorization", authorization);
        }
        String googleApiKey = httpRequest.getHeader("x-goog-api-key");
        if (googleApiKey != null) {
          headers.put("x-goog-api-key", googleApiKey);
        }
      }

    }

    // String authorization = httpRequest.getHeader("authorization");

    try (Response response = AiChatProxyClient.generate(url, headers)) {
      // OkHttpResponseUtils.toTioHttpResponse(response, httpResponse);
      int code = response.code();
      httpResponse.setStatus(code);

      try {
        String resposneBody = response.body().string();
        httpResponse.setString(resposneBody, "utf-8", "application/json");
        httpResponse.setSkipGzipped(true);
        if (EnvUtils.getBoolean("app.debug", false)) {
          log.info("chat:{},{}", requestURI, resposneBody);
        }

      } catch (IOException e) {
        e.printStackTrace();
      }
      long end = System.currentTimeMillis();
      log.info("finish llm in {} (ms):", (end - start));
    }

    return httpResponse;
  }
}
