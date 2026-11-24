package nexus.io.llm.proxy.handler;

import com.jfinal.kit.Kv;
import java.util.ArrayList;
import java.util.List;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ApiKey;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.service.SettingsService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.utils.environment.EnvUtils;

public class WebSettingsHandler {
  public HttpResponse handle(HttpRequest request) {
    int port = EnvUtils.getInt("server.port", 8318);
    String boundHost = EnvUtils.get("server.address", "127.0.0.1");
    boolean wildcard = boundHost.isBlank() || "0.0.0.0".equals(boundHost) || "::".equals(boundHost)
        || "[::]".equals(boundHost);
    // A wildcard bind has no client-facing host; mirror the address the browser used.
    String host = wildcard ? requestedHost(request) : boundHost;
    boolean allowLan = wildcard || !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host));
    ProxySettings settings = Aop.get(SettingsService.class).snapshot();
    List<Kv> apiKeys = new ArrayList<>();
    if (settings.apiKeys != null) {
      for (ApiKey key : settings.apiKeys) {
        apiKeys.add(Kv.create().set("apiKey", key.key).set("remark", key.name));
      }
    }
    String managementToken = EnvUtils.get("proxy.management-token");
    Kv gui = Kv.create().set("host", host).set("port", port).set("runOnStartup", false)
        .set("allowLan", allowLan).set("closeBehavior", "ask");
    Kv core = Kv.create().set("host", host).set("port", port).set("allowLan", allowLan)
        .set("managementSecretConfigured", managementToken != null && !managementToken.isBlank())
        .set("apiKeys", apiKeys).set("debug", false).set("commercialMode", false)
        .set("loggingToFile", false).set("logsMaxTotalSizeMb", 0).set("errorLogsMaxFiles", 0)
        .set("usageStatisticsEnabled", true).set("redisUsageQueueRetentionSeconds", 0)
        .set("routingStrategy", settings.routing == null ? "round-robin" : settings.routing)
        .set("proxyUrl", EnvUtils.get("proxy.outbound-proxy", "")).set("proxyOverride", false)
        .set("routingSessionAffinity", false).set("routingSessionAffinityTtl", "0")
        .set("disableCooling", false).set("requestRetry", 3).set("maxRetryCredentials", 1)
        .set("maxRetryInterval", 0).set("streamingBootstrapRetries", 0);
    Kv tls = Kv.create().set("enabled", false).set("cert", "").set("key", "");
    return TioRequestContext.getResponse().respond(RespBodyVo.ok(Kv.create()
        .set("gui", gui).set("core", core).set("tls", tls).set("version", "web")));
  }

  private String requestedHost(HttpRequest request) {
    String header = request.getHeader("host");
    if (header == null || header.isBlank()) {
      return "127.0.0.1";
    }
    String value = header.trim();
    if (value.startsWith("[")) {
      int end = value.indexOf(']');
      return end > 0 ? value.substring(0, end + 1) : value;
    }
    int colon = value.indexOf(':');
    return colon > 0 ? value.substring(0, colon) : value;
  }
}
