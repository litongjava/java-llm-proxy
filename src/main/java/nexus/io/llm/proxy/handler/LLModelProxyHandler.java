package nexus.io.llm.proxy.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import nexus.io.cerebras.CerebrasConst;
import nexus.io.claude.ClaudeClient;
import nexus.io.gemini.GeminiClient;
import nexus.io.openai.client.OpenAiClient;
import nexus.io.openrouter.OpenRouterConst;
import nexus.io.proxy.AiChatProxyClient;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.common.utils.HttpIpUtils;
import nexus.io.tio.http.server.handler.HttpRequestHandler;
import nexus.io.tio.http.server.util.CORSUtils;
import nexus.io.tio.utils.environment.EnvUtils;
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
