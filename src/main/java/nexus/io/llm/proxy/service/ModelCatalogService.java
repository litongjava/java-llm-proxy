package nexus.io.llm.proxy.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.tio.utils.json.JsonUtils;

public class ModelCatalogService {
  private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);
  private static volatile Kv catalog;
  private static final Object CATALOG_LOCK = new Object();
  public ModelCatalogService() {
    ensureCatalog();
  }

  private static void ensureCatalog() {
    if (catalog != null) { return; }
    synchronized (CATALOG_LOCK) {
      if (catalog != null) { return; }
      try (InputStream source = ModelCatalogService.class.getResourceAsStream("/catalog/models.json");
          InputStream devin = ModelCatalogService.class.getResourceAsStream("/catalog/devin_models.json")) {
        Kv initial = ProxyJson.parse(new String(source.readAllBytes(), StandardCharsets.UTF_8));
        initial.set(ProxyJson.parse(new String(devin.readAllBytes(), StandardCharsets.UTF_8)));
        catalog = initial;
      } catch (Exception error) { throw new IllegalStateException("Cannot load model catalog", error); }
      Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "model-catalog-refresh");
        thread.setDaemon(true);
        return thread;
      }).scheduleWithFixedDelay(ModelCatalogService::refreshRemote, 0, 3, TimeUnit.HOURS);
    }
  }

  static boolean installCatalog(String json) {
    try {
      Kv replacement = ProxyJson.parse(json);
      for (String plan : List.of("free", "team", "plus", "pro")) {
        List<?> models = ProxyJson.list(replacement.get("codex-" + plan));
        if (models.isEmpty()) { return false; }
      }
      for (Object key : replacement.keySet()) {
        Object value = replacement.get(key);
        if (!(value instanceof List<?>)) { return false; }
        for (Object model : (List<?>) value) {
          if (ProxyJson.object(model).getStr("id") == null) { return false; }
        }
      }
      synchronized (CATALOG_LOCK) {
        Kv previous = catalog;
        // The Devin catalog is maintained independently of models.json.
        if (previous != null && previous.containsKey("devin")) { replacement.set("devin", previous.get("devin")); }
        catalog = replacement;
      }
      return true;
    } catch (RuntimeException error) { return false; }
  }

  private static void refreshRemote() {
    OkHttpClient client = new OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS).build();
    if (!refreshFromUrls(client, List.of("https://raw.githubusercontent.com/router-for-me/models/refs/heads/main/models.json",
        "https://models.router-for.me/models.json"))) {
      log.warn("Model catalog refresh failed; keeping the last valid catalog");
    }
  }

  static boolean refreshFromUrls(OkHttpClient client, List<String> urls) {
    for (String url : urls) {
      try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
        if (!response.isSuccessful() || response.body() == null) { continue; }
        try (InputStream input = response.body().byteStream()) {
          byte[] data = input.readNBytes(4 * 1024 * 1024 + 1);
          if (data.length <= 4 * 1024 * 1024 && installCatalog(new String(data, StandardCharsets.UTF_8))) { return true; }
        }
      } catch (Exception error) {
        log.debug("Model catalog refresh failed from {}", url, error);
      }
    }
    return false;
  }
  public List<?> definitions(String provider) throws Exception {
    ManagementParameterValidator.identifier(provider);
    NativePlugin plugin = Aop.get(PluginService.class).provider(provider, true);
    if (plugin != null && plugin.supports("model_provider")) { return ProxyJson.list(plugin.call("model.static", Kv.create()).get("Models")); }
    if (Aop.get(NativeChannelService.class).supports(provider)) {
      return Aop.get(NativeChannelService.class).models(provider, null);
    }
    Kv catalog = ModelCatalogService.catalog;
    String channel = switch (provider) { case "vertex" -> "gemini"; case "kimi-ai" -> "kimi"; default -> provider; };
    // Codex's bundled catalog is split by plan, while OAuth credentials use type "codex".
    if ("codex".equals(channel) && ProxyJson.list(catalog.get(channel)).isEmpty()) {
      Map<String, Kv> models = new LinkedHashMap<>();
      for (String plan : List.of("free", "team", "plus", "pro")) {
        for (Object value : ProxyJson.list(catalog.get("codex-" + plan))) {
          Kv model = ProxyJson.object(value);
          if (model.getStr("id") != null) { models.putIfAbsent(model.getStr("id"), model); }
        }
      }
      models.putIfAbsent("gpt-live-1-codex", Kv.create().set("id", "gpt-live-1-codex").set("object", "model").set("owned_by", "openai"));
      return new ArrayList<>(models.values());
    }
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
    } else if (Aop.get(NativeChannelService.class).supports(provider)) {
      credential = Aop.get(CredentialService.class).refreshed(name, false);
      models = Aop.get(NativeChannelService.class).models(provider, credential);
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
    Kv oauth = ProxyJson.object(Aop.get(DesktopConfigService.class).section("oauth"));
    List<?> exclusions = ProxyJson.list(credential.get("excluded_models"));
    if (exclusions.isEmpty()) { exclusions = ProxyJson.list(credential.get("excluded-models")); }
    if (exclusions.isEmpty()) { exclusions = ProxyJson.list(ProxyJson.object(oauth.get("excluded-models")).get(credential.get("type"))); }
    for (Object value : exclusions) {
      if (!(value instanceof String pattern) || pattern.isBlank()) { continue; }
      String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
      if (model.matches(regex)) { return false; }
    }
    return true;
  }
}
