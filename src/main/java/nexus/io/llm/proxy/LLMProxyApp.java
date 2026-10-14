package nexus.io.llm.proxy;

import nexus.io.llm.proxy.config.LLMProxyConfig;
import nexus.io.tio.boot.TioApplication;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.PluginCommandService;

public class LLMProxyApp {
  public static void main(String[] args) {
    long start = System.currentTimeMillis();
    TioApplication.run(LLMProxyApp.class, new LLMProxyConfig(), args);
    try {
      Integer exitCode = Aop.get(PluginCommandService.class).execute(args);
      if (exitCode != null) { System.exit(exitCode); }
    } catch (Exception error) {
      System.err.println("Plugin command failed: " + error.getMessage());
      System.exit(1);
    }
    long end = System.currentTimeMillis();
    System.out.println((end - start) + "ms");
  }
}
