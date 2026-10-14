package nexus.io.llm.proxy.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.core.Tio;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.server.util.SseEmitter;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import nexus.io.tio.utils.validator.ParameterValidator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Buffer;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyService {
  private static final Logger LOG = LoggerFactory.getLogger(ProxyService.class);
  private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(false)
      .followRedirects(false).followSslRedirects(false).build();

  public HttpResponse forward(HttpRequest request, String protocol, String endpoint) throws Exception {
    CredentialRetryState retry = new CredentialRetryState();
    while (true) {
      try { return forwardAttempt(request, protocol, endpoint, retry); }
      catch (CredentialRetryState.NextCredential next) { }
    }
  }

  private HttpResponse forwardAttempt(HttpRequest request, String protocol, String endpoint, CredentialRetryState retry) throws Exception {
    HttpResponse response = TioRequestContext.getResponse();
    Kv payload = request.getBodyObject(Kv.class);
    String model = payload.getStr("model");
    if ("gemini".equals(protocol)) {
      String path = request.getRequestURI();
      int colon = path.lastIndexOf(':');
      ParameterValidator.require(colon > path.lastIndexOf('/'), "Gemini model action is required");
      model = path.substring(path.lastIndexOf('/') + 1, colon);
      endpoint = path.substring(colon);
      ParameterValidator.require(endpoint.equals(":generateContent") || endpoint.equals(":streamGenerateContent")
          || endpoint.equals(":countTokens"), "Unknown Gemini action");
    }
    model = ParameterValidator.text(model, "model", 256);
    SettingsService settingsService = Aop.get(SettingsService.class);
    ProxySettings settings = settingsService.snapshot();
    String body = JsonUtils.toJsonWithLongAsNumber(payload);
    for (String word : settings.sensitiveWords) {
      ParameterValidator.require(word.isBlank() || !body.contains(word), "Request contains a blocked phrase");
    }
    String session = request.getHeader("x-session-id");
    if (session == null) { session = request.getHeader("session_id"); }
    if (session == null) { session = ProxyJson.object(payload.get("metadata")).getStr("session_id"); }
    PluginHookService hooks = Aop.get(PluginHookService.class);
    RequestRecord record = new RequestRecord();
    record.id = retry.requestId;
    record.timestamp = retry.startedAt;
    record.method = "POST";
    record.path = request.getRequestURI();
    record.protocol = protocol;
    record.model = model;
    record.apiKeyName = String.valueOf(request.getLocalAttribute("proxy.identity"));
    record.source = request.getHeader("user-agent");
    record.requestHeaders = HeaderService.redact(request.getHeaders());
    record.stream = Boolean.TRUE.equals(payload.getBoolean("stream")) || endpoint.contains("streamGenerateContent");
    Request.Builder ingress = new Request.Builder().url("http://localhost" + request.getRequestURI())
        .post(RequestBody.create(body, MediaType.get("application/json")));
    request.getHeaders().forEach(ingress::header);
    PluginHookService.Prepared before = hooks.before(ingress.build(), body, record);
    if (before.terminated() != null) {
      try (Response rejected = before.terminated()) {
        record.status = rejected.code(); record.provider = "plugin";
        response.setStatus(rejected.code());
        rejected.headers().forEach(header -> { if (!HeaderService.transport(header.getFirst())) { response.addHeader(header.getFirst(), header.getSecond()); } });
        response.setBody(rejected.body() == null ? new byte[0] : rejected.body().bytes());
        if (settings.captureBodies) { record.requestBody = request.getBodyString(); record.responseBody = new String(response.getBody(), StandardCharsets.UTF_8); }
        finish(record, new Provider(), settings);
        return response;
      }
    }
    Buffer incomingBody = new Buffer();
    before.request().body().writeTo(incomingBody);
    payload = ProxyJson.parse(incomingBody.readUtf8());
    String routedModel = ProxyJson.text(payload, "model", model);
    record.stream = Boolean.TRUE.equals(payload.getBoolean("stream")) || endpoint.contains("streamGenerateContent");
    PluginHookService.Route route = hooks.route(request, payload, protocol, routedModel, record.stream);
    SettingsService.Selection selection = retry.next != null ? retry.next : route.executor() == null ? settingsService.select(route.model(), protocol, route.provider(), null, session)
        : new SettingsService.Selection(route.executor(), route.model(), null);
    Provider provider = selection.provider();
    record.provider = provider.name;
    String upstreamProtocol = provider.protocol;
    ProtocolConversionService conversion = Aop.get(ProtocolConversionService.class);
    Kv pluginPayload = hooks.transformRequest(payload, protocol, upstreamProtocol, selection.model(), record.stream);
    if (!protocol.equals(upstreamProtocol)) {
      ParameterValidator.require(endpoint.equals("/chat/completions") || endpoint.equals("/messages") || endpoint.equals("/responses")
          || endpoint.equals(":generateContent") || endpoint.equals(":streamGenerateContent"), "This endpoint requires a provider with the same protocol");
      payload = pluginPayload == null ? conversion.request(payload, protocol, upstreamProtocol, selection.model(), record.stream) : pluginPayload;
      endpoint = switch (upstreamProtocol) {
        case "anthropic" -> "/messages";
        case "gemini" -> record.stream ? ":streamGenerateContent" : ":generateContent";
        case "responses" -> "/responses";
        default -> "/chat/completions";
      };
    }
    else if (pluginPayload != null) { payload = pluginPayload; }
    int captureLimit = EnvUtils.getInt("proxy.capture.max-bytes", 4194304);
    record.requestBody = settings.captureBodies ? truncate(request.getBodyString(), captureLimit) : null;
    record.requestTruncated = settings.captureBodies && request.getBodyString().getBytes(StandardCharsets.UTF_8).length > captureLimit;
    if (!"gemini".equals(upstreamProtocol)) {
      payload.set("model", selection.model());
    }
    if (selection.reasoningEffort() != null && !selection.reasoningEffort().isBlank()) {
      payload.set("reasoning_effort", selection.reasoningEffort());
    }
    Kv effectivePayload = payload;
    settings.payloadDefaults.forEach((key, value) -> effectivePayload.putIfAbsent(key, value));
    payload.putAll(settings.payloadOverrides);
    settings.payloadRemove.forEach(payload::remove);
    payload = Aop.get(PayloadRuleService.class).apply(payload, selection.model(), provider.upstreamType == null ? upstreamProtocol : provider.upstreamType,
        protocol, Kv.create().set(request.getHeaders()));
    payload = hooks.thinking(payload, provider, selection.model());
    OAuthTransportService oauthTransport = Aop.get(OAuthTransportService.class);
    String upstreamBody = JsonUtils.toJsonWithLongAsNumber(oauthTransport.payload(provider, payload, selection.model(), endpoint));
    record.upstreamRequestBody = settings.captureBodies ? truncate(upstreamBody, captureLimit) : null;
    String url = provider.baseUrl + ("gemini".equals(upstreamProtocol) ? "/" + selection.model() : "") + endpoint;
    if (record.stream && "gemini".equals(upstreamProtocol)) {
      url += "?alt=sse";
    }
    url = oauthTransport.url(provider, url, endpoint, record.stream);
    Request.Builder builder = new Request.Builder().url(url).post(RequestBody.create(upstreamBody, MediaType.get("application/json")));
    before.request().headers().forEach(header -> {
      String name = header.getFirst();
      String value = header.getSecond();
      if (!HeaderService.transport(name) && !HeaderService.secret(name) && !name.equalsIgnoreCase("accept-encoding")
          && !name.equalsIgnoreCase("origin") && !name.equalsIgnoreCase("referer")) {
        builder.header(name, value);
      }
    });
    if (provider.apiKey != null && !provider.apiKey.isBlank()) {
      if ("oauth".equals(provider.authType)) {
        builder.header("Authorization", "Bearer " + provider.apiKey);
        if ("claude".equals(provider.upstreamType)) {
          builder.header("anthropic-beta", "oauth-2025-04-20");
        }
        if ("codex".equals(provider.upstreamType) && provider.metadata.getStr("account_id") != null) {
          builder.header("ChatGPT-Account-Id", provider.metadata.getStr("account_id"));
        }
      } else if ("anthropic".equals(upstreamProtocol)) {
        builder.header("x-api-key", provider.apiKey);
      } else if ("gemini".equals(upstreamProtocol)) {
        builder.header("x-goog-api-key", provider.apiKey);
      } else {
        builder.header("Authorization", "Bearer " + provider.apiKey);
      }
    }
    if ("anthropic".equals(upstreamProtocol) && request.getHeader("anthropic-version") == null) {
      builder.header("anthropic-version", "2023-06-01");
    }
    if (provider.headers != null) {
      provider.headers.forEach((name, value) -> {
        if (!HeaderService.transport(name.toString())) {
          String configured = String.valueOf(value);
          String actual = configured.startsWith("$") ? request.getHeader(configured.substring(1)) : configured;
          if (actual != null) { builder.header(name.toString(), actual); }
        }
      });
    }
    oauthTransport.headers(provider, builder);
    PluginHookService.Prepared prepared = hooks.request(builder.build(), upstreamBody, record, provider);
    Buffer executedBody = new Buffer();
    if (prepared.request().body() != null) { prepared.request().body().writeTo(executedBody); }
    String executedPayload = executedBody.readUtf8();
    if (settings.captureBodies) { record.upstreamRequestBody = truncate(executedPayload, captureLimit); }
    Request upstreamRequest = "devin".equals(provider.upstreamType)
        ? Aop.get(DevinTransportService.class).prepare(prepared.request(), provider, executedPayload) : prepared.request();
    record.upstreamRequestHeaders = HeaderService.redact(upstreamRequest.headers().toMultimap());
    response.removeHeaders("x-request-id");
    response.addHeader("x-request-id", record.id);
    Response upstream;
    Response owned = null;
    try {
      NativePlugin executor = provider.upstreamType == null ? null : Aop.get(PluginService.class).provider(provider.upstreamType, true);
      ManagementHttpService transport = Aop.get(ManagementHttpService.class);
      String proxyUrl = ProxyJson.text(provider.metadata, "proxy_url", ProxyJson.text(provider.metadata, "proxy-url", transport.configuredProxy()));
      OkHttpClient outbound = proxyUrl.isBlank() ? client : transport.client(proxyUrl, 0).newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
      Response rawResponse = prepared.terminated() != null ? prepared.terminated() : Aop.get(RequestRetryService.class).execute(provider, () -> "devin".equals(provider.upstreamType)
          ? Aop.get(DevinTransportService.class).execute(outbound, upstreamRequest, selection.model(), record.stream) : executor == null ? UniChatClient.execute(outbound, upstreamRequest)
          : executor.execute(upstreamRequest, provider.metadata, selection.model(), upstreamProtocol, record.stream, executedPayload));
      owned = rawResponse;
      if (rawResponse.code() == 401 && provider.credentialName != null && executor == null && prepared.terminated() == null) {
        rawResponse.close();
        CredentialService credentials = Aop.get(CredentialService.class);
        Kv refreshed = credentials.refreshed(provider.credentialName, true);
        provider.apiKey = credentials.accessToken(refreshed);
        if ("devin".equals(provider.upstreamType)) {
          Request retried = Aop.get(DevinTransportService.class).prepare(prepared.request(), provider, executedPayload);
          rawResponse = Aop.get(DevinTransportService.class).execute(outbound, retried, selection.model(), record.stream);
        } else {
          rawResponse = UniChatClient.execute(outbound, upstreamRequest.newBuilder().header("Authorization", "Bearer " + provider.apiKey).build());
        }
      }
      owned = rawResponse;
      if (prepared.terminated() == null && CredentialRetryState.retryable(rawResponse.code())) {
        Aop.get(RoutingService.class).result(provider, selection.model(), rawResponse.code(), rawResponse.header("Retry-After"));
        if (route.executor() == null && retry.advance(provider, route.model(), protocol, route.provider(), session)) {
          rawResponse.close();
          throw new CredentialRetryState.NextCredential();
        }
      }
      record.upstreamResponseHeaders = HeaderService.redact(rawResponse.headers().toMultimap());
      if (settings.captureBodies && rawResponse.body() != null) {
        rawResponse = rawResponse.newBuilder().body(new CapturedResponseBody(rawResponse.body(), record, captureLimit)).build();
      }
      Response normalized = oauthTransport.response(provider, rawResponse, record.stream);
      if (normalized.isSuccessful() && normalized.body() != null
          && normalized.header("Content-Type", "").contains("text/event-stream")) {
        int eventLimit = EnvUtils.getInt("proxy.stream.max-event-bytes", 16777216);
        InputStream events = new SseFrameInputStream(normalized.body().byteStream(), eventLimit,
            frame -> hooks.responsePhase(frame, upstreamProtocol, record, request.getBodyString(), executedPayload, "response_before_translator", "response.normalize_before"));
        if (hooks.nativeRoute(upstreamProtocol, protocol)) {
          events = new ProtocolEventStream(events, upstreamProtocol, protocol, model);
        } else {
          events = new SseFrameInputStream(events, eventLimit,
              frame -> hooks.responsePhase(frame, upstreamProtocol, record, request.getBodyString(), executedPayload, "response_translator", "response.translate"));
        }
        events = new SseFrameInputStream(events, eventLimit,
            frame -> hooks.responsePhase(frame, upstreamProtocol, record, request.getBodyString(), executedPayload, "response_after_translator", "response.normalize_after"));
        BufferedSource source = Okio.buffer(Okio.source(events));
        ResponseBody translated = new ResponseBody() {
          public MediaType contentType() { return MediaType.get("text/event-stream"); }
          public long contentLength() { return -1; }
          public BufferedSource source() { return source; }
        };
        upstream = normalized.newBuilder().removeHeader("Content-Length").body(translated).build();
      } else { upstream = normalized; }
    } catch (CredentialRetryState.NextCredential next) { throw next; }
    catch (Exception error) {
      if (owned != null) { owned.close(); }
      if (error instanceof IOException && route.executor() == null) {
        Aop.get(RoutingService.class).result(provider, selection.model(), 502, null);
        if (retry.advance(provider, route.model(), protocol, route.provider(), session)) { throw new CredentialRetryState.NextCredential(); }
      }
      LOG.warn("Upstream connection failed for request {}", record.id, error);
      record.status = 502;
      record.error = "Upstream connection failed";
      finish(record, provider, settings);
      response.setStatus(502);
      return response.respond(RespBodyVo.fail(502, record.error));
    }
    record.firstByteMs = System.currentTimeMillis() - record.timestamp;
    record.status = upstream.code();
    Aop.get(RoutingService.class).result(provider, selection.model(), upstream.code(), upstream.header("Retry-After"));
    record.responseHeaders = HeaderService.redact(upstream.headers().toMultimap());
    response.setStatus(upstream.code());
    upstream.headers().forEach(pair -> {
      if (!HeaderService.transport(pair.getFirst()) && !HeaderService.secret(pair.getFirst())) {
        response.addHeader(pair.getFirst(), pair.getSecond());
      }
    });
    String contentType = upstream.header("Content-Type", "application/json");
    if (contentType.contains("text/event-stream") && upstream.isSuccessful()) {
      record.stream = true;
      response.addServerSentEventsHeader();
      response.setSend(false);
      response.setSkipGzipped(true);
      response.addHeader("Transfer-Encoding", "chunked");
      Thread.startVirtualThread(() -> stream(request, response, upstream, record, provider, settings, captureLimit, executedPayload));
      return response;
    }
    try (upstream) {
      byte[] bytes = upstream.body() == null ? new byte[0] : upstream.body().bytes();
      byte[] pluginResponse = upstream.isSuccessful() && contentType.contains("json") ? hooks.transformResponse(bytes, upstreamProtocol, record, request.getBodyString(), executedPayload) : null;
      if (pluginResponse != null) { bytes = pluginResponse; }
      else if (!protocol.equals(upstreamProtocol) && upstream.isSuccessful() && contentType.contains("json")) {
        bytes = JsonUtils.toJsonWithLongAsNumber(conversion.response(ProxyJson.parse(new String(bytes, StandardCharsets.UTF_8)), upstreamProtocol, protocol, model)).getBytes(StandardCharsets.UTF_8);
      }
      Kv modifiedHeaders = Kv.create().set(upstream.headers().toMultimap());
      if (upstream.isSuccessful()) { bytes = hooks.response(bytes, record, modifiedHeaders); }
      for (String name : upstream.headers().names()) { if (!HeaderService.transport(name)) { response.removeHeaders(name); } }
      modifiedHeaders.forEach((key, value) -> {
        if (!HeaderService.transport(key.toString()) && !HeaderService.secret(key.toString())) {
          for (Object header : ProxyJson.list(value)) { response.addHeader(key.toString(), header.toString()); }
        }
      });
      record.responseHeaders = HeaderService.redact(modifiedHeaders);
      response.setBody(bytes);
      if (contentType.contains("json")) {
        Aop.get(ProtocolFormatService.class).usage(record, new String(bytes, StandardCharsets.UTF_8));
      }
      if (settings.captureBodies) {
        record.responseBody = new String(Arrays.copyOf(bytes, Math.min(bytes.length, captureLimit)), StandardCharsets.UTF_8);
        record.responseTruncated = bytes.length > captureLimit;
      }
    } catch (Exception error) {
      record.error = "Upstream response read failed";
      record.status = 502;
      response.setStatus(502);
      response.respond(RespBodyVo.fail(502, record.error));
    } finally {
      finish(record, provider, settings);
    }
    return response;
  }

  void stream(HttpRequest request, HttpResponse response, Response upstream, RequestRecord record,
      Provider provider, ProxySettings settings, int limit, String upstreamBody) {
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    StreamUsageAccumulator usage = new StreamUsageAccumulator(Aop.get(ProtocolFormatService.class), record);
    PluginHookService hooks = Aop.get(PluginHookService.class);
    Kv headers = Kv.create().set(upstream.headers().toMultimap());
    List<byte[]> history = new ArrayList<>();
    long index = 0;
    try (upstream; InputStream input = upstream.body().byteStream()) {
      hooks.streamChunk(new byte[0], -1, record, headers, request.getBodyString(), upstreamBody, history);
      for (String name : upstream.headers().names()) { if (!HeaderService.transport(name)) { response.removeHeaders(name); } }
      headers.forEach((key, values) -> {
        if (!HeaderService.transport(key.toString()) && !HeaderService.secret(key.toString())) {
          for (Object value : ProxyJson.list(values)) { response.addHeader(key.toString(), value.toString()); }
        }
      });
      record.responseHeaders = HeaderService.redact(headers);
      if (!Tio.bSend(request.getChannelContext(), response)) {
        throw new IllegalStateException("Downstream disconnected");
      }
      SseFrameInputStream frames = new SseFrameInputStream(input, EnvUtils.getInt("proxy.stream.max-event-bytes", 16777216), frame -> frame);
      byte[] frame;
      while ((frame = frames.nextFrame()) != null) {
        usage.accept(frame, frame.length);
        byte[] chunk = hooks.streamChunk(frame, index++, record, headers, request.getBodyString(), upstreamBody, history);
        if (chunk.length == 0) { continue; }
        history.add(chunk);
        while (history.size() > 64 || history.stream().mapToInt(item -> item.length).sum() > 1048576) { history.removeFirst(); }
        if (settings.captureBodies) {
          int keep = Math.min(chunk.length, Math.max(0, limit - capture.size()));
          capture.write(chunk, 0, keep);
          record.responseTruncated |= keep < chunk.length;
        }
        if (!SseEmitter.pushChunk(request.getChannelContext(), chunk)) {
          throw new IllegalStateException("Downstream disconnected");
        }
      }
    } catch (Exception error) {
      record.error = "Stream interrupted";
      record.status = 502;
    } finally {
      usage.finishEvent();
      record.responseBody = settings.captureBodies ? capture.toString(StandardCharsets.UTF_8) : null;
      finish(record, provider, settings);
      SseEmitter.closeChunkConnection(request.getChannelContext());
    }
  }

  void finish(RequestRecord record, Provider provider, ProxySettings settings) {
    record.durationMs = System.currentTimeMillis() - record.timestamp;
    record.cost = (record.inputTokens * provider.inputPrice + record.outputTokens * provider.outputPrice) / 1000000;
    try {
      Aop.get(StorageService.class).record(record);
      Aop.get(LogService.class).recordError(record);
      Aop.get(StorageService.class).prune(settings.retentionDays);
      Aop.get(PluginHookService.class).completed(record);
    } catch (RuntimeException error) {
      LOG.error("Could not persist request {}", record.id, error);
    }
  }

  private String truncate(String value, int limit) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return new String(bytes, 0, Math.min(bytes.length, limit), StandardCharsets.UTF_8);
  }
}
