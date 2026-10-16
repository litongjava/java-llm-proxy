package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.llm.proxy.model.UpstreamTask;
import nexus.io.llm.proxy.validation.NativeRequestValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.model.upload.UploadFile;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/** Provider-native endpoints preserve JSON extensions, uploaded files and binary responses. */
public class NativeEndpointService {
  public HttpResponse forward(HttpRequest request, String protocol, String endpoint, String defaultModel, boolean rememberTask) throws Exception {
    return execute(request, protocol, endpoint, defaultModel, rememberTask, null);
  }

  public HttpResponse retrieve(HttpRequest request, String suffix) throws Exception {
    String id = NativeRequestValidator.taskId(request);
    UpstreamTask task = Aop.get(StorageService.class).task(id, String.valueOf(request.getLocalAttribute("proxy.owner")));
    BusinessException.require(task != null, 404, "TASK_NOT_FOUND", "Task not found for this API key");
    return execute(request, task.protocol, "/videos/" + id + suffix, task.model, false, task);
  }

  private HttpResponse execute(HttpRequest request, String protocol, String endpoint, String fallback, boolean remember, UpstreamTask task) throws Exception {
    String method = request.getRequestLine().getMethod().toString();
    String contentType = request.getHeader("content-type");
    boolean json = contentType != null && contentType.toLowerCase().contains("json");
    boolean multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
    Kv input = json && !"GET".equals(method) ? request.getBodyObject(Kv.class) : Kv.create();
    String model = task == null ? NativeRequestValidator.model(request, input, fallback) : task.model;
    SettingsService settings = Aop.get(SettingsService.class);
    SettingsService.Selection selected = settings.select(model, protocol, protocol, task == null ? null : task.providerId, null);
    Provider provider = selected.provider();
    BusinessException.require(protocol.equals(provider.protocol), 400, "NATIVE_PROTOCOL", "This endpoint requires a provider with the same protocol");
    ProxySettings configuration = settings.snapshot();
    RequestRecord record = new RequestRecord();
    record.id = String.valueOf(SnowflakeIdUtils.id()); record.timestamp = System.currentTimeMillis();
    record.method = method; record.path = request.getRequestURI(); record.protocol = protocol;
    record.model = model; record.provider = provider.name; record.source = request.getHeader("user-agent");
    record.apiKeyName = String.valueOf(request.getLocalAttribute("proxy.identity"));
    record.requestHeaders = HeaderService.redact(request.getHeaders());
    int captureLimit = EnvUtils.getInt("proxy.capture.max-bytes", 4194304);
    byte[] original = request.getBody() == null ? new byte[0] : request.getBody();
    if (configuration.captureBodies) {
      byte[] captured = requestCaptureBytes(original, contentType);
      record.requestBody = capture(captured, contentType, captureLimit);
      record.requestTruncated = captured.length > captureLimit;
    }
    String base = provider.baseUrl;
    if ("gemini".equals(protocol) && base.endsWith("/models")) { base = base.substring(0, base.length() - 7); }
    HttpUrl.Builder url = HttpUrl.get(base + endpoint).newBuilder();
    String query = request.getRequestLine().getQueryString();
    if (query != null && !query.isBlank()) { url.encodedQuery(query).removeAllQueryParameters("key"); }
    Request.Builder outbound = new Request.Builder().url(url.build());
    request.getHeaders().forEach((name, value) -> {
      if (!HeaderService.transport(name) && !HeaderService.secret(name) && !name.equalsIgnoreCase("content-type") && !name.equalsIgnoreCase("accept-encoding")) { outbound.header(name, value); }
    });
    RequestBody body = null;
    if (!"GET".equals(method) && !"HEAD".equals(method)) {
      if (json) {
        input.set("model", selected.model());
        body = RequestBody.create(JsonUtils.toJsonWithLongAsNumber(input), MediaType.get(contentType));
      } else if (multipart) {
        MultipartBody.Builder parts = new MultipartBody.Builder().setType(MultipartBody.FORM);
        request.getParams().forEach((name, values) -> {
          if ("model".equals(name) || "key".equals(name)) { return; }
          for (Object value : values) {
            if (value instanceof UploadFile file) {
              parts.addFormDataPart(name, file.getName(), RequestBody.create(file.getData(), MediaType.parse(file.getContentType() == null ? "application/octet-stream" : file.getContentType())));
            } else if (value != null) { parts.addFormDataPart(name, value.toString()); }
          }
        });
        parts.addFormDataPart("model", selected.model());
        body = parts.build();
      } else { body = RequestBody.create(original, MediaType.parse(contentType == null ? "application/octet-stream" : contentType)); }
    }
    outbound.method(method, body);
    if (provider.apiKey != null) {
      String auth = "oauth".equals(provider.authType) ? "Authorization" : "gemini".equals(protocol) ? "x-goog-api-key" : "anthropic".equals(protocol) ? "x-api-key" : "Authorization";
      outbound.header(auth, "Authorization".equals(auth) ? "Bearer " + provider.apiKey : provider.apiKey);
    }
    provider.headers.forEach((name, value) -> { if (!HeaderService.transport(name.toString())) { outbound.header(name.toString(), value.toString()); } });
    Aop.get(OAuthTransportService.class).headers(provider, outbound);
    Request upstream = outbound.build();
    record.upstreamRequestHeaders = HeaderService.redact(upstream.headers().toMultimap());
    if (body != null && configuration.captureBodies) {
      Buffer sent = new Buffer(); body.writeTo(sent);
      String sentType = body.contentType() == null ? contentType : body.contentType().toString();
      record.upstreamRequestBody = capture(requestCaptureBytes(sent.readByteArray(), sentType), sentType, captureLimit);
    }
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    String proxy = ProxyJson.text(provider.metadata, "proxy_url", ProxyJson.text(provider.metadata, "proxy-url", transport.configuredProxy()));
    HttpResponse response = TioRequestContext.getResponse();
    response.addHeader("x-request-id", record.id);
    boolean streaming = false;
    try {
      Response received = UniChatClient.execute(transport.client(proxy, 0).newBuilder().readTimeout(0, TimeUnit.SECONDS).build(), upstream);
      record.status = received.code(); record.firstByteMs = System.currentTimeMillis() - record.timestamp;
      record.upstreamResponseHeaders = HeaderService.redact(received.headers().toMultimap()); record.responseHeaders = record.upstreamResponseHeaders;
      response.setStatus(received.code());
      received.headers().forEach(header -> { if (!HeaderService.transport(header.getFirst()) && !HeaderService.secret(header.getFirst())) { response.addHeader(header.getFirst(), header.getSecond()); } });
      String media = received.header("Content-Type", "application/octet-stream");
      if (received.isSuccessful() && media.contains("text/event-stream")) {
        streaming = true; record.stream = true;
        Response captured = configuration.captureBodies && received.body() != null ? received.newBuilder().body(new CapturedResponseBody(received.body(), record, captureLimit)).build() : received;
        response.addServerSentEventsHeader(); response.setSend(false); response.setSkipGzipped(true); response.addHeader("Transfer-Encoding", "chunked");
        var downstream = request.getChannelContext();
        PluginHookService.StreamHooks streamHooks = Aop.get(PluginHookService.class).streamHooks();
        String originalForStream = streamHooks.active() ? request.getBodyString() : null;
        Thread.startVirtualThread(() -> Aop.get(ProxyService.class).stream(downstream, originalForStream, response, captured, record, provider, configuration, captureLimit, record.upstreamRequestBody, streamHooks));
        return response;
      }
      try (received) {
        int maximum = EnvUtils.getInt("proxy.native.max-response-bytes", 134217728);
        byte[] bytes = received.body() == null ? new byte[0] : received.body().byteStream().readNBytes(maximum + 1);
        BusinessException.require(bytes.length <= maximum, 502, "UPSTREAM_SIZE", "Upstream response exceeds size limit");
        response.setBody(bytes);
        if (configuration.captureBodies) {
          record.responseBody = capture(bytes, media, captureLimit); record.upstreamResponseBody = record.responseBody;
          record.responseTruncated = bytes.length > captureLimit;
        }
        if (media.contains("json") && received.isSuccessful()) {
          String text = new String(bytes, StandardCharsets.UTF_8);
          Aop.get(ProtocolFormatService.class).usage(record, text);
          if (remember) {
            Kv result = ProxyJson.parse(text);
            String id = ProxyJson.text(result, "id", result.getStr("request_id"));
            if (id != null && !id.isBlank()) {
              UpstreamTask saved = new UpstreamTask();
              saved.id = id; saved.model = model; saved.protocol = protocol; saved.providerId = provider.id; saved.owner = String.valueOf(request.getLocalAttribute("proxy.owner"));
              Aop.get(StorageService.class).saveTask(saved);
            }
          }
        }
        return response;
      }
    } catch (Exception error) { record.status = 502; record.error = "Native endpoint request failed"; throw error; }
    finally { if (!streaming) { Aop.get(ProxyService.class).finish(record, provider, configuration); } }
  }

  private byte[] requestCaptureBytes(byte[] bytes, String type) {
    if (type != null && type.contains("json")) {
      return Aop.get(RequestImageService.class).externalize(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }
    return bytes;
  }

  private String capture(byte[] bytes, String type, int limit) {
    int count = Math.min(bytes.length, limit);
    if (type != null && (type.contains("json") || type.startsWith("text/"))) {
      return Utf8Capture.prefix(bytes, limit);
    }
    return "base64:" + Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, count));
  }
}
