package nexus.io.llm.proxy.config;

import nexus.io.llm.proxy.handler.GeminiLiveWsHandler;
import nexus.io.llm.proxy.handler.LLMChatHandler;
import nexus.io.llm.proxy.handler.LLMChatProxyHandler;
import nexus.io.llm.proxy.handler.LLMTestChatHandler;
import nexus.io.llm.proxy.handler.LLModelProxyHandler;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.tio.boot.websocket.WebSocketRouter;
import nexus.io.tio.http.server.router.HttpRequestRouter;

public class LLMProxyAppConfig {

  public void config() {
    TioBootServer server = TioBootServer.me();
    HttpRequestRouter requestRouter = server.getRequestRouter();
    if (requestRouter != null) {
      LLMChatHandler llmChatHandler = new LLMChatHandler();
      requestRouter.add("/v1/chat/completions", llmChatHandler);
      
      requestRouter.add("/test/v1/chat/completions", new LLMTestChatHandler());
      
      
      LLMChatProxyHandler openAIV1ChatHandler = new LLMChatProxyHandler();
      
      requestRouter.add("/openai/v1/chat/completions", openAIV1ChatHandler);
      requestRouter.add("/openrouter/v1/chat/completions", openAIV1ChatHandler);
      requestRouter.add("/cerebras/v1/chat/completions", openAIV1ChatHandler);
      requestRouter.add("/anthropic/v1/chat/completions", openAIV1ChatHandler);
      
      requestRouter.add("/anthropic/v1/messages", openAIV1ChatHandler);
      requestRouter.add("/google/v1beta/models/*", openAIV1ChatHandler);
      requestRouter.add("/vertexai/v1beta/models/*", openAIV1ChatHandler);
      
      LLModelProxyHandler llModelProxyHandler = new LLModelProxyHandler();
      requestRouter.add("/openai/v1/models", llModelProxyHandler);
      
      
      
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
