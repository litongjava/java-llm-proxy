package nexus.io.llm.proxy.service;

import java.net.URI;
import java.util.Set;
import com.alibaba.fastjson2.JSON;
import nexus.io.llm.proxy.model.Provider;

public class DeepSeekTransportService {
  public Provider nativeProvider(Provider provider, String protocol, String endpoint) {
    if (!"anthropic".equals(protocol) || !"/messages".equals(endpoint)
        || !"api-key".equals(provider.authType) || provider.baseUrl == null) { return provider; }
    try {
      URI uri = URI.create(provider.baseUrl);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.deepseek.com".equalsIgnoreCase(uri.getHost())
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || (uri.getPort() != -1 && uri.getPort() != 443)
          || !Set.of("", "/v1", "/anthropic", "/anthropic/v1").contains(uri.getPath().replaceAll("/+$", ""))) { return provider; }
    } catch (IllegalArgumentException error) { return provider; }
    Provider nativeProvider = JSON.parseObject(JSON.toJSONString(provider), Provider.class);
    nativeProvider.protocol = "anthropic";
    nativeProvider.baseUrl = "https://api.deepseek.com/anthropic";
    nativeProvider.upstreamType = "deepseek";
    return nativeProvider;
  }
}
