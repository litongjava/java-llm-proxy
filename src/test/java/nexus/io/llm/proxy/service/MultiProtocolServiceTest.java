package nexus.io.llm.proxy.service;

import java.util.Map;
import nexus.io.llm.proxy.model.Provider;
import org.junit.Test;
import static org.junit.Assert.*;

public class MultiProtocolServiceTest {
  @Test public void selectsNativeEndpointAndFallsBackWithoutChangingProvider() {
    Provider provider = new Provider();
    provider.protocol = "responses";
    provider.baseUrl = "https://gateway.test/v1/";
    provider.upstreamType = "multi-protocol";
    provider.protocolEndpoints.putAll(Map.of("responses", "https://gateway.test/v1/",
        "anthropic", "https://gateway.test/anthropic"));
    MultiProtocolService service = new MultiProtocolService();
    assertTrue(MultiProtocolService.supports(provider, "anthropic"));
    assertFalse(MultiProtocolService.supports(provider, "gemini"));
    Provider nativeProvider = service.resolve(provider, "anthropic");
    assertEquals("anthropic", nativeProvider.protocol);
    assertEquals("https://gateway.test/anthropic", nativeProvider.baseUrl);
    Provider fallback = service.resolve(provider, "gemini");
    assertEquals("responses", fallback.protocol);
    assertEquals("https://gateway.test/v1", fallback.baseUrl);
    assertEquals("responses", provider.protocol);
    assertEquals("https://gateway.test/v1/", provider.baseUrl);
    nativeProvider.protocolEndpoints.clear();
    assertEquals(2, provider.protocolEndpoints.size());
  }

  @Test public void rejectsInvalidDefaultAndEndpoint() {
    Provider provider = new Provider();
    provider.upstreamType = "multi-protocol";
    assertInvalid(provider);
    provider.protocolEndpoints.put("anthropic", "https://gateway.test");
    assertInvalid(provider);
    provider.protocol = "anthropic";
    for (String url : new String[] { "", "relative", "ftp://gateway.test", "https://user:secret@gateway.test", "https://gateway.test?key=value" }) {
      provider.protocolEndpoints.put("anthropic", url);
      assertInvalid(provider);
    }
  }

  private void assertInvalid(Provider provider) {
    try { MultiProtocolService.validate(provider); fail("Expected invalid protocol configuration"); }
    catch (RuntimeException expected) { }
  }
}
