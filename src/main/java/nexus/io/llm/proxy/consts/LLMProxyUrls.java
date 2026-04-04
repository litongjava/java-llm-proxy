package nexus.io.llm.proxy.consts;

public interface LLMProxyUrls {

  String[] URL = { "/openai/**", "/openrouter/**", "/cerebras/**",
      //
      "/anthropic/**",
      //
      "/google/**", "/vertexai/**" };
}
