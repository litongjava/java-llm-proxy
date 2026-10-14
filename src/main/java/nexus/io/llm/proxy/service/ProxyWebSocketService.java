package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ProxyWebSocketSession;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.core.Tio;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import nexus.io.tio.utils.validator.ParameterValidator;
import nexus.io.tio.websocket.common.WebSocketResponse;
import okhttp3.Request;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ProxyWebSocketService {
  private final ConcurrentHashMap<ChannelContext, ProxyWebSocketSession> sessions = new ConcurrentHashMap<>();

  public ProxyWebSocketService() {
    Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "proxy-websocket-cleanup"); thread.setDaemon(true); return thread;
    }).scheduleWithFixedDelay(() -> {
      for (ChannelContext channel : sessions.keySet()) {
        if (channel.isClosed || channel.isRemoved) { close(channel); }
      }
    }, 10, 10, TimeUnit.SECONDS);
  }

  public void opened(HttpRequest request, ChannelContext channel) {
    ProxyWebSocketSession session = new ProxyWebSocketSession();
    session.request = request;
    session.channel = channel;
    sessions.put(channel, session);
    if (request.getRequestURI().contains("realtime")) {
      synchronized (session) {
        try { connect(session, Kv.create()); }
        catch (RuntimeException error) { fail(session, error.getMessage()); }
      }
    }
  }

  public void text(ChannelContext channel, String text) throws Exception {
    ParameterValidator.require(text.length() <= EnvUtils.getInt("proxy.websocket.max-message-chars", 4194304), "WebSocket message exceeds size limit");
    ProxyWebSocketSession session = sessions.get(channel);
    ParameterValidator.require(session != null && !session.closed, "WebSocket session is closed");
    synchronized (session) {
      Kv input = ProxyJson.parse(text);
      if (session.upstream == null) { connect(session, input); }
      if ("response.create".equals(input.getStr("type"))) {
        ParameterValidator.require(session.record == null, "A response is already in progress");
        begin(session, input, text);
      }
      ParameterValidator.require(session.upstream.send(JsonUtils.toJsonWithLongAsNumber(input)), "Upstream WebSocket send queue is full");
    }
  }

  public void bytes(ChannelContext channel, byte[] bytes) {
    ProxyWebSocketSession session = sessions.get(channel);
    ParameterValidator.require(session != null && session.upstream != null && session.upstream.send(ByteString.of(bytes)), "Upstream WebSocket is not ready");
  }

  private void connect(ProxyWebSocketSession session, Kv input) {
    Kv nested = ProxyJson.object(input.get("response"));
    String model = ProxyJson.text(input, "model", ProxyJson.text(nested, "model", session.request.getParam("model")));
    model = ParameterValidator.text(model, "model", 256);
    SettingsService.Selection selected = Aop.get(SettingsService.class).select(model, "responses");
    ParameterValidator.require("responses".equals(selected.provider().protocol) || "openai".equals(selected.provider().protocol), "WebSocket provider must support the Responses protocol");
    session.provider = selected.provider();
    session.requestedModel = model;
    String endpoint = session.request.getRequestURI().contains("realtime") ? "/realtime" : "/responses";
    String url = session.provider.baseUrl + endpoint;
    if ("/realtime".equals(endpoint)) { url = HttpUrl.get(url).newBuilder().addQueryParameter("model", selected.model()).build().toString(); }
    Request.Builder builder = new Request.Builder().url(url.replaceFirst("^http", "ws"));
    session.request.getHeaders().forEach((key, value) -> {
      if (!HeaderService.transport(key) && !HeaderService.secret(key) && !key.toLowerCase().startsWith("sec-websocket") && !"origin".equalsIgnoreCase(key)) { builder.header(key, value); }
    });
    if (session.provider.apiKey != null) { builder.header("Authorization", "Bearer " + session.provider.apiKey); }
    session.provider.headers.forEach((key, value) -> builder.header(key.toString(), value.toString()));
    Aop.get(OAuthTransportService.class).headers(session.provider, builder);
    builder.header("OpenAI-Beta", "/realtime".equals(endpoint) ? "realtime=v1" : "responses_websockets=2026-02-06");
    session.upstreamRequestHeaders = HeaderService.redact(builder.build().headers().toMultimap());
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    String proxy = ProxyJson.text(session.provider.metadata, "proxy_url", ProxyJson.text(session.provider.metadata, "proxy-url", transport.configuredProxy()));
    session.upstream = UniChatClient.newWebSocket(transport.client(proxy, 0), builder.build(), new WebSocketListener() {
      @Override public void onMessage(WebSocket socket, String message) {
        synchronized (session) {
          try {
            Kv event = ProxyJson.parse(message);
            String type = event.getStr("type");
            RequestRecord record = session.record;
            if (record != null) {
              if (record.firstByteMs == 0) { record.firstByteMs = System.currentTimeMillis() - record.timestamp; }
              if (Aop.get(SettingsService.class).snapshot().captureBodies) {
                int limit = EnvUtils.getInt("proxy.capture.max-bytes", 4194304);
                byte[] line = ("data: " + message + "\n\n").getBytes(StandardCharsets.UTF_8);
                int keep = Math.min(line.length, Math.max(0, limit - session.captured.size()));
                session.captured.write(line, 0, keep);
                record.responseTruncated |= keep < line.length;
              }
              Aop.get(ProtocolFormatService.class).usage(record, message);
              for (NativePlugin plugin : Aop.get(PluginService.class).enabled("websocket_response_observer")) {
                plugin.call("websocket.response_event", Kv.create().set("RequestID", record.id).set("TraceID", record.id).set("SourceFormat", "openai-response")
                    .set("Model", record.model).set("RequestedModel", record.model).set("Provider", session.provider.upstreamType).set("AuthID", session.provider.credentialName)
                    .set("EventType", type).set("Payload", PluginHostCallbacks.encode(message.getBytes(StandardCharsets.UTF_8))).set("Metadata", Kv.create()));
              }
            }
            if (!Tio.bSend(session.channel, WebSocketResponse.fromText(message, "UTF-8"))) { close(session.channel); return; }
            if ("response.completed".equals(type) || "response.done".equals(type)) { finish(session, null); }
            else if ("response.failed".equals(type) || "error".equals(type)) { finish(session, "Upstream WebSocket response failed"); }
          } catch (Exception error) { fail(session, "WebSocket response processing failed"); }
        }
      }
      @Override public void onMessage(WebSocket socket, ByteString bytes) {
        Tio.send(session.channel, WebSocketResponse.fromBytes(bytes.toByteArray()));
      }
      @Override public void onFailure(WebSocket socket, Throwable error, Response response) { fail(session, "Upstream WebSocket connection failed"); }
      @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
      @Override public void onClosed(WebSocket socket, int code, String reason) { close(session.channel); Tio.remove(session.channel, "Upstream closed"); }
      @Override public void onOpen(WebSocket socket, Response response) {
        synchronized (session) {
          session.upstreamResponseHeaders = HeaderService.redact(response.headers().toMultimap());
          if (session.record != null) {
            session.record.upstreamResponseHeaders = session.upstreamResponseHeaders;
            session.record.responseHeaders = session.record.upstreamResponseHeaders;
          }
        }
      }
    });
  }

  private void begin(ProxyWebSocketSession session, Kv input, String original) {
    String requested = ProxyJson.text(input, "model", ProxyJson.text(ProxyJson.object(input.get("response")), "model", session.requestedModel));
    String resolved = Aop.get(ModelRoutingService.class).resolve(session.provider, requested);
    ParameterValidator.require(resolved != null, "Model is not available on this WebSocket connection");
    RequestRecord record = new RequestRecord();
    record.id = String.valueOf(SnowflakeIdUtils.id()); record.timestamp = System.currentTimeMillis(); record.method = "WEBSOCKET";
    record.path = session.request.getRequestURI(); record.protocol = "responses"; record.model = requested; record.provider = session.provider.name;
    record.stream = true; record.status = 200; record.apiKeyName = String.valueOf(session.request.getLocalAttribute("proxy.identity"));
    record.requestHeaders = HeaderService.redact(session.request.getHeaders());
    record.upstreamRequestHeaders = session.upstreamRequestHeaders;
    record.upstreamResponseHeaders = session.upstreamResponseHeaders;
    record.responseHeaders = session.upstreamResponseHeaders;
    if (input.containsKey("response")) { Kv response = ProxyJson.object(input.get("response")); response.set("model", resolved); input.set("response", response); }
    else { input.set("model", resolved); }
    if (Aop.get(SettingsService.class).snapshot().captureBodies) {
      int limit = EnvUtils.getInt("proxy.capture.max-bytes", 4194304);
      byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
      record.requestBody = new String(Arrays.copyOf(bytes, Math.min(bytes.length, limit)), StandardCharsets.UTF_8);
      record.requestTruncated = bytes.length > limit;
      byte[] translated = JsonUtils.toJsonWithLongAsNumber(input).getBytes(StandardCharsets.UTF_8);
      record.upstreamRequestBody = new String(Arrays.copyOf(translated, Math.min(translated.length, limit)), StandardCharsets.UTF_8);
    }
    session.record = record; session.captured.reset();
  }

  private void finish(ProxyWebSocketSession session, String error) {
    synchronized (session) {
      RequestRecord record = session.record;
      if (record == null) { return; }
      session.record = null;
      record.error = error; record.status = error == null ? 200 : 502;
      record.durationMs = System.currentTimeMillis() - record.timestamp;
      record.responseBody = session.captured.size() == 0 ? null : session.captured.toString(StandardCharsets.UTF_8);
      record.upstreamResponseBody = record.responseBody;
      record.cost = (record.inputTokens * session.provider.inputPrice + record.outputTokens * session.provider.outputPrice) / 1000000;
      Aop.get(StorageService.class).record(record);
      Aop.get(PluginHookService.class).completed(record);
    }
  }

  private void fail(ProxyWebSocketSession session, String message) {
    finish(session, message);
    Tio.send(session.channel, WebSocketResponse.fromText(JsonUtils.toJsonWithLongAsNumber(Kv.create().set("type", "error").set("error", Kv.create().set("message", message))), "UTF-8"));
    close(session.channel);
    Tio.remove(session.channel, message);
  }

  public void close(ChannelContext channel) {
    ProxyWebSocketSession session = sessions.remove(channel);
    if (session != null) {
      session.closed = true;
      finish(session, "WebSocket session closed before completion");
      if (session.upstream != null) { session.upstream.cancel(); }
    }
  }
}
