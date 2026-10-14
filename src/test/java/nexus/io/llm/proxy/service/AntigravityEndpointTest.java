package nexus.io.llm.proxy.service;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;

public class AntigravityEndpointTest {
  @Test
  public void generationUsesDailyUnlessExplicitlyOverridden() throws Exception {
    Path directory = Files.createTempDirectory("antigravity-endpoint-test");
    Path config = directory.resolve("config.yaml");
    Path credential = directory.resolve("antigravity.json");
    String previousConfig = EnvUtils.get("proxy.config");
    String previousAuth = EnvUtils.get("proxy.auth-dir");
    String previousBase = EnvUtils.get("proxy.oauth.antigravity.api-base-url");
    try {
      Files.writeString(config, "plugins: {enabled: false}\n");
      Files.writeString(credential, "{\"type\":\"antigravity\"}");
      EnvUtils.set("proxy.config", config.toString());
      EnvUtils.set("proxy.auth-dir", directory.toString());
      EnvUtils.set("proxy.oauth.antigravity.api-base-url", null);
      Provider provider = antigravity();
      OAuthTransportService transport = new OAuthTransportService();
      assertEquals("https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent",
          transport.url(provider, "unused", ":generateContent", false));
      assertEquals("https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse",
          transport.url(provider, "unused", ":streamGenerateContent", true));

      EnvUtils.set("proxy.oauth.antigravity.api-base-url", "http://localhost:18780/configured/v1internal/");
      assertEquals("http://localhost:18780/configured/v1internal", antigravity().baseUrl);
      Files.writeString(credential, JsonUtils.toJson(Kv.create().set("type", "antigravity")
          .set("base_url", "http://localhost:18781/credential/v1internal/")));
      assertEquals("http://localhost:18781/credential/v1internal", antigravity().baseUrl);
    } finally {
      EnvUtils.set("proxy.config", previousConfig);
      EnvUtils.set("proxy.auth-dir", previousAuth);
      EnvUtils.set("proxy.oauth.antigravity.api-base-url", previousBase);
      Files.deleteIfExists(credential);
      Files.deleteIfExists(config);
      Files.deleteIfExists(directory);
    }
  }

  private Provider antigravity() {
    return new CredentialProviderService().providers().stream()
        .filter(provider -> "antigravity".equals(provider.upstreamType)).findFirst().orElseThrow();
  }
}
