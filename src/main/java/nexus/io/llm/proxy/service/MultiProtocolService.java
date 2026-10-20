package nexus.io.llm.proxy.service;

import java.net.URI;
import java.util.Set;
import com.alibaba.fastjson2.JSON;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.validator.ParameterValidator;

public class MultiProtocolService {
  private static final Set<String> PROTOCOLS = Set.of("openai", "responses", "anthropic", "gemini");

  public static void validate(Provider provider) {
    boolean configured = provider.protocolEndpoints != null && !provider.protocolEndpoints.isEmpty();
    if (!configured && !"multi-protocol".equals(provider.upstreamType)) { return; }
    ParameterValidator.require(configured, "Select at least one supported protocol");
    ParameterValidator.require(provider.protocolEndpoints.containsKey(provider.protocol), "Default protocol must be supported");
    provider.protocolEndpoints.forEach((protocol, base) -> {
      ParameterValidator.require(PROTOCOLS.contains(protocol), "Unsupported provider protocol");
      URI uri = null;
      try { uri = URI.create(base); } catch (RuntimeException ignored) { }
      ParameterValidator.require(uri != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
          "Protocol endpoint must be an HTTP API prefix");
    });
    provider.baseUrl = provider.protocolEndpoints.get(provider.protocol).replaceAll("/+$", "");
  }

  public static boolean supports(Provider provider, String protocol) {
    return provider.protocolEndpoints != null && !provider.protocolEndpoints.isEmpty()
        ? provider.protocolEndpoints.containsKey(protocol) : protocol.equals(provider.protocol);
  }

  public Provider resolve(Provider provider, String protocol) {
    if (provider.protocolEndpoints == null || provider.protocolEndpoints.isEmpty()) { return provider; }
    Provider resolved = JSON.parseObject(JSON.toJSONString(provider), Provider.class);
    validate(resolved);
    resolved.protocol = supports(provider, protocol) ? protocol : provider.protocol;
    resolved.baseUrl = provider.protocolEndpoints.get(resolved.protocol).replaceAll("/+$", "");
    resolved.upstreamType = "multi-protocol";
    return resolved;
  }
}
