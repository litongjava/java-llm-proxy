package nexus.io.llm.proxy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nexus.io.llm.proxy.config.LLMProxyConfig;
import nexus.io.tio.boot.TioApplication;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.PluginCommandService;

public class LLMProxyApp {
  public static void main(String[] args) {
    long start = System.currentTimeMillis();
    // Configure the business executor before the server copies its startup
    // settings.
    ExecutorService businessExecutor = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("proxy-request-", 0).factory());
    TioBootServer.me().setBizExecutor(businessExecutor);

    Runtime.getRuntime().addShutdownHook(new Thread(businessExecutor::shutdownNow, "proxy-request-shutdown"));

    TioApplication.run(LLMProxyApp.class, new LLMProxyConfig(), args);
    try {
      Integer exitCode = Aop.get(PluginCommandService.class).execute(args);
      if (exitCode != null) {
        System.exit(exitCode);
      }
    } catch (Exception error) {
      System.err.println("Plugin command failed: " + error.getMessage());
      System.exit(1);
    }
    long end = System.currentTimeMillis();
    System.out.println((end - start) + "ms");
  }
}
