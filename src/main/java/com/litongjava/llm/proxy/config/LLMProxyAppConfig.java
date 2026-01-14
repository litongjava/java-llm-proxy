package com.litongjava.llm.proxy.config;

import com.litongjava.llm.proxy.handler.GeminiLiveWsHandler;
import com.litongjava.llm.proxy.handler.LLMProxyHandler;
import com.litongjava.tio.boot.server.TioBootServer;
import com.litongjava.tio.boot.websocket.WebSocketRouter;
import com.litongjava.tio.http.server.router.HttpRequestRouter;

public class LLMProxyAppConfig {

  public void config() {
    TioBootServer server = TioBootServer.me();
    HttpRequestRouter requestRouter = server.getRequestRouter();
    if (requestRouter != null) {
      LLMProxyHandler openAIV1ChatHandler = new LLMProxyHandler();
      requestRouter.add("/openai/v1/chat/completions", openAIV1ChatHandler);
      requestRouter.add("/anthropic/v1/messages", openAIV1ChatHandler);
      requestRouter.add("/google/v1beta/models/*", openAIV1ChatHandler);
      requestRouter.add("/openrouter/v1/chat/completions", openAIV1ChatHandler);
      requestRouter.add("/cerebras/v1/chat/completions", openAIV1ChatHandler);
    }
    WebSocketRouter webSocketRouter = server.getWebSocketRouter();
    if (webSocketRouter != null) {
      String uri = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
      String url = "wss://generativelanguage.googleapis.com" + uri;
      GeminiLiveWsHandler geminiLiveWsHandler = new GeminiLiveWsHandler(url);
      // GOOGLE_GEMINI_BASE_URL=http://localhost:8080/google/gemini
      String path = "/google/gemini" + uri;
      webSocketRouter.add(path, geminiLiveWsHandler);
    }
  }
}
