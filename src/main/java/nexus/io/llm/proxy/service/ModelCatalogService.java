package nexus.io.llm.proxy.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.tio.utils.json.JsonUtils;

public class ModelCatalogService {
  private final Kv catalog;
  public ModelCatalogService() {
    try (InputStream source = getClass().getResourceAsStream("/catalog/models.json")) {
      catalog = ProxyJson.parse(new String(source.readAllBytes(), StandardCharsets.UTF_8));
      try (InputStream devin = getClass().getResourceAsStream("/catalog/devin_models.json")) {
        catalog.set(ProxyJson.parse(new String(devin.readAllBytes(), StandardCharsets.UTF_8)));
      }
    } catch (Exception error) { throw new IllegalStateException("Cannot load model catalog", error); }
  }
  public List<?> definitions(String provider) throws Exception {
    ManagementParameterValidator.identifier(provider);
    NativePlugin plugin = Aop.get(PluginService.class).provider(provider, true);
    if (plugin != null && plugin.supports("model_provider")) { return ProxyJson.list(plugin.call("model.static", Kv.create()).get("Models")); }
    String channel = switch (provider) { case "vertex" -> "gemini"; case "kimi-ai" -> "kimi"; default -> provider; };
    return ProxyJson.list(catalog.get(channel));
  }
  public Kv credentialModels(String name) throws Exception {
    Kv credential = Aop.get(CredentialService.class).read(name);
    String provider = credential.getStr("type");
    List<?> models = ProxyJson.list(credential.get("models"));
    NativePlugin plugin = Aop.get(PluginService.class).provider(provider, true);
    if (plugin != null && plugin.supports("model_provider")) {
      Kv discovered = plugin.call("model.for_auth", Kv.create().set("AuthID", name).set("AuthProvider", provider)
          .set("StorageJSON", PluginHostCallbacks.encode(JsonUtils.toJson(credential).getBytes(StandardCharsets.UTF_8)))
          .set("Metadata", credential).set("Attributes", Kv.create()).set("Host", Kv.create()));
      models = ProxyJson.list(discovered.get("Models"));
      Kv update = ProxyJson.object(discovered.get("AuthUpdate"));
      if (!update.isEmpty()) {
        if (update.getStr("FileName") == null) { update.set("FileName", name); }
        Aop.get(OAuthService.class).savePluginAuth(update);
      }
    } else if (models.isEmpty()) { models = definitions(provider); }
    List<Kv> result = new ArrayList<>();
    for (Object value : models) {
      Kv item = value instanceof String ? Kv.create().set("id", value) : ProxyJson.object(value);
      if (!item.containsKey("id")) { item.set("id", ProxyJson.text(item, "ID", item.getStr("name"))); }
      result.add(item);
    }
    return Kv.create().set("models", result);
  }

  public boolean allowed(Kv credential, String model) {
    Kv oauth = ProxyJson.object(ProxyJson.object(Aop.get(DesktopConfigService.class).get("")).get("oauth"));
    List<Object> exclusions = new ArrayList<>(ProxyJson.list(ProxyJson.object(oauth.get("excluded-models")).get(credential.get("type"))));
    exclusions.addAll(ProxyJson.list(credential.get("excluded_models")));
    exclusions.addAll(ProxyJson.list(credential.get("excluded-models")));
    for (Object value : exclusions) {
      String pattern = value.toString();
      String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
      if (model.matches(regex)) { return false; }
    }
    return true;
  }
}
