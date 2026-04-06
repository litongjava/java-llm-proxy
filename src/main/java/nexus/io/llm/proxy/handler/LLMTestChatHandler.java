package nexus.io.llm.proxy.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;

import lombok.extern.slf4j.Slf4j;
import nexus.io.chat.UniChatClient;
import nexus.io.llm.proxy.callback.SSEProxyCallbackEventSourceListener;
import nexus.io.model.body.RespBodyVo;
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
import okhttp3.Response;
import okhttp3.sse.EventSourceListener;

@Slf4j
public class LLMTestChatHandler implements HttpRequestHandler {

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
    log.info("id:{},from:{},requestURI:{},body:\n{}", id, realIp, requestURI, bodyString);
    Boolean stream = false;
    Map<String, String> headers = new HashMap<>();
    String authorization = httpRequest.getAuthorization();

    String url = null;
    url = UniChatClient.GITEE_API_URL + "/chat/completions";
    headers.put("authorization", authorization);

    JSONObject openAiRequestVo = null;

    if (bodyString != null) {
      openAiRequestVo = FastJson2Utils.parseObject(bodyString);
      stream = openAiRequestVo.getBoolean("stream");
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
