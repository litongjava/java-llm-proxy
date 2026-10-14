package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.concurrent.TimeUnit;

public class ManagementService {
  private final long started = System.currentTimeMillis();

  public Kv status() {
    ProxySettings settings = Aop.get(SettingsService.class).publicSettings();
    return Kv.create().set("name", "Java LLM Proxy").set("running", true)
        .set("uptimeMs", System.currentTimeMillis() - started)
        .set("port", EnvUtils.getInt("server.port", 8318))
        .set("environment", EnvUtils.appEnv()).set("providers", settings.providers.size())
        .set("enabledProviders", settings.providers.stream().filter(p -> p.enabled).count());
  }

  public RequestRecord detail(String id) {
    RequestRecord record = Aop.get(StorageService.class).detail(id);
    BusinessException.require(record != null, 404, "NOT_FOUND", "Request not found");
    return record;
  }

  public Kv models() throws Exception {
    return models("");
  }

  public Kv models(String tool) throws Exception {
    ProxySettings settings = Aop.get(SettingsService.class).snapshot();
    Set<String> names = new LinkedHashSet<>();
    ModelRoutingService modelRouting = Aop.get(ModelRoutingService.class);
    settings.providers.stream().filter(p -> p.enabled).forEach(p -> names.addAll(modelRouting.visible(p, p.models, tool)));
    settings.aliases.stream().filter(alias -> tool.equals(alias.tool)).forEach(alias -> names.add(alias.name));
    Aop.get(PluginService.class).staticProviders().forEach(provider -> names.addAll(provider.models));
    CredentialService credentials = Aop.get(CredentialService.class);
    ModelCatalogService catalog = Aop.get(ModelCatalogService.class);
    for (Provider provider : Aop.get(CredentialProviderService.class).providers()) {
      Kv credential = credentials.read(provider.credentialName);
      List<String> originals = new ArrayList<>();
      for (Object value : ProxyJson.list(catalog.credentialModels(provider.credentialName).get("models"))) {
        String id = ProxyJson.object(value).getStr("id");
        if (id != null) { originals.add(id); }
      }
      names.addAll(modelRouting.visible(provider, originals, tool));
    }
    List<Kv> models = new ArrayList<>();
    for (String name : names) {
      models.add(Kv.create().set("id", name).set("object", "model").set("created", 0).set("owned_by", "java-llm-proxy"));
    }
    return Kv.create().set("object", "list").set("data", models);
  }

  public Kv health(String id) throws Exception {
    Provider provider = Aop.get(SettingsService.class).snapshot().providers.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
    BusinessException.require(provider != null, 404, "NOT_FOUND", "Provider not found");
    String url = provider.baseUrl + ("gemini".equals(provider.protocol) ? "" : "/models");
    Request.Builder builder = new Request.Builder().url(url).get();
    if (provider.apiKey != null && !provider.apiKey.isBlank()) {
      if ("anthropic".equals(provider.protocol)) {
        builder.header("x-api-key", provider.apiKey).header("anthropic-version", "2023-06-01");
      } else if ("gemini".equals(provider.protocol)) {
        builder.header("x-goog-api-key", provider.apiKey);
      } else {
        builder.header("Authorization", "Bearer " + provider.apiKey);
      }
    }
    if (provider.headers != null) {
      provider.headers.forEach((name, value) -> builder.header(name.toString(), String.valueOf(value)));
    }
    OkHttpClient client = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build();
    long start = System.currentTimeMillis();
    try (Response response = UniChatClient.execute(client, builder.build())) {
      String body = response.body() == null ? "" : response.body().string();
      Object catalog = null;
      if (response.isSuccessful()) {
        try {
          catalog = JsonUtils.parse(body, Kv.class);
        } catch (RuntimeException ignored) {
          catalog = null;
        }
      }
      return Kv.create().set("healthy", response.isSuccessful()).set("status", response.code())
          .set("latencyMs", System.currentTimeMillis() - start).set("catalog", catalog);
    }
  }
}
