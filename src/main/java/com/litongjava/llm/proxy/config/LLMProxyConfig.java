package com.litongjava.llm.proxy.config;

import com.litongjava.context.BootConfiguration;

public class LLMProxyConfig implements BootConfiguration {

  public void config() {
    new LLMProxyAppConfig().config();
  }
}
