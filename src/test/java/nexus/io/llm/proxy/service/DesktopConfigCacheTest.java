package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.tio.utils.environment.EnvUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DesktopConfigCacheTest {
  private Path directory;
  private Path file;
  private String previous;
  private DesktopConfigService service;

  @Before public void setup() throws Exception {
    previous = EnvUtils.get("proxy.config");
    directory = Files.createTempDirectory("desktop-config-cache");
    file = directory.resolve("config.yaml");
    EnvUtils.set("proxy.config", file.toString());
    service = new DesktopConfigService();
  }

  @After public void cleanup() throws Exception {
    EnvUtils.set("proxy.config", previous);
    try (var paths = Files.list(directory)) {
      for (Path path : paths.toList()) { Files.deleteIfExists(path); }
    }
    Files.deleteIfExists(directory);
  }

  private Object field(String name) throws Exception {
    Field field = DesktopConfigService.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(service);
  }

  @Test public void cachesParsingAndIsolatesReturnedSubtrees() throws Exception {
    Files.writeString(file, "tool-model-aliases:\n  codex:\n    providers:\n      - section: codex\n        models: [one]\n");
    Kv first = (Kv) service.section("tool-model-aliases", "codex");
    Object parsed = field("cachedConfig");
    List<?> providers = (List<?>) first.get("providers");
    ((Kv) providers.getFirst()).set("models", List.of("changed"));
    Kv next = (Kv) service.section("tool-model-aliases", "codex");
    assertEquals(List.of("one"), ((Kv) ((List<?>) next.get("providers")).getFirst()).get("models"));
    assertSame(parsed, field("cachedConfig"));
    assertNull(service.section("missing"));
    ((Kv) service.get("")).clear();
    assertNotNull(service.section("tool-model-aliases", "codex"));
  }

  @Test public void reloadsExternalEditsAndPreservedMetadata() throws Exception {
    Files.writeString(file, "routing: {strategy: first}\n");
    assertEquals("first", ((Kv) service.section("routing")).getStr("strategy"));
    FileTime stamp = Files.getLastModifiedTime(file);
    Files.writeString(file, "routing: {strategy: other}\n");
    Files.setLastModifiedTime(file, stamp);
    Field check = DesktopConfigService.class.getDeclaredField("lastContentCheck");
    check.setAccessible(true);
    check.setLong(service, System.nanoTime() - 2_000_000_000L);
    assertEquals("other", ((Kv) service.section("routing")).getStr("strategy"));
    Files.writeString(file, "routing: {strategy: external-longer}\n");
    assertEquals("external-longer", ((Kv) service.section("routing")).getStr("strategy"));
    Files.delete(file);
    assertNull(service.section("routing"));
    Files.writeString(file, "routing: {strategy: recreated}\n");
    assertEquals("recreated", ((Kv) service.section("routing")).getStr("strategy"));
  }

  @Test public void savesInvalidateImmediatelyAndRefreshSettings() throws Exception {
    service.replaceYaml("routing: {strategy: first}\n");
    ProxySettings first = service.refresh(new ProxySettings());
    assertEquals("first", first.routing);
    assertSame(first, service.refresh(first));
    service.mutate("routing/strategy", "PUT", "second");
    assertEquals("second", service.get("routing/strategy"));
    assertEquals("second", service.refresh(first).routing);
    assertEquals("first", first.routing);
    service.replaceYaml("routing: {strategy: third}\n");
    assertEquals("third", service.get("routing/strategy"));
  }

  @Test public void concurrentReadersCannotMutateCachedData() throws Exception {
    service.replaceYaml("routing: {strategy: stable}\n");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 40; i++) {
        jobs.add(executor.submit(() -> {
          Kv routing = (Kv) service.section("routing");
          assertEquals("stable", routing.getStr("strategy"));
          routing.set("strategy", "changed");
        }));
      }
      for (var job : jobs) { job.get(); }
    }
    assertEquals("stable", service.get("routing/strategy"));
  }
}
