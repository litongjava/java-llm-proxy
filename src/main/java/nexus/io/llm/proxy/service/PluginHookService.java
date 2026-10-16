package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Protocol;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.model.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PluginHookService {
  private static final Logger LOG = LoggerFactory.getLogger(PluginHookService.class);
  public record Prepared(Request request, Response terminated, boolean bodyChanged) { }

  List<NativePlugin> enabled(String capability) throws Exception { return Aop.get(PluginService.class).enabled(capability); }
  public boolean has(String capability) throws Exception { return !enabled(capability).isEmpty(); }
  public record StreamPlugin(NativePlugin plugin, long schema) { }
  public record StreamHooks(List<StreamPlugin> plugins, boolean history) {
    public StreamHooks { plugins = List.copyOf(plugins); }
    public boolean active() { return !plugins.isEmpty(); }
  }

  public StreamHooks streamHooks() throws Exception {
    List<StreamPlugin> active = enabled("response_stream_interceptor").stream()
        .map(plugin -> new StreamPlugin(plugin, ProxyJson.number(plugin.registration, "schema_version", 1))).toList();
    return new StreamHooks(active, active.stream().anyMatch(plugin -> plugin.schema() < 5));
  }
  public record Route(Provider executor, String model, String provider) { }

  public Route route(HttpRequest request, Kv body, String protocol, String model, boolean stream) throws Exception {
    List<NativePlugin> routers = enabled("model_router");
    if (routers.isEmpty()) { return new Route(null, model, null); }
    PluginService plugins = Aop.get(PluginService.class);
    List<Provider> providers = new ArrayList<>(Aop.get(SettingsService.class).snapshot().providers);
    providers.addAll(Aop.get(CredentialProviderService.class).providers());
    Kv headers = Kv.create();
    request.getHeaders().forEach((name, value) -> headers.set(name, List.of(value)));
    for (NativePlugin plugin : routers) {
      Kv result = plugin.call("model.route", Kv.create().set("Plugin", plugin.registration.get("metadata")).set("PluginID", plugin.id)
          .set("SourceFormat", format(protocol)).set("RequestedModel", model).set("Stream", stream).set("Headers", headers)
          .set("Query", Kv.create().set(request.getParams())).set("Body", encode(JsonUtils.toJsonWithLongAsNumber(body))).set("Metadata", Kv.create())
          .set("AvailableProviders", providers.stream().filter(provider -> provider.enabled).map(provider -> provider.upstreamType).filter(value -> value != null).distinct().toList()));
      if (!Boolean.TRUE.equals(result.getBoolean("Handled"))) { continue; }
      if ("provider".equals(result.getStr("TargetKind"))) { return new Route(null, ProxyJson.text(result, "TargetModel", model), result.getStr("Target")); }
      NativePlugin executor = "self".equals(result.getStr("TargetKind")) ? plugin : plugins.require(result.getStr("Target"));
      BusinessException.require(executor.supports("executor"), 502, "MODEL_ROUTE", "Model router selected a plugin without an executor");
      Provider provider = new Provider();
      provider.id = "plugin:" + executor.id;
      provider.name = executor.id;
      provider.upstreamType = executor.executorProvider;
      provider.protocol = protocol;
      provider.baseUrl = "http://plugin.invalid/v1";
      return new Route(provider, model, null);
    }
    return new Route(null, model, null);
  }

  public Kv thinking(Kv body, Provider provider, String model) throws Exception {
    List<NativePlugin> appliers = enabled("thinking_applier");
    if (appliers.isEmpty()) { return body; }
    Kv config = Kv.create();
    Object effort = body.get("reasoning_effort");
    if (effort == null) { effort = ProxyJson.object(body.get("reasoning")).get("effort"); }
    Object budget = ProxyJson.object(body.get("thinking")).get("budget_tokens");
    if (effort != null) { config.set("Mode", "none".equals(effort) || "auto".equals(effort) ? effort : "level").set("Level", effort); }
    else if (budget != null) { config.set("Mode", "budget").set("Budget", budget); }
    else { return body; }
    for (NativePlugin plugin : appliers) {
      String identifier = plugin.call("thinking.identifier", Kv.create()).getStr("identifier");
      if (!identifier.equals(provider.upstreamType)) { continue; }
      Kv result = plugin.call("thinking.apply", Kv.create().set("Provider", provider.upstreamType).set("Model", model)
          .set("Config", config).set("Body", encode(JsonUtils.toJsonWithLongAsNumber(body))));
      if (result.getStr("Body") != null && !result.getStr("Body").isBlank()) { body = ProxyJson.parse(PluginHostCallbacks.text(result.get("Body"))); }
    }
    return body;
  }

  public Kv transformRequest(Kv body, String from, String to, String model, boolean stream) throws Exception {
    boolean nativeRoute = nativeRoute(from, to);
    if (nativeRoute) { body = Aop.get(ProtocolConversionService.class).request(body, from, to, model, stream); }
    List<NativePlugin> normalizers = enabled("request_normalizer");
    List<NativePlugin> translators = nativeRoute ? List.of() : enabled("request_translator");
    if (normalizers.isEmpty() && translators.isEmpty()) { return body; }
    Kv context = Kv.create().set("FromFormat", format(from)).set("ToFormat", format(to)).set("Model", model).set("Stream", stream)
        .set("Body", encode(JsonUtils.toJsonWithLongAsNumber(body)));
    for (String capability : List.of("request_normalizer", "request_translator")) {
      if (nativeRoute && "request_translator".equals(capability)) { continue; }
      for (NativePlugin plugin : "request_normalizer".equals(capability) ? normalizers : translators) {
        Kv result = plugin.call("request_normalizer".equals(capability) ? "request.normalize" : "request.translate", context);
        if (result.getStr("Body") != null && !result.getStr("Body").isBlank()) { context.set("Body", result.get("Body")); }
      }
    }
    return ProxyJson.parse(PluginHostCallbacks.text(context.get("Body")));
  }

  public byte[] transformResponse(byte[] body, String from, RequestRecord record, String original, String translated) throws Exception {
    body = responsePhase(body, from, record, original, translated, "response_before_translator", "response.normalize_before");
    if (nativeRoute(from, record.protocol)) {
      body = JsonUtils.toJsonWithLongAsNumber(Aop.get(ProtocolConversionService.class).response(ProxyJson.parse(new String(body, StandardCharsets.UTF_8)), from, record.protocol, record.model)).getBytes(StandardCharsets.UTF_8);
    } else {
      body = responsePhase(body, from, record, original, translated, "response_translator", "response.translate");
    }
    return responsePhase(body, from, record, original, translated, "response_after_translator", "response.normalize_after");
  }

  public byte[] responsePhase(byte[] body, String from, RequestRecord record, String original, String translated, String capability, String operation) throws Exception {
    List<NativePlugin> active = enabled(capability);
    if (active.isEmpty()) { return body; }
    Kv context = Kv.create().set("FromFormat", format(from)).set("ToFormat", format(record.protocol)).set("Model", record.model)
        .set("Stream", record.stream).set("OriginalRequest", encode(original)).set("TranslatedRequest", encode(translated)).set("Body", PluginHostCallbacks.encode(body));
    for (NativePlugin plugin : active) {
      Kv result = plugin.call(operation, context);
      if (result.getStr("Body") != null && !result.getStr("Body").isBlank()) { context.set("Body", result.get("Body")); }
    }
    return PluginHostCallbacks.decode(context.get("Body"));
  }

  public boolean nativeRoute(String from, String to) {
    return !from.equals(to) && List.of("openai", "responses", "anthropic", "gemini").contains(from)
        && List.of("openai", "responses", "anthropic", "gemini").contains(to);
  }

  public byte[] streamChunk(StreamHooks streamHooks, byte[] bytes, long index, RequestRecord record, Kv headers, String original, String translated, List<byte[]> history) throws Exception {
    List<StreamPlugin> active = streamHooks.plugins();
    if (active.isEmpty()) { return bytes; }
    Kv context = context(record).set("Body", PluginHostCallbacks.encode(bytes)).set("ChunkIndex", index)
        .set("RequestHeaders", record.requestHeaders).set("ResponseHeaders", headers);
    for (StreamPlugin snapshot : active) {
      NativePlugin plugin = snapshot.plugin();
      long schema = snapshot.schema();
      context.remove("OriginalRequest"); context.remove("RequestBody"); context.remove("HistoryChunks");
      if (index < 0 || schema < 3) { context.set("OriginalRequest", encode(original)).set("RequestBody", encode(translated)); }
      if (index < 0 || schema < 5) { context.set("HistoryChunks", history.stream().map(PluginHostCallbacks::encode).toList()); }
      Kv result = plugin.callIfRunning("response.intercept_stream_chunk", context);
      if (result == null) { continue; }
      if (result.getStr("Body") != null && !result.getStr("Body").isBlank()) { context.set("Body", result.get("Body")); }
      for (Object name : ProxyJson.list(result.get("ClearHeaders"))) { headers.remove(name); }
      headers.set(ProxyJson.object(result.get("Headers")));
      if (Boolean.TRUE.equals(result.getBoolean("DropChunk"))) { return new byte[0]; }
    }
    return PluginHostCallbacks.decode(context.get("Body"));
  }

  public String authenticate(HttpRequest request, String fallback) throws Exception {
    List<NativePlugin> active = enabled("frontend_auth_provider");
    if (active.isEmpty()) { return fallback; }
    Kv headers = Kv.create();
    request.getHeaders().forEach((key, value) -> headers.set(key, List.of(value)));
    for (NativePlugin plugin : active) {
      Kv result = plugin.call("frontend_auth.authenticate", Kv.create().set("Method", request.getRequestLine().getMethod().toString())
          .set("Path", request.getRequestURI()).set("Headers", headers).set("Query", Kv.create().set(request.getParams())).set("Body", encode(request.getBodyString())));
      if (Boolean.TRUE.equals(result.getBoolean("Authenticated"))) { return ProxyJson.text(result, "Principal", plugin.id); }
      if (plugin.supports("frontend_auth_provider_exclusive")) { return null; }
    }
    return fallback;
  }

  public Prepared request(Request request, String body, RequestRecord record, Provider provider) throws Exception {
    return request(request, body, record, provider, "request.intercept_after");
  }

  public Prepared before(Request request, String body, RequestRecord record) throws Exception {
    return request(request, body, record, null, "request.intercept_before");
  }

  private Prepared request(Request request, String body, RequestRecord record, Provider provider, String phase) throws Exception {
    List<NativePlugin> active = enabled("request_interceptor");
    if (active.isEmpty()) { return new Prepared(request, null, false); }
    boolean bodyChanged = false;
    Request current = request;
    Kv context = context(record).set("ToFormat", provider == null ? "" : format(provider.protocol)).set("Headers", Kv.create().set(request.headers().toMultimap()))
        .set("Body", PluginHostCallbacks.encode(body.getBytes(StandardCharsets.UTF_8)));
      for (NativePlugin plugin : active) {
        Kv result = plugin.call(phase, context);
        if (Boolean.TRUE.equals(result.getBoolean("Terminate"))) {
          int status = (int) ProxyJson.number(result, "StatusCode", 403);
          Response.Builder terminated = new Response.Builder().request(current).protocol(Protocol.HTTP_1_1)
              .code(status >= 100 && status <= 599 ? status : 403).message("Plugin response")
              .body(ResponseBody.create(PluginHostCallbacks.decode(result.get("ResponseBody")), MediaType.get("application/json")));
          ProxyJson.object(result.get("ResponseHeaders")).forEach((key, value) -> {
            for (Object header : ProxyJson.list(value)) { terminated.addHeader(key.toString(), header.toString()); }
          });
          return new Prepared(current, terminated.build(), bodyChanged);
        }
        Request.Builder builder = current.newBuilder();
        for (Object name : ProxyJson.list(result.get("ClearHeaders"))) { builder.removeHeader(name.toString()); }
        ProxyJson.object(result.get("Headers")).forEach((key, value) -> {
          builder.removeHeader(key.toString());
          for (Object header : ProxyJson.list(value)) { builder.addHeader(key.toString(), header.toString()); }
        });
        if (result.getStr("Body") != null && !result.getStr("Body").isBlank() && !result.get("Body").equals(context.get("Body"))) {
          bodyChanged = true;
          byte[] modified = PluginHostCallbacks.decode(result.get("Body"));
          builder.post(RequestBody.create(modified, MediaType.get("application/json")));
          context.set("Body", result.get("Body"));
        }
        String path = result.getStr("path");
        if (path != null && path.startsWith("/") && !path.startsWith("//")) { builder.url(current.url().newBuilder().encodedPath(path).build()); }
        current = builder.build();
        context.set("Headers", Kv.create().set(current.headers().toMultimap()));
      }
    return new Prepared(current, null, bodyChanged);
  }

  public byte[] response(byte[] bytes, RequestRecord record, Kv headers) throws Exception {
    List<NativePlugin> active = enabled("response_interceptor");
    if (active.isEmpty()) { return bytes; }
    Kv context = context(record).set("Body", PluginHostCallbacks.encode(bytes)).set("StatusCode", record.status)
        .set("ResponseHeaders", headers).set("RequestHeaders", record.requestHeaders)
        .set("OriginalRequest", encode(record.requestBody)).set("RequestBody", encode(record.upstreamRequestBody));
    for (NativePlugin plugin : active) {
      Kv result = plugin.call("response.intercept_after", context);
      if (result.getStr("Body") != null && !result.getStr("Body").isBlank()) { context.set("Body", result.get("Body")); }
      for (Object name : ProxyJson.list(result.get("ClearHeaders"))) { headers.remove(name); }
      headers.set(ProxyJson.object(result.get("Headers")));
    }
    return PluginHostCallbacks.decode(context.get("Body"));
  }

  public void completed(RequestRecord record) {
    try {
      List<NativePlugin> lifecycle = enabled("request_lifecycle_plugin");
      List<NativePlugin> usagePlugins = enabled("usage_plugin");
      if (lifecycle.isEmpty() && usagePlugins.isEmpty()) { return; }
      Kv completion = context(record).set("Outcome", record.error == null && record.status < 400 ? "succeeded" : "failed")
          .set("StatusCode", record.status).set("Error", record.error).set("StartedAt", Instant.ofEpochMilli(record.timestamp).toString())
          .set("CompletedAt", Instant.now().toString());
      for (NativePlugin plugin : lifecycle) { notifyCompletion(plugin, "request.complete", completion, record.id); }
      Kv usage = Kv.create().set("RequestID", record.id).set("Provider", record.provider).set("Model", record.model)
          .set("APIKey", record.apiKeyName).set("RequestedAt", Instant.ofEpochMilli(record.timestamp).toString())
          .set("Failed", record.status >= 400).set("Stream", record.stream).set("Generate", true)
          .set("Latency", record.durationMs * 1000000).set("TTFT", record.firstByteMs * 1000000)
          .set("Detail", Kv.create().set("InputTokens", record.inputTokens).set("OutputTokens", record.outputTokens)
              .set("CachedTokens", record.cachedTokens).set("ReasoningTokens", record.reasoningTokens));
      for (NativePlugin plugin : usagePlugins) { notifyCompletion(plugin, "usage.handle", usage, record.id); }
    } catch (Exception error) { LOG.warn("Plugin completion callback failed for request {}: {}", record.id, error.getClass().getSimpleName()); }
  }

  private void notifyCompletion(NativePlugin plugin, String operation, Kv input, String requestId) {
    try { plugin.call(operation, input); }
    catch (Exception error) { LOG.warn("Plugin {} callback {} failed for request {}: {}", plugin.id, operation, requestId, error.getClass().getSimpleName()); }
  }

  private String encode(String value) { return PluginHostCallbacks.encode((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); }
  private Kv context(RequestRecord record) {
    return Kv.create().set("RequestID", record.id).set("TraceID", record.id).set("SourceFormat", format(record.protocol))
        .set("Model", record.model).set("RequestedModel", record.model).set("Stream", record.stream).set("Metadata", Kv.create());
  }
  public static String format(String protocol) { return switch (protocol) { case "anthropic" -> "claude"; case "responses" -> "openai-response"; default -> protocol; }; }
}
