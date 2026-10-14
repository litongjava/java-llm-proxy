package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class ToolAliasRoutingTest {
  @Test
  public void sameAliasRoutesIndependentlyAndOriginalModelsRemainVisible() throws Exception {
    Path config = Files.createTempFile("tool-alias-test", ".yaml");
    String previous = EnvUtils.get("proxy.config");
    Provider provider = new Provider();
    provider.upstreamType = "codex";
    provider.protocol = "responses";
    provider.models = List.of("deepseek-flash", "deepseek-v4-pro");
    provider.metadata = Kv.create().set("api-key", "test-key").set("base-url", "https://example.invalid/v1");
    String identity = new ToolScopeService().identity(provider);
    try {
      EnvUtils.set("proxy.config", config.toString());
      Files.writeString(config, """
          tool-model-aliases:
            claude-desktop:
              providers:
                - section: codex
                  identity: %s
                  models: [{name: deepseek-flash, alias: shared-alias}]
            codex:
              providers:
                - section: codex
                  identity: %s
                  models: [{name: deepseek-v4-pro, alias: shared-alias}]
          """.formatted(identity, identity));
      ModelRoutingService routing = new ModelRoutingService();
      assertEquals("deepseek-flash", routing.resolve(provider, "shared-alias", "claude-desktop"));
      assertEquals("deepseek-v4-pro", routing.resolve(provider, "shared-alias", "codex"));
      assertNull(routing.resolve(provider, "shared-alias", "opencode"));
      assertNull(routing.resolve(provider, "shared-alias"));
      assertEquals("deepseek-flash", routing.resolve(provider, "deepseek-flash", "codex"));
      assertEquals(2, routing.visible(provider, provider.models).size());
      assertEquals(3, routing.visible(provider, provider.models, "codex").size());
      provider.metadata.set("api-key", "different-provider");
      assertNull(routing.resolve(provider, "shared-alias", "codex"));
    } finally {
      EnvUtils.set("proxy.config", previous);
      Files.deleteIfExists(config);
    }
  }
}
