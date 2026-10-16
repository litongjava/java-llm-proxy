package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.CredentialInfo;
import nexus.io.llm.proxy.model.Provider;
import org.junit.Test;

public class NativeCatalogLifecycleTest {
  private static class Catalog extends NativeChannelService {
    long time = 1000000;
    int calls;
    boolean fail;
    boolean empty;
    @Override
    protected long now() { return time; }
    @Override
    public String baseUrl(String provider) { return "https://example.invalid/v1"; }
    @Override
    protected List<Kv> discoverModels(String provider, Kv credential) throws Exception {
      calls++;
      if (fail) { throw new IllegalStateException("mock catalog outage"); }
      return empty ? List.of() : List.of(Kv.create().set("id", "model-test").set("details", Kv.create().set("supports", true)));
    }
  }

  @Test
  public void cacheRefreshesPreservesBriefOutagesAndAcceptsAuthoritativeEmptyCatalog() throws Exception {
    Catalog catalog = new Catalog();
    Kv credential = Kv.create().set("access_token", "test-token");
    List<Kv> models = catalog.models("minimax", credential);
    ProxyJson.object(models.getFirst().get("details")).set("supports", false);
    credential.set("models", models);
    assertEquals(Boolean.TRUE, ProxyJson.object(catalog.models("minimax", credential).getFirst().get("details")).getBoolean("supports"));
    assertEquals(1, catalog.calls);
    catalog.time += 61000;
    catalog.fail = true;
    assertEquals(1, catalog.models("minimax", credential).size());
    assertEquals(2, catalog.calls);
    catalog.models("minimax", credential);
    assertEquals(2, catalog.calls);
    catalog.time += 6000;
    catalog.fail = false;
    catalog.empty = true;
    assertTrue(catalog.models("minimax", credential).isEmpty());
    assertEquals(3, catalog.calls);
    assertTrue(catalog.models("minimax", credential).isEmpty());
  }

  @Test
  public void tokenAndChannelChangesCannotReuseOldCatalogAndStaleDataEventuallyExpires() throws Exception {
    Catalog catalog = new Catalog();
    Kv credential = Kv.create().set("access_token", "old-token").set("zcode_channel", "start-plan");
    catalog.models("zcode", credential);
    credential.set("access_token", "rotated-token");
    catalog.models("zcode", credential);
    credential.set("zcode_channel", "coding-plan");
    catalog.models("zcode", credential);
    assertEquals(3, catalog.calls);
    catalog.time += 301000;
    catalog.fail = true;
    try {
      catalog.models("zcode", credential);
      fail("A stale catalog must expire");
    } catch (IllegalStateException expected) {
      assertEquals("mock catalog outage", expected.getMessage());
    }
  }

  @Test
  public void concurrentDiscoveryForOneAccountMakesOneUpstreamCall() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Catalog catalog = new Catalog() {
      @Override
      protected List<Kv> discoverModels(String provider, Kv credential) throws Exception {
        started.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return super.discoverModels(provider, credential);
      }
    };
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<List<Kv>> first = executor.submit(() -> catalog.models("minimax", Kv.create().set("access_token", "same")));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Future<List<Kv>> second = executor.submit(() -> catalog.models("minimax", Kv.create().set("access_token", "same")));
      release.countDown();
      assertEquals(1, first.get(5, TimeUnit.SECONDS).size());
      assertEquals(1, second.get(5, TimeUnit.SECONDS).size());
      assertEquals(1, catalog.calls);
    }
  }

  @Test
  public void expiredCredentialIsRefreshedBeforeDiscoveryAndBrokenAccountDoesNotHideOthers() {
    class Store extends CredentialService {
      int refreshes;
      @Override
      public List<CredentialInfo> list() {
        CredentialInfo broken = new CredentialInfo();
        broken.name = "broken.json";
        broken.type = "minimax";
        CredentialInfo usable = new CredentialInfo();
        usable.name = "usable.json";
        usable.type = "minimax";
        usable.auth_index = "test-index";
        return List.of(broken, usable);
      }
      @Override
      public Kv read(String name) { return Kv.create().set("type", "minimax").set("access_token", "expired"); }
      @Override
      public Kv refreshed(String name, boolean force) {
        refreshes++;
        if ("broken.json".equals(name)) { throw new IllegalStateException("mock expired session"); }
        return Kv.create().set("type", "minimax").set("access_token", "fresh");
      }
    }
    Store store = new Store();
    Catalog catalog = new Catalog() {
      @Override
      protected List<Kv> discoverModels(String provider, Kv credential) throws Exception {
        assertEquals("fresh", credential.getStr("access_token"));
        return super.discoverModels(provider, credential);
      }
    };
    CredentialProviderService providers = new CredentialProviderService() {
      @Override
      protected CredentialService credentials() { return store; }
      @Override
      protected NativeChannelService channels() { return catalog; }
    };
    List<Provider> result = providers.providers();
    assertEquals(2, store.refreshes);
    assertEquals(1, result.size());
    assertEquals("usable.json", result.getFirst().credentialName);
    assertEquals("fresh", result.getFirst().metadata.getStr("access_token"));
    assertEquals(List.of("model-test"), result.getFirst().models);
  }
}
