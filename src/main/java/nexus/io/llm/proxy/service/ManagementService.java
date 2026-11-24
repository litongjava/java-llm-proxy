package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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
    Map<String, Kv> models = new LinkedHashMap<>();
    ModelRoutingService modelRouting = Aop.get(ModelRoutingService.class);
    ModelCatalogService catalog = Aop.get(ModelCatalogService.class);
    List<Provider> providers = new ArrayList<>(settings.providers);
    providers.addAll(Aop.get(PluginService.class).staticProviders());
    providers.addAll(Aop.get(CredentialProviderService.class).providers());
    for (Provider provider : providers) {
      if (!provider.enabled) { continue; }
      Map<String, Kv> details = new LinkedHashMap<>();
      List<?> definitions = provider.credentialName == null ? ProxyJson.list(provider.metadata.get("models"))
          : ProxyJson.list(catalog.credentialModels(provider.credentialName).get("models"));
      for (Object value : definitions) {
        Kv item = value instanceof String ? Kv.create().set("id", value) : ProxyJson.object(value);
        String id = ProxyJson.text(item, "id", item.getStr("name"));
        if (id != null) { details.put(id, item); }
      }
      for (String name : modelRouting.visible(provider, provider.models, tool)) {
        String actual = modelRouting.resolve(provider, name, tool);
        if (actual != null) { addModel(models, name, actual, provider, details.get(actual)); }
      }
      for (var alias : settings.aliases) {
        if (!tool.equals(alias.tool) || (alias.providerId != null && !alias.providerId.isBlank() && !alias.providerId.equals(provider.id))) { continue; }
        String actual = modelRouting.resolve(provider, alias.model, tool);
        if (actual != null) { addModel(models, alias.name, actual, provider, details.get(actual)); }
      }
    }
    return Kv.create().set("object", "list").set("data", new ArrayList<>(models.values()));
  }

  static void addModel(Map<String, Kv> models, String name, String actual, Provider provider, Kv details) {
    String owner = details == null ? null : details.getStr("owned_by");
    if (owner == null || owner.isBlank()) {
      owner = switch (provider.upstreamType == null ? "" : provider.upstreamType) {
        case "codex", "openai" -> "openai";
        case "claude", "anthropic" -> "anthropic";
        case "gemini", "vertex" -> "google";
        case "deepseek" -> "deepseek";
        default -> "unknown";
      };
    }
    String source = provider.upstreamType == null || provider.upstreamType.isBlank()
        ? provider.credentialName == null && provider.name != null ? provider.name : "custom"
        : provider.upstreamType;
    Kv entry = models.computeIfAbsent(name, key -> Kv.create().set("id", key).set("object", "model")
        .set("created", 0).set("owners", new ArrayList<String>()).set("sources", new ArrayList<Kv>()));
    @SuppressWarnings("unchecked") List<String> owners = (List<String>) entry.get("owners");
    if (!owners.contains(owner)) { owners.add(owner); }
    entry.set("owned_by", owners.size() == 1 ? owners.getFirst() : "multiple");
    @SuppressWarnings("unchecked") List<Kv> sources = (List<Kv>) entry.get("sources");
    Kv origin = Kv.create().set("provider", source).set("model", actual).set("owned_by", owner);
    if (!sources.contains(origin)) { sources.add(origin); }
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
    // Health checks bypass the forwarding path; apply the same upstream headers
    // (notably x-opencode-session) before probing the catalog endpoint.
    Aop.get(OAuthTransportService.class).httpHeaders(provider, builder);
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

  /**
   * Forwards one provider health probe from the desktop/server UI. The caller has
   * already built the protocol-specific body and headers; this method only adds
   * headers that depend on the upstream host (opencode.ai needs a session id).
   */
  public Kv probe(Kv input) throws Exception {
    String url = input.getStr("url");
    BusinessException.require(url != null && (url.startsWith("http://") || url.startsWith("https://")),
        400, "INVALID_PROBE_URL", "Probe URL must be HTTP(S)");
    String data = input.getStr("data");
    BusinessException.require(data != null, 400, "INVALID_PROBE_BODY", "Probe body is required");
    Kv headers = ProxyJson.object(input.get("header"));
    for (Object value : headers.values()) {
      BusinessException.require(!String.valueOf(value).contains("$TOKEN$"), 400, "PROBE_AUTH_INDEX",
          "Credential-index probes must run on the desktop client");
    }
    long timeout = Math.min(120_000, Math.max(1_000, ProxyJson.number(input, "timeoutMs", 15_000)));
    Request.Builder builder = new Request.Builder().url(url)
        .post(RequestBody.create(data, MediaType.get("application/json")));
    boolean hasSession = false;
    for (Object key : headers.keySet()) {
      String name = String.valueOf(key);
      Object value = headers.get(name);
      if (name.equalsIgnoreCase("x-opencode-session")) { hasSession = true; }
      if (HeaderService.transport(name) || name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")) { continue; }
      builder.header(name, String.valueOf(value));
    }
    if ("opencode.ai".equalsIgnoreCase(URI.create(url).getHost()) && !hasSession) {
      builder.header("x-opencode-session", "desktop-health-" + UUID.randomUUID());
    }
    OkHttpClient client = new OkHttpClient.Builder().callTimeout(timeout, TimeUnit.MILLISECONDS)
        .followRedirects(false).build();
    long start = System.currentTimeMillis();
    try (Response response = UniChatClient.execute(client, builder.build())) {
      if (response.body() != null) { response.body().string(); }
      return Kv.create().set("healthy", response.isSuccessful()).set("status", response.code())
          .set("latencyMs", System.currentTimeMillis() - start);
    }
  }
}
