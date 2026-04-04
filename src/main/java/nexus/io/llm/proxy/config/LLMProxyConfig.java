package nexus.io.llm.proxy.config;

import nexus.io.context.BootConfiguration;

public class LLMProxyConfig implements BootConfiguration {

  public void config() {
    new LLMProxyAppConfig().config();
  }
}
