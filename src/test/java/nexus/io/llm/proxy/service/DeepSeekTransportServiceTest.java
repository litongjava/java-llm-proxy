package nexus.io.llm.proxy.service;

import java.util.List;
import nexus.io.llm.proxy.model.Provider;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeepSeekTransportServiceTest {
  private final DeepSeekTransportService service = new DeepSeekTransportService();

  @Test public void selectsNativeMessagesWithoutMutatingSharedProvider() {
    for (String path : List.of("", "/", "/v1", "/anthropic/v1/")) {
      Provider provider = new Provider();
      provider.baseUrl = "https://api.deepseek.com" + path;
      provider.protocol = "responses";
      provider.upstreamType = "codex";
      provider.apiKey = "test-key";
      Provider nativeProvider = service.nativeProvider(provider, "anthropic", "/messages");
      assertNotSame(provider, nativeProvider);
      assertEquals("responses", provider.protocol);
      assertEquals("codex", provider.upstreamType);
      assertEquals("anthropic", nativeProvider.protocol);
      assertEquals("deepseek", nativeProvider.upstreamType);
      assertEquals("test-key", nativeProvider.apiKey);
      assertEquals("https://api.deepseek.com/anthropic/v1/messages",
          UpstreamUrlService.endpoint(nativeProvider.baseUrl, nativeProvider.protocol, "/messages"));
      assertSame(provider, service.nativeProvider(provider, "responses", "/responses"));
      assertSame(provider, service.nativeProvider(provider, "anthropic", "/messages/count_tokens"));
    }
  }

  @Test public void preservesCustomGatewaysAndOauth() {
    for (String base : List.of("https://api.deepseek.com.example.com", "https://example.com/deepseek",
        "https://api.deepseek.com/custom", "https://api.deepseek.com?route=custom", "http://api.deepseek.com")) {
      Provider provider = new Provider();
      provider.baseUrl = base;
      assertSame(provider, service.nativeProvider(provider, "anthropic", "/messages"));
    }
    Provider provider = new Provider();
    provider.baseUrl = "https://api.deepseek.com";
    provider.authType = "oauth";
    assertSame(provider, service.nativeProvider(provider, "anthropic", "/messages"));
  }
}
