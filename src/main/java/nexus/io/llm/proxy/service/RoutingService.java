package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;

public class RoutingService {
  private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
  private record Binding(String credential, long expires) { }
  private final ConcurrentHashMap<String, Binding> sessions = new ConcurrentHashMap<>();
  private final LongSupplier cooldownTime;

  public RoutingService() {
    this(System::currentTimeMillis);
  }

  RoutingService(LongSupplier cooldownTime) {
    this.cooldownTime = cooldownTime;
  }

  private Kv configuration() { return ProxyJson.object(ProxyJson.object(Aop.get(DesktopConfigService.class).get("")).get("routing")); }

  public Provider pickSession(List<Provider> candidates, String model, String strategy, String session) {
    Kv routing = configuration();
    if (session == null || session.isBlank() || !Boolean.TRUE.equals(routing.getBoolean("session-affinity"))) { return pick(candidates, model, strategy); }
    String key = model + "\n" + session;
    Binding binding = sessions.get(key);
    if (binding != null && binding.expires > System.currentTimeMillis()) {
      for (Provider candidate : candidates) {
        if (binding.credential.equals(candidate.id) && available(candidate, model)) { return candidate; }
      }
    }
    Provider selected = pick(candidates, model, strategy);
    if (sessions.size() >= 10000) { sessions.entrySet().removeIf(entry -> entry.getValue().expires <= System.currentTimeMillis()); }
    if (sessions.size() < 10000) { sessions.put(key, new Binding(selected.id, System.currentTimeMillis() + ttl(routing))); }
    return selected;
  }

  private long ttl(Kv routing) {
    String value = ProxyJson.text(routing, "session-affinity-ttl", "1h");
    try {
      long multiplier = value.endsWith("h") ? 3600000 : value.endsWith("m") ? 60000 : 1000;
      return Math.max(1000, Math.min(86400000, Long.parseLong(value.replaceFirst("[hms]$", "")) * multiplier));
    } catch (NumberFormatException ignored) { return 3600000; }
  }

