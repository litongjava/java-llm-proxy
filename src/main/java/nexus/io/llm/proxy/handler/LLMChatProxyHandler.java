package nexus.io.llm.proxy.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;

import lombok.extern.slf4j.Slf4j;
import nexus.io.cerebras.CerebrasConst;
import nexus.io.claude.ClaudeClient;
import nexus.io.gemini.GeminiClient;
import nexus.io.llm.proxy.callback.SSEProxyCallbackEventSourceListener;
import nexus.io.model.body.RespBodyVo;
import nexus.io.openai.client.OpenAiClient;
import nexus.io.openrouter.OpenRouterConst;
import nexus.io.proxy.AiChatProxyClient;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.common.utils.HttpIpUtils;
import nexus.io.tio.http.server.handler.HttpRequestHandler;
import nexus.io.tio.http.server.util.CORSUtils;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.hutool.StrUtil;
import nexus.io.tio.utils.json.FastJson2Utils;
import nexus.io.vertexai.VertexAiConsts;
import okhttp3.Response;
import okhttp3.sse.EventSourceListener;

@Slf4j
public class LLMChatProxyHandler implements HttpRequestHandler {

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
    String authorization = httpRequest.getAuthorization();
    if (requestURI.startsWith("/openai")) {
      url = OpenAiClient.OPENAI_API_URL + "/chat/completions";
      headers.put("authorization", authorization);

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }

    } else if (requestURI.startsWith("/openrouter")) {
      url = OpenRouterConst.API_PREFIX_URL + "/chat/completions";

      headers.put("authorization", authorization);

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }

    } else if (requestURI.startsWith("/cerebras")) {
      url = CerebrasConst.API_PREFIX_URL + "/chat/completions";

      headers.put("authorization", authorization);

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }
    } else if (requestURI.startsWith("/anthropic") && requestURI.endsWith("completions")) {
      url = ClaudeClient.CLAUDE_API_URL + "/chat/completions";

      headers.put("authorization", authorization);

      JSONObject openAiRequestVo = null;

      if (bodyString != null) {
        openAiRequestVo = FastJson2Utils.parseObject(bodyString);
        stream = openAiRequestVo.getBoolean("stream");
      }

    } else if (requestURI.startsWith("/anthropic") && requestURI.endsWith("messages")) {
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
      if (key != null) {
        if (requestURI.endsWith("streamGenerateContent")) {
          url = GeminiClient.GEMINI_API_URL + "/" + modelName1 + ":streamGenerateContent?alt=sse&key=" + key;
          stream = true;
        } else {
          url = GeminiClient.GEMINI_API_URL + "/" + modelName1 + ":generateContent?key=" + key;
        }
      } else {
        if (requestURI.endsWith("streamGenerateContent")) {
          url = GeminiClient.GEMINI_API_URL + "/" + modelName1 + ":streamGenerateContent?alt=sse";
          stream = true;
        } else {
          url = GeminiClient.GEMINI_API_URL + "/" + modelName1 + ":generateContent";
        }
        if (authorization != null) {
          headers.put("authorization", authorization);
        }
        String googleApiKey = httpRequest.getHeader("x-goog-api-key");
        if (googleApiKey != null) {
          headers.put("x-goog-api-key", googleApiKey);
        }
      }

    } else if (requestURI.startsWith("/vertexai")) {
      String key = httpRequest.getParam("key");
      String modelName1 = requestURI.substring(requestURI.lastIndexOf('/') + 1, requestURI.indexOf(':'));
      if (key != null) {
        if (requestURI.endsWith("streamGenerateContent")) {
          url = VertexAiConsts.API_MODEL_BASE + "/" + modelName1 + ":streamGenerateContent?alt=sse&key=" + key;
          stream = true;
        } else {
          url = VertexAiConsts.API_MODEL_BASE + "/" + modelName1 + ":generateContent?key=" + key;
        }
      } else {
        if (requestURI.endsWith("streamGenerateContent")) {
          url = VertexAiConsts.API_MODEL_BASE + "/" + modelName1 + ":streamGenerateContent?alt=sse";
          stream = true;
        } else {
          url = VertexAiConsts.API_MODEL_BASE + "/" + modelName1 + ":generateContent";
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
