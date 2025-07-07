package com.litongjava.llm.proxy.config;

import com.litongjava.context.BootConfiguration;
import com.litongjava.llm.proxy.handler.OpenAIV1ChatHandler;
import com.litongjava.tio.boot.server.TioBootServer;
import com.litongjava.tio.http.server.router.HttpRequestRouter;

public class LLMProxyConfig implements BootConfiguration {

  public void config() {

    TioBootServer server = TioBootServer.me();
    HttpRequestRouter requestRouter = server.getRequestRouter();

    OpenAIV1ChatHandler openAIV1ChatHandler = new OpenAIV1ChatHandler();
    requestRouter.add("/openai/v1/chat/completions", openAIV1ChatHandler::completions);
    requestRouter.add("/anthropic/v1/messages", openAIV1ChatHandler::completions);
    requestRouter.add("/google/v1beta/models/*", openAIV1ChatHandler::completions);
  }
}
