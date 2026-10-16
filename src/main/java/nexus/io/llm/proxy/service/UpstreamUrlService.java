package nexus.io.llm.proxy.service;

public final class UpstreamUrlService {
  private UpstreamUrlService() { }

  public static String endpoint(String baseUrl, String protocol, String endpoint) {
    String base = baseUrl.replaceAll("/+$", "");
    if ("anthropic".equals(protocol) && !base.endsWith("/v1")) {
      base += "/v1";
    }
    return base + endpoint;
  }
}
