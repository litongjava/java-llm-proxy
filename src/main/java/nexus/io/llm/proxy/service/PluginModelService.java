package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.validator.ParameterValidator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/** Nested plugin model execution uses the same credential and protocol services as HTTP ingress. */
public class PluginModelService {
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  public Response execute(Kv input, boolean streaming) throws Exception {
    BusinessException.require(DEPTH.get() < 8, 508, "PLUGIN_RECURSION", "Nested model execution exceeded its depth limit");
    ParameterValidator.require(streaming == Boolean.TRUE.equals(input.getBoolean("stream")), "Model callback stream flag does not match operation");
    DEPTH.set(DEPTH.get() + 1);
    try { return executeModel(input, streaming); }
    finally { int depth = DEPTH.get() - 1; if (depth == 0) { DEPTH.remove(); } else { DEPTH.set(depth); } }
  }

  private Response executeModel(Kv input, boolean streaming) throws Exception {
    String entry = protocol(ProxyJson.text(input, "entry_protocol", "openai"));
    String exit = protocol(ProxyJson.text(input, "exit_protocol", entry));
    String requested = ParameterValidator.text(input.getStr("model"), "model", 256);
    SettingsService.Selection selected = Aop.get(SettingsService.class).select(requested, exit, input.getStr("forced_provider"), input.getStr("auth_id"), null);
    Provider provider = selected.provider();
    ProtocolConversionService conversion = Aop.get(ProtocolConversionService.class);
    Kv original = ProxyJson.parse(PluginHostCallbacks.text(input.get("body")));
    Kv payload = conversion.request(original, entry, provider.protocol, selected.model(), streaming);
    if (!"gemini".equals(provider.protocol)) { payload.set("model", selected.model()); }
    OAuthTransportService oauth = Aop.get(OAuthTransportService.class);
    String endpoint = switch (provider.protocol) {
      case "anthropic" -> "/messages";
      case "gemini" -> streaming ? ":streamGenerateContent" : ":generateContent";
      case "responses" -> "/responses";
      default -> "/chat/completions";
    };
    String path = input.getStr("path");
    if (path != null && !path.isBlank()) {
      ParameterValidator.require(path.startsWith("/") && !path.startsWith("//") && !path.contains(".."), "Invalid model execution path");
      endpoint = path.startsWith("/v1/") && provider.baseUrl.endsWith("/v1") ? path.substring(3) : path;
    }
    String body = JsonUtils.toJsonWithLongAsNumber(oauth.payload(provider, payload, selected.model(), endpoint));
    String url = oauth.url(provider, provider.baseUrl + ("gemini".equals(provider.protocol) ? "/" + selected.model() : "") + endpoint, endpoint, streaming);
    if (streaming && "gemini".equals(provider.protocol) && !url.contains("alt=sse")) { url += (url.contains("?") ? "&" : "?") + "alt=sse"; }
    Request.Builder builder = new Request.Builder().url(url).post(RequestBody.create(body, MediaType.get("application/json")));
    ProxyJson.object(input.get("headers")).forEach((key, values) -> {
      if (!HeaderService.transport(key.toString()) && !HeaderService.secret(key.toString())) {
        for (Object value : ProxyJson.list(values)) { builder.addHeader(key.toString(), value.toString()); }
      }
    });
    if (provider.apiKey != null) {
      String header = "oauth".equals(provider.authType) ? "Authorization" : switch (provider.protocol) {
        case "anthropic" -> "x-api-key"; case "gemini" -> "x-goog-api-key"; default -> "Authorization";
      };
      builder.header(header, "Authorization".equals(header) ? "Bearer " + provider.apiKey : provider.apiKey);
    }
    if ("anthropic".equals(provider.protocol)) { builder.header("anthropic-version", "2023-06-01"); }
    provider.headers.forEach((name, value) -> { if (!HeaderService.transport(name.toString())) { builder.header(name.toString(), value.toString()); } });
    oauth.headers(provider, builder);
    Request request = builder.build();
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    String proxy = ProxyJson.text(input, "proxy_url", ProxyJson.text(provider.metadata, "proxy_url", ProxyJson.text(provider.metadata, "proxy-url", transport.configuredProxy())));
    OkHttpClient client = transport.client(proxy, 120);
    NativePlugin plugin = provider.upstreamType == null ? null : Aop.get(PluginService.class).provider(provider.upstreamType, true);
    Response raw;
    if (plugin != null) { raw = plugin.execute(request, provider.metadata, selected.model(), provider.protocol, streaming, body); }
    else if ("devin".equals(provider.upstreamType)) {
      DevinTransportService devin = Aop.get(DevinTransportService.class);
      raw = devin.execute(client, devin.prepare(request, provider, body), selected.model(), streaming);
    } else { raw = UniChatClient.execute(client, request); }
    Aop.get(RoutingService.class).result(provider, selected.model(), raw.code(), raw.header("Retry-After"));
    Response response;
    try { response = oauth.response(provider, raw, streaming); }
    catch (Exception error) { raw.close(); throw error; }
    if (!response.isSuccessful() || entry.equals(provider.protocol) || response.body() == null) { return response; }
    if (streaming && response.header("Content-Type", "").contains("text/event-stream")) {
      BufferedSource source = Okio.buffer(Okio.source(new ProtocolEventStream(response.body().byteStream(), provider.protocol, entry, requested)));
      return response.newBuilder().removeHeader("Content-Length").body(new ResponseBody() {
        public MediaType contentType() { return MediaType.get("text/event-stream"); }
        public long contentLength() { return -1; }
        public BufferedSource source() { return source; }
      }).build();
    }
    try (response) {
      Kv translated = conversion.response(ProxyJson.parse(response.body().string()), provider.protocol, entry, requested);
      return response.newBuilder().removeHeader("Content-Length").body(ResponseBody.create(JsonUtils.toJsonWithLongAsNumber(translated), MediaType.get("application/json"))).build();
    }
  }

  private String protocol(String value) { return switch (value) { case "claude" -> "anthropic"; case "codex", "openai-response" -> "responses"; default -> value; }; }
}
