package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
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
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.concurrent.TimeUnit;

public class ManagementService {
  private static final Logger LOG = LoggerFactory.getLogger(ManagementService.class);
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
          .set("latencyMs", (int) (System.currentTimeMillis() - start)).set("catalog", catalog);
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
    URI uri = URI.create(url);
    Request.Builder builder = new Request.Builder().url(url)
        .post(RequestBody.create(data, MediaType.get("application/json")));
    Kv outboundHeaders = Kv.create();
    String session = null;
    boolean hasUserAgent = false;
    for (Object key : headers.keySet()) {
      String name = String.valueOf(key);
      Object value = headers.get(name);
      if (name.equalsIgnoreCase("x-opencode-session")) { session = String.valueOf(value); }
      if (name.equalsIgnoreCase("user-agent")) { hasUserAgent = true; }
      if (HeaderService.transport(name) || name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")) { continue; }
      builder.header(name, String.valueOf(value));
      outboundHeaders.set(name, String.valueOf(value));
    }
    if (!hasUserAgent) {
      String userAgent = probeUserAgent(uri.getHost(), input.getStr("protocol"));
      builder.header("User-Agent", userAgent);
      outboundHeaders.set("User-Agent", userAgent);
    }
    if ("opencode.ai".equalsIgnoreCase(uri.getHost()) && session == null) {
      session = "desktop-health-" + UUID.randomUUID();
      builder.header("x-opencode-session", session);
      outboundHeaders.set("x-opencode-session", session);
    }
    OkHttpClient client = new OkHttpClient.Builder().callTimeout(timeout, TimeUnit.MILLISECONDS)
        .followRedirects(false).build();
    long start = System.currentTimeMillis();
    RequestRecord record = probeRecord(input, url, data, outboundHeaders, session, start);
    try (Response response = UniChatClient.execute(client, builder.build())) {
      long firstByteMs = System.currentTimeMillis() - start;
      String body = response.body() == null ? "" : response.body().string();
      record.status = response.code();
      record.firstByteMs = firstByteMs;
      record.durationMs = System.currentTimeMillis() - start;
      record.upstreamResponseHeaders = HeaderService.redact(response.headers().toMultimap());
      record.responseHeaders = record.upstreamResponseHeaders;
      applyProbeBody(record, body);
      try {
        Aop.get(ProtocolFormatService.class).usage(record, body);
      } catch (RuntimeException ignored) {
        // A health probe must not fail because the usage parser did not recognize the frame.
      }
      persistProbe(record);
      return Kv.create().set("healthy", response.isSuccessful()).set("status", response.code())
          .set("latencyMs", (int) (System.currentTimeMillis() - start)).set("requestId", record.id);
    } catch (Exception error) {
      record.status = 502;
      record.durationMs = System.currentTimeMillis() - start;
      record.error = error.getMessage();
      persistProbe(record);
      throw error;
    }
  }

  private RequestRecord probeRecord(Kv input, String url, String data,
      Kv outboundHeaders, String session, long start) {
    URI uri = URI.create(url);
    String model = input.getStr("model");
    if (model == null || model.isBlank()) {
      try {
        model = ProxyJson.text(ProxyJson.parse(data), "model", "");
      } catch (RuntimeException ignored) {
        model = "";
      }
    }
    RequestRecord record = new RequestRecord();
    record.id = String.valueOf(SnowflakeIdUtils.id());
    record.timestamp = start;
    record.method = "POST";
    record.path = uri.getPath();
    record.protocol = probeProtocol(input.getStr("protocol"), uri.getPath());
    record.model = model;
    record.requestedModel = model;
    record.provider = "opencode.ai".equalsIgnoreCase(uri.getHost()) ? "opencode" : uri.getHost();
    record.apiKeyName = "provider-health";
    record.source = "provider-health-check";
    record.originator = "provider-health-check";
    record.session_id = session;
    record.stream = true;
    record.requestHeaders = HeaderService.redact(outboundHeaders);
    record.upstreamRequestHeaders = HeaderService.redact(outboundHeaders);
    record.requestBody = data;
    record.upstreamRequestBody = data;
    return record;
  }

  private static String probeUserAgent(String host, String protocol) {
    String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
    if (normalizedHost.equals("opencode.ai") || normalizedHost.endsWith(".opencode.ai")) {
      return "atomcode/5.2.8";
    }
    if (normalizedHost.equals("api.anthropic.com") || normalizedHost.endsWith(".anthropic.com")) {
      return "claude-cli/2.1.302 (external, cli)";
    }
    if (normalizedHost.equals("chatgpt.com") || normalizedHost.endsWith(".chatgpt.com")
        || normalizedHost.equals("api.openai.com")) {
      return "codex-tui/0.166.0 (Windows 10.0.26300; x86_64) xterm-256color (codex-tui; 0.167.0)";
    }
    if ("claude".equals(protocol)) {
      return "claude-cli/2.1.302 (external, cli)";
    }
    if ("codex".equals(protocol) || "openai-responses".equals(protocol)) {
      return "codex-tui/0.166.0 (Windows 10.0.26300; x86_64) xterm-256color (codex-tui; 0.167.0)";
    }
    if ("gemini".equals(protocol) || "interactions".equals(protocol)) {
      return "GeminiCLI/0.1.0 (Windows; x86_64)";
    }
    return "LLMPoroxyUI/1.0.0 (+https://github.com/litongjava/java-llm-proxy)";
  }

  private static String probeProtocol(String protocol, String path) {
    if (protocol == null) {
      return path != null && path.endsWith("/responses") ? "responses" : "openai";
    }
    return switch (protocol) {
    case "openai-responses" -> "responses";
    case "openai-chat" -> "openai";
    case "claude" -> "anthropic";
    default -> protocol;
    };
  }

  private void applyProbeBody(RequestRecord record, String body) {
    ProxySettings settings = Aop.get(SettingsService.class).snapshot();
    String originalRequest = record.requestBody;
    if (!settings.captureBodies) {
      record.requestBody = null;
      record.upstreamRequestBody = null;
      record.responseBody = null;
      record.upstreamResponseBody = null;
      return;
    }
    int limit = EnvUtils.getInt("proxy.capture.max-bytes", 4194304);
    String requestBody = Utf8Capture.prefix(originalRequest, limit);
    String responseBody = Utf8Capture.prefix(body, limit);
    record.requestBody = requestBody;
    record.upstreamRequestBody = requestBody;
    record.requestTruncated = requestBody.length() < originalRequest.length();
    record.responseBody = responseBody;
    record.upstreamResponseBody = responseBody;
    record.responseTruncated = responseBody.length() < body.length();
  }

  private void persistProbe(RequestRecord record) {
    try {
      Aop.get(StorageService.class).record(record);
    } catch (RuntimeException error) {
      LOG.warn("Could not persist provider health probe {}", record.id, error);
    }
  }
}
