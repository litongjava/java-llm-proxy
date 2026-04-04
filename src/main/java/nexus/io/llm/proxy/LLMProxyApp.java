package nexus.io.llm.proxy;

import nexus.io.llm.proxy.config.LLMProxyConfig;
import nexus.io.tio.boot.TioApplication;

public class LLMProxyApp {
  public static void main(String[] args) {
    long start = System.currentTimeMillis();
    TioApplication.run(LLMProxyApp.class, new LLMProxyConfig(), args);
    long end = System.currentTimeMillis();
    System.out.println((end - start) + "ms");
  }
}