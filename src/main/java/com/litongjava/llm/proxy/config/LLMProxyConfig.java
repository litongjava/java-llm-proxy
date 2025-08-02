package com.litongjava.llm.proxy.config;

import com.litongjava.context.BootConfiguration;
import com.litongjava.llm.proxy.handler.LLMProxyHandler;
import com.litongjava.tio.boot.server.TioBootServer;
import com.litongjava.tio.http.server.router.HttpRequestRouter;

public class LLMProxyConfig implements BootConfiguration {

  public void config() {

    TioBootServer server = TioBootServer.me();
    HttpRequestRouter requestRouter = server.getRequestRouter();
    if (requestRouter != null) {
      LLMProxyHandler openAIV1ChatHandler = new LLMProxyHandler();
      requestRouter.add("/openai/v1/chat/completions", openAIV1ChatHandler::completions);
      requestRouter.add("/anthropic/v1/messages", openAIV1ChatHandler::completions);
      requestRouter.add("/google/v1beta/models/*", openAIV1ChatHandler::completions);
      requestRouter.add("/openrouter/v1/chat/completions", openAIV1ChatHandler::completions);
    }
  }
}
