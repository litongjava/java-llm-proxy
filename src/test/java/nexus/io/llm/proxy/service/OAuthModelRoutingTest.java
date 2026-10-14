package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class OAuthModelRoutingTest {
  @Test
  public void claudeUsesAntigravityAndCodexRetainsItsOwnModels() throws Exception {
    Path directory = Files.createTempDirectory("oauth-routing-test");
    Path config = directory.resolve("config.yaml");
    String previousConfig = EnvUtils.get("proxy.config");
    String previousAuth = EnvUtils.get("proxy.auth-dir");
    try {
      Files.writeString(config, "plugins: {enabled: false}\n");
      Files.writeString(directory.resolve("codex.json"), "{\"type\":\"codex\"}");
      Files.writeString(directory.resolve("antigravity.json"), "{\"type\":\"antigravity\"}");
      EnvUtils.set("proxy.config", config.toString());
      EnvUtils.set("proxy.auth-dir", directory.toString());
      List<Provider> providers = new CredentialProviderService().providers();
      Provider codex = providers.stream().filter(p -> "codex".equals(p.upstreamType)).findFirst().orElseThrow();
      Provider antigravity = providers.stream().filter(p -> "antigravity".equals(p.upstreamType)).findFirst().orElseThrow();
      ModelRoutingService routing = new ModelRoutingService();
      assertFalse(codex.models.isEmpty());
      assertEquals(codex.models.size(), codex.models.stream().distinct().count());
      assertNull(routing.resolve(codex, "claude-sonnet-4-6"));
      assertEquals("claude-sonnet-4-6", routing.resolve(antigravity, "claude-sonnet-4-6"));
      ModelCatalogService catalog = new ModelCatalogService();
      for (String plan : List.of("free", "team", "plus", "pro")) {
        for (Object value : catalog.definitions("codex-" + plan)) {
          String id = ProxyJson.object(value).getStr("id");
          assertEquals(id, routing.resolve(codex, id));
        }
      }
      List<?> listed = ProxyJson.list(catalog.credentialModels("codex.json").get("models"));
      assertEquals(codex.models.size(), listed.size());

      codex.models.clear();
      assertNull(routing.resolve(codex, "claude-sonnet-4-6"));
      assertNull(routing.resolve(codex, "unknown-model"));
      Provider api = new Provider();
      api.metadata = Kv.create();
      assertEquals("custom-model", routing.resolve(api, "custom-model"));
    } finally {
      EnvUtils.set("proxy.config", previousConfig);
      EnvUtils.set("proxy.auth-dir", previousAuth);
      Files.deleteIfExists(directory.resolve("codex.json"));
      Files.deleteIfExists(directory.resolve("antigravity.json"));
      Files.deleteIfExists(config);
      Files.deleteIfExists(directory);
    }
  }
}