  public Kv affinity(Kv input) {
    Kv result = Kv.create().set("observed_at", Instant.now().toString());
    if (!Boolean.TRUE.equals(configuration().getBoolean("session-affinity"))) { return result.set("status", "unsupported"); }
    Binding binding = sessions.get(input.getStr("model") + "\n" + input.getStr("session_id"));
    if (binding == null || binding.expires <= System.currentTimeMillis()) { return result.set("status", "unbound"); }
    List<Provider> providers = new ArrayList<>(Aop.get(SettingsService.class).snapshot().providers);
    providers.addAll(Aop.get(CredentialProviderService.class).providers());
    Provider selected = providers.stream().filter(provider -> provider.id.equals(binding.credential)
        && (input.getStr("provider").equals(provider.upstreamType) || input.getStr("provider").equals(provider.protocol))).findFirst().orElse(null);
    if (selected == null) { return result.set("status", "unbound"); }
    return result.set("status", "bound").set("auth_index", selected.id).set("disabled", !selected.enabled)
        .set("unavailable", !available(selected, input.getStr("model")));
  }
  private String key(Provider provider, String model) { return provider.id + "\n" + model; }
  public boolean available(Provider provider, String model) {
    return cooldowns.computeIfPresent(key(provider, model),
        (key, until) -> until <= cooldownTime.getAsLong() ? null : until) == null;
  }
  public void result(Provider provider, String model, int status, String retryAfter) {
    if (status >= 200 && status < 400) {
      // In-flight successes must not cancel a cooldown recorded by another request.
      // Routing resumes when the cooldown expires or an operator explicitly resets it.
      cooldowns.computeIfPresent(key(provider, model),
          (key, until) -> until <= cooldownTime.getAsLong() ? null : until);
      return;
    }
    if (Boolean.TRUE.equals(provider.metadata.getBoolean("disable-cooling")) || Boolean.TRUE.equals(provider.metadata.getBoolean("disable_cooling"))) { return; }
    if (status == 429 || status == 401 || status == 403 || status >= 500) {
      long seconds = status == 429 ? 60 : status >= 500 ? 5 : 300;
      try { if (retryAfter != null) { seconds = Math.max(1, Math.min(3600, Long.parseLong(retryAfter))); } }
      catch (NumberFormatException ignored) {
        try { seconds = Math.max(1, Math.min(3600, (ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - cooldownTime.getAsLong()) / 1000)); }
        catch (RuntimeException invalidDate) { }
      }
      cooldowns.put(key(provider, model), cooldownTime.getAsLong() + seconds * 1000);
    }
  }
  public Provider pick(List<Provider> candidates, String model, String strategy) {
    List<Provider> ready = candidates.stream().filter(provider -> available(provider, model)).toList();
    BusinessException.require(!ready.isEmpty(), 503, "PROVIDERS_COOLING", "Available credentials are cooling down");
    long priority = ready.stream().mapToLong(provider -> ProxyJson.number(provider.metadata, "priority", 0)).max().orElse(0);
    List<Provider> allPriorities = ready;
    ready = ready.stream().filter(provider -> ProxyJson.number(provider.metadata, "priority", 0) == priority).toList();
    try {
      for (NativePlugin plugin : Aop.get(PluginService.class).enabled("scheduler")) {
        List<Provider> eligible = plugin.supports("scheduler_across_priorities") ? allPriorities : ready;
        List<Kv> entries = eligible.stream().map(provider -> Kv.create().set("ID", provider.id).set("Provider", provider.upstreamType)
            .set("Priority", (int) ProxyJson.number(provider.metadata, "priority", 0)).set("Status", "active").set("Metadata", provider.metadata).set("Attributes", Kv.create())).toList();
        Kv decision = plugin.call("scheduler.pick", Kv.create().set("Plugin", plugin.registration.get("metadata")).set("Model", model)
            .set("Provider", ready.getFirst().upstreamType).set("Providers", ready.stream().map(provider -> provider.upstreamType).toList())
            .set("Candidates", entries).set("Options", Kv.create().set("Headers", Kv.create()).set("Metadata", Kv.create())));
        if (!Boolean.TRUE.equals(decision.getBoolean("Handled")) && !Boolean.TRUE.equals(decision.getBoolean("handled"))) { continue; }
        BusinessException.require(!Boolean.TRUE.equals(decision.getBoolean("Reject")) && !Boolean.TRUE.equals(decision.getBoolean("reject")),
            503, "SCHEDULER_REJECTED", ProxyJson.text(decision, "RejectReason", ProxyJson.text(decision, "reject_reason", "Plugin scheduler rejected the request")));
        String selected = ProxyJson.text(decision, "AuthID", decision.getStr("auth_id"));
        if (selected != null) {
          return eligible.stream().filter(provider -> provider.id.equals(selected)).findFirst()
              .orElseThrow(() -> new BusinessException(502, "SCHEDULER_SELECTION", "Plugin selected an unavailable credential"));
        }
        strategy = ProxyJson.text(decision, "DelegateBuiltin", ProxyJson.text(decision, "delegate_builtin", strategy));
      }
    } catch (BusinessException error) { throw error; }
    catch (Exception error) { throw new IllegalStateException("Plugin scheduler failed", error); }
    if ("fill-first".equals(strategy)) { return ready.getFirst(); }
    long total = ready.stream().mapToLong(provider -> Math.min(10000, Math.max(1, ProxyJson.number(provider.metadata, "weight", 1)))).sum();
    long slot = Math.floorMod(counters.computeIfAbsent(model, ignored -> new AtomicLong()).getAndIncrement(), total);
    for (Provider provider : ready) {
      slot -= Math.min(10000, Math.max(1, ProxyJson.number(provider.metadata, "weight", 1)));
      if (slot < 0) { return provider; }
    }
    return ready.getFirst();
  }
  public Kv reset(String index) throws Exception {
    Aop.get(CredentialService.class).resolve(index);
    List<String> models = new ArrayList<>();
    String prefix = index + "\n";
    for (String key : cooldowns.keySet()) {
      if (key.startsWith(prefix)) { models.add(key.substring(prefix.length())); cooldowns.remove(key); }
    }
    return Kv.create().set("status", "ok").set("auth_index", index).set("models", models);
  }
}
