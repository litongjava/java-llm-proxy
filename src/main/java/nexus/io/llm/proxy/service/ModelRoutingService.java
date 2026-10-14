package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;

public class ModelRoutingService {
  private List<Kv> aliases(Provider provider, String tool) {
    List<Kv> result = new ArrayList<>();
    if (provider.credentialName != null) {
      for (String key : List.of("model_aliases", "model-aliases")) {
        for (Object value : ProxyJson.list(provider.metadata.get(key))) { result.add(ProxyJson.object(value)); }
      }
      Kv oauth = ProxyJson.object(ProxyJson.object(Aop.get(DesktopConfigService.class).get("")).get("oauth"));
      for (Object value : ProxyJson.list(ProxyJson.object(oauth.get("model-alias")).get(provider.upstreamType))) { result.add(ProxyJson.object(value)); }
    } else {
      for (Object value : ProxyJson.list(provider.metadata.get("models"))) { result.add(ProxyJson.object(value)); }
    }
    List<Kv> scoped = new ArrayList<>();
    for (Object value : Aop.get(ToolScopeService.class).aliases(provider, tool, result)) {
      scoped.add(ProxyJson.object(value));
    }
    return scoped;
  }

  public String resolve(Provider provider, String requested) {
    return resolve(provider, requested, "");
  }

  public String resolve(Provider provider, String requested, String tool) {
    String prefix = ProxyJson.text(provider.metadata, "prefix", "");
    String model = requested;
    if (!prefix.isEmpty() && model.startsWith(prefix + "/")) { model = model.substring(prefix.length() + 1); }
    else if (!prefix.isEmpty()) {
      Kv routing = ProxyJson.object(ProxyJson.object(Aop.get(DesktopConfigService.class).get("")).get("routing"));
      if (Boolean.TRUE.equals(routing.getBoolean("force-model-prefix"))) { return null; }
    }
    for (Kv alias : aliases(provider, tool)) {
      if (model.equals(alias.getStr("alias"))) { model = alias.getStr("name"); break; }
    }
    // An OAuth credential with no known models must not become a catch-all route.
    if (model == null || ((provider.credentialName != null || !provider.models.isEmpty())
        && !provider.models.contains(model))) { return null; }
    if (!Aop.get(ModelCatalogService.class).allowed(provider.metadata, model)) { return null; }
    return model;
  }

  public Set<String> visible(Provider provider, List<String> originals) {
    return visible(provider, originals, "");
  }

  public Set<String> visible(Provider provider, List<String> originals, String tool) {
    Set<String> result = new LinkedHashSet<>();
    String prefix = ProxyJson.text(provider.metadata, "prefix", "");
    List<Kv> aliases = aliases(provider, tool);
    ModelCatalogService catalog = Aop.get(ModelCatalogService.class);
    for (String model : originals) {
      if (!catalog.allowed(provider.metadata, model)) { continue; }
      for (Kv alias : aliases) {
        if (!model.equals(alias.getStr("name")) || alias.getStr("alias") == null) { continue; }
        result.add(alias.getStr("alias"));
      }
      // Original model IDs remain available to every tool.
      result.add(model);
    }
    if (prefix.isEmpty()) { return result; }
    Set<String> prefixed = new LinkedHashSet<>();
    for (String model : result) { prefixed.add(prefix + "/" + model); }
    return prefixed;
  }
}
