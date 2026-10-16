package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.jfinal.kit.Kv;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ApiKey;
import nexus.io.llm.proxy.model.ModelAlias;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.validation.ProxyParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import nexus.io.tio.utils.validator.ParameterValidator;

public class SettingsService {
  private final AtomicLong sequence = new AtomicLong();
  private volatile ProxySettings current = Aop.get(StorageService.class).settings();

  public ProxySettings snapshot() {
    refreshDesktop();
    return JsonUtils.parse(JsonUtils.toJson(current), ProxySettings.class);
  }

  public ProxySettings publicSettings() {
    ProxySettings settings = snapshot();
    for (Provider provider : settings.providers) {
      if (provider.apiKey != null && !provider.apiKey.isEmpty()) {
        provider.apiKey = "********";
      }
      provider.headers = HeaderService.redact(provider.headers);
    }
    for (ApiKey key : settings.apiKeys) {
      key.key = "********";
    }
    return settings;
  }

  public synchronized ProxySettings save(ProxySettings input) {
    ProxyParameterValidator.settings(input);
    for (Provider provider : input.providers) {
      Provider old = current.providers.stream().filter(p -> p.id.equals(provider.id)).findFirst().orElse(null);
      if (provider.id == null || provider.id.isBlank()) {
        provider.id = String.valueOf(SnowflakeIdUtils.id());
      }
      if ("********".equals(provider.apiKey)) {
        provider.apiKey = old == null ? null : old.apiKey;
      }
      if (provider.headers == null) {
        provider.headers = Kv.create();
      }
      if (old != null && old.headers != null) {
        for (Object raw : provider.headers.keySet()) {
          String name = raw.toString();
          if ("[REDACTED]".equals(provider.headers.get(name))) {
            provider.headers.set(name, old.headers.get(name));
          }
        }
      }
    }
    for (ApiKey key : input.apiKeys) {
      ParameterValidator.text(key.name, "key name", 120);
      if ("********".equals(key.key)) {
        key.key = current.apiKeys.stream().filter(k -> k.id.equals(key.id)).map(k -> k.key).findFirst().orElse(null);
      }
      ParameterValidator.text(key.key, "key", 512);
      if (key.id == null) {
        key.id = String.valueOf(SnowflakeIdUtils.id());
      }
    }
    for (ModelAlias alias : input.aliases) {
      ParameterValidator.text(alias.name, "alias", 256);
      ParameterValidator.text(alias.model, "model", 256);
    }
    Aop.get(StorageService.class).saveSettings(input);
    current = JsonUtils.parse(JsonUtils.toJson(input), ProxySettings.class);
    return publicSettings();
  }

  public synchronized Provider saveProvider(Provider input) {
    ProxyParameterValidator.provider(input);
    ProxySettings settings = snapshot();
    if (input.id == null || input.id.isBlank()) {
      input.id = String.valueOf(SnowflakeIdUtils.id());
    }
    settings.providers.removeIf(provider -> provider.id.equals(input.id));
    settings.providers.add(input);
    save(settings);
    return publicSettings().providers.stream().filter(p -> p.id.equals(input.id)).findFirst().orElseThrow();
  }

  public synchronized void deleteProvider(String id) {
    ProxySettings settings = snapshot();
    settings.providers.removeIf(provider -> provider.id.equals(id));
    settings.aliases.removeIf(alias -> id.equals(alias.providerId));
    save(settings);
  }

  public synchronized ApiKey createKey(String name) {
    ParameterValidator.text(name, "name", 120);
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    ApiKey key = new ApiKey();
    key.id = String.valueOf(SnowflakeIdUtils.id());
    key.name = name;
    key.key = "sk-proxy-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    ProxySettings settings = snapshot();
    settings.apiKeys.add(key);
    save(settings);
    return key;
  }

  public String authenticate(String token, boolean management) {
    refreshDesktop();
    if (management) {
      String configured = EnvUtils.get("proxy.management-token");
      return matches(configured, token) ? "management" : null;
    }
    for (ApiKey key : current.apiKeys) {
      if (key.enabled && matches(key.key, token)) {
        return key.name;
      }
    }
    return matches(EnvUtils.get("proxy.api-key"), token) ? "environment" : null;
  }

  public static boolean matches(String expected, String actual) {
    return expected != null && !expected.isBlank() && actual != null
        && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  public Selection select(String requestedModel, String protocol) {
    return select(requestedModel, protocol, null, null, null);
  }

  public Selection select(String requestedModel, String protocol, String forcedProvider, String authId,
      String session) {
    return select(requestedModel, protocol, forcedProvider, authId, session, Set.of());
  }

  public Selection select(String requestedModel, String protocol, String forcedProvider, String authId, String session,
      Set<String> excluded) {
    return select(requestedModel, protocol, forcedProvider, authId, session, excluded, "");
  }

  public Selection select(String requestedModel, String protocol, String forcedProvider, String authId, String session,
      Set<String> excluded, String tool) {
    refreshDesktop();
    String model = requestedModel;
    String providerId = null;
    String effort = null;
    for (ModelAlias alias : current.aliases) {
      if (alias.name.equals(requestedModel) && tool.equals(alias.tool)) {
        model = alias.model;
        providerId = alias.providerId;
        effort = alias.reasoningEffort;
        break;
      }
    }
    String resolved = model;
    String selectedId = providerId;
    List<Provider> allProviders = new ArrayList<>(current.providers);
    allProviders.addAll(Aop.get(CredentialProviderService.class).providers());
    allProviders.addAll(Aop.get(PluginService.class).staticProviders());
    ModelRoutingService models = Aop.get(ModelRoutingService.class);
    List<Provider> candidates = allProviders.stream()
        .filter(p -> p.enabled && !excluded.contains(p.id)
            && (selectedId == null || selectedId.isBlank() || p.id.equals(selectedId))
            && (forcedProvider == null || forcedProvider.isBlank() || forcedProvider.equals(p.upstreamType)
                || forcedProvider.equals(p.protocol))
            && (authId == null || authId.isBlank() || authId.equals(p.id) || authId.equals(p.credentialName))
            && models.resolve(p, resolved, tool) != null)
        .toList();
    BusinessException.require(!candidates.isEmpty(), 503, "NO_PROVIDER",
        "No enabled provider for this model and protocol");
    List<Provider> nativeCandidates = candidates.stream().filter(provider -> provider.protocol.equals(protocol))
        .toList();
    if (!nativeCandidates.isEmpty()) {
      candidates = nativeCandidates;
    }
    Provider chosen = Aop.get(RoutingService.class).pickSession(candidates, model, current.routing, session);
    return new Selection(Aop.get(CredentialProviderService.class).authorize(chosen),
        models.resolve(chosen, model, tool), effort);
  }

  public record Selection(Provider provider, String model, String reasoningEffort) {
  }

  private synchronized void refreshDesktop() {
    current = Aop.get(DesktopConfigService.class).refresh(current);
  }
}
