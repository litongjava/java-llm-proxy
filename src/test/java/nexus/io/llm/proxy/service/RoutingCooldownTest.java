package nexus.io.llm.proxy.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import nexus.io.llm.proxy.model.Provider;

public class RoutingCooldownTest {
  private final AtomicLong now = new AtomicLong(1700000000000L);
  private final RoutingService service = new RoutingService(now::get);
  private final Provider provider = provider("codex-account");

  @Test
  public void inFlightSuccessCannotClearNewRateLimit() {
    // Both requests were eligible before one of them received a rate limit.
    assertTrue(service.available(provider, "model-a"));
    assertTrue(service.available(provider, "model-a"));
    service.result(provider, "model-a", 429, "60");
    service.result(provider, "model-a", 200, null);
    assertFalse(service.available(provider, "model-a"));
    now.addAndGet(59999);
    assertFalse(service.available(provider, "model-a"));
    now.incrementAndGet();
    assertTrue(service.available(provider, "model-a"));
  }

  @Test
  public void successesPreserveAuthenticationAndTransientCooldowns() {
    for (int failure : new int[] {401, 403, 500, 502, 503}) {
      String model = "model-" + failure;
      service.result(provider, model, failure, null);
      service.result(provider, model, 204, null);
      service.result(provider, model, 302, null);
      assertFalse(service.available(provider, model));
    }
    now.addAndGet(5000);
    assertTrue(service.available(provider, "model-503"));
    assertFalse(service.available(provider, "model-401"));
    now.addAndGet(295000);
    assertTrue(service.available(provider, "model-401"));
  }

  @Test
  public void successAfterExpiryAllowsRecoveryAndSubsequentCooldown() {
    service.result(provider, "model-a", 429, "1");
    now.addAndGet(1000);
    service.result(provider, "model-a", 200, null);
    assertTrue(service.available(provider, "model-a"));
    service.result(provider, "model-a", 429, "60");
    service.result(provider, "model-a", 200, null);
    assertFalse(service.available(provider, "model-a"));
  }

  @Test
  public void cooldownIsScopedToCredentialAndModel() {
    service.result(provider, "model-a", 429, null);
    assertTrue(service.available(provider, "model-b"));
    assertTrue(service.available(provider("other-account"), "model-a"));
    assertFalse(service.available(provider, "model-a"));
  }

  @Test
  public void httpDateRetryAfterRemainsEffective() {
    String retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(
        Instant.ofEpochMilli(now.get() + 120000).atZone(ZoneOffset.UTC));
    service.result(provider, "model-a", 429, retryAfter);
    service.result(provider, "model-a", 200, null);
    now.addAndGet(119999);
    assertFalse(service.available(provider, "model-a"));
    now.incrementAndGet();
    assertTrue(service.available(provider, "model-a"));
  }

  @Test
  public void disabledCoolingDoesNotScheduleNewCooldowns() {
    for (String setting : new String[] {"disable-cooling", "disable_cooling"}) {
      Provider uncoolable = provider(setting);
      uncoolable.metadata.set(setting, true);
      service.result(uncoolable, "model-a", 429, "60");
      assertTrue(service.available(uncoolable, "model-a"));
    }
  }

  private static Provider provider(String id) {
    Provider value = new Provider();
    value.id = id;
    value.upstreamType = "codex";
    return value;
  }
}
