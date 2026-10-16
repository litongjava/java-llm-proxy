package nexus.io.llm.proxy.service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.LiveCallRequest;
import nexus.io.llm.proxy.model.LiveCallSession;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.validation.LiveRequestValidator;
import nexus.io.model.body.RespBodyVo;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.core.Tio;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.validator.ParameterValidator;
import nexus.io.tio.websocket.common.WebSocketResponse;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Codex WebRTC bootstrap and account-pinned sideband transport. */
public class CodexLiveService {
  private final ConcurrentHashMap<String, LiveCallSession> calls = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ChannelContext, LiveCallSession> connections = new ConcurrentHashMap<>();

  public CodexLiveService() {
    Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "codex-live-cleanup");
      thread.setDaemon(true);
      return thread;
    }).scheduleWithFixedDelay(() -> {
      for (LiveCallSession session : calls.values()) {
        synchronized (session) {
          if ((session.channel == null && session.expires <= System.currentTimeMillis())
              || (session.channel != null && (session.channel.isClosed || session.channel.isRemoved))) {
            close(session);
          }
        }
      }
    }, 10, 10, TimeUnit.SECONDS);
  }

  public HttpResponse create(HttpRequest request) throws Exception {
    LiveCallRequest input = LiveRequestValidator.input(request);
    String model = input.session == null ? input.model : ProxyJson.text(input.session, "model", input.model);
    model = normalizeModel(model);
    SettingsService.Selection selection = Aop.get(SettingsService.class).select(model, "responses", "codex", null,
        null, Set.of(), Aop.get(ToolScopeService.class).tool(request));
    Provider provider = selection.provider();
    BusinessException.require("oauth".equals(provider.authType), 400, "LIVE_OAUTH_REQUIRED", "Codex Live requires a Codex OAuth credential");
    if (input.session != null) {
      input.session.set("model", selection.model());
    } else if (input.model != null) {
      input.model = selection.model();
    }
    Kv payload = input.extensions.set("sdp", input.sdp);
    if (input.session != null) {
      payload.set("session", input.session);
    }
    if (input.model != null) {
      payload.set("model", input.model);
    }
    String url = provider.baseUrl + "/realtime/calls?intent=quicksilver&architecture=avas";
    Request upstream = headers(request, provider).url(url).post(RequestBody.create(
        JsonUtils.toJsonWithLongAsNumber(payload), MediaType.get("application/json"))).build();
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    HttpResponse response = TioRequestContext.getResponse();
    try (Response received = UniChatClient.execute(transport.client(proxy(provider), 0), upstream)) {
      byte[] bytes = received.body() == null ? new byte[0] : received.body().byteStream().readNBytes(16 * 1024 * 1024 + 1);
      BusinessException.require(bytes.length <= 16 * 1024 * 1024, 502, "LIVE_RESPONSE_LIMIT", "Live response exceeds size limit");
      Aop.get(RoutingService.class).result(provider, model, received.code(), received.header("Retry-After"));
      response.setStatus(received.code());
      for (String name : List.of("Content-Type", "Retry-After", "X-Request-Id", "OpenAI-Request-Id")) {
        if (received.header(name) != null) {
          response.addHeader(name, received.header(name));
        }
      }
      if (received.isSuccessful()) {
        String id = locationId(received.header("Location"));
        BusinessException.require(id != null, 502, "LIVE_LOCATION", "Live upstream did not return a valid call Location");
        LiveCallSession session = new LiveCallSession();
        session.id = id;
        session.model = model;
        session.providerId = provider.id;
        session.owner = String.valueOf(request.getLocalAttribute("proxy.owner"));
        session.tool = Aop.get(ToolScopeService.class).tool(request);
        session.expires = System.currentTimeMillis() + 3600000;
        synchronized (calls) {
          BusinessException.require(calls.size() < EnvUtils.getInt("proxy.live.max-sessions", 1000),
              503, "LIVE_CAPACITY", "Live session capacity reached");
          BusinessException.require(calls.putIfAbsent(id, session) == null, 502, "LIVE_DUPLICATE", "Duplicate upstream call ID");
        }
        String path = request.getRequestURI();
        String prefix = path.substring(0, path.indexOf("/v1/"));
        response.addHeader("Location", prefix + "/v1/realtime/calls/" + id);
      }
      // SDP and upstream protocol responses must remain wire-compatible with Codex.
      response.setBody(bytes);
      return response;
    }
  }

  public static String normalizeModel(String model) {
    if (model == null || model.isBlank() || model.equals("gpt-realtime")
        || model.startsWith("gpt-realtime-") || model.contains("realtime-preview")) {
      return "gpt-live-1-codex";
    }
    return ParameterValidator.text(model, "model", 256);
  }

  public static String locationId(String location) {
    if (LiveRequestValidator.validId(location)) {
      return location;
    }
    try {
      URI uri = URI.create(location);
      if (uri.getRawQuery() != null) {
        String queryId = HttpUrl.get("https://localhost/?" + uri.getRawQuery()).queryParameter("call_id");
        if (LiveRequestValidator.validId(queryId)) {
          return queryId;
        }
      }
      String path = uri.getRawPath().replaceAll("/+$", "");
      String id = path.substring(path.lastIndexOf('/') + 1);
      return LiveRequestValidator.validId(id) ? id : null;
    } catch (RuntimeException error) {
      return null;
    }
  }

  private Request.Builder headers(HttpRequest request, Provider provider) {
    Request.Builder builder = new Request.Builder();
    for (String name : List.of("OpenAI-Alpha", "X-Session-Id", "Session-Id", "Thread-Id", "Originator",
        "OpenAI-Safety-Identifier", "OpenAI-Organization", "OpenAI-Project", "X-Oai-Attestation")) {
      if (request.getHeader(name.toLowerCase(Locale.ROOT)) != null) {
        builder.header(name, request.getHeader(name.toLowerCase(Locale.ROOT)));
      }
    }
    provider.headers.forEach((key, value) -> builder.header(key.toString(), value.toString()));
    builder.header("Authorization", "Bearer " + provider.apiKey);
    String account = provider.metadata.getStr("account_id");
    if (account != null && !account.isBlank()) {
      builder.header("Chatgpt-Account-Id", account);
    }
    return builder;
  }

  private String proxy(Provider provider) {
    return ProxyJson.text(provider.metadata, "proxy_url", ProxyJson.text(provider.metadata, "proxy-url",
        Aop.get(ManagementHttpService.class).configuredProxy()));
  }

  public static String sidebandUrl(String base, String path, String id) {
    String root = base.replaceAll("/+$", "");
    if (path.endsWith("/realtime")) {
      return root + "/realtime?intent=quicksilver&call_id=" + id;
    }
    return root + (path.contains("/realtime/calls/") ? "/realtime/calls/" : "/live/") + id;
  }

  public HttpResponse handshake(HttpRequest request, HttpResponse response, ChannelContext channel) throws Exception {
    String id = LiveRequestValidator.callId(request);
    LiveCallSession session = calls.get(id);
    BusinessException.require(session != null, 404, "LIVE_NOT_FOUND", "Live call not found or expired");
    synchronized (session) {
      BusinessException.require(!session.closed && session.expires > System.currentTimeMillis(), 404, "LIVE_EXPIRED", "Live call expired");
      BusinessException.require(Objects.equals(session.owner, request.getLocalAttribute("proxy.owner")),
          403, "LIVE_OWNER", "Live call belongs to another API key");
      BusinessException.require(session.channel == null, 409, "LIVE_BUSY", "Live call already connected");
      session.channel = channel;
      connections.put(channel, session);
    }
    try {
      Provider provider = Aop.get(SettingsService.class).select(session.model, "responses", "codex", session.providerId,
          null, Set.of(), session.tool).provider();
      String url = sidebandUrl(EnvUtils.get("proxy.live.sideband-base-url", "https://api.openai.com/v1"), request.getRequestURI(), id);
      Request.Builder builder = headers(request, provider).url(url.replaceFirst("^http", "ws"));
      String protocols = request.getHeader("sec-websocket-protocol");
      if (protocols != null) {
        builder.header("Sec-WebSocket-Protocol", protocols);
      }
      CompletableFuture<Integer> attempt = session.handshake;
      session.upstream = UniChatClient.newWebSocket(Aop.get(ManagementHttpService.class).client(proxy(provider), 0), builder.build(), new WebSocketListener() {
        @Override public void onOpen(WebSocket socket, Response received) {
          String protocol = received.header("Sec-WebSocket-Protocol");
          if (protocol != null) {
            response.addHeader("Sec-WebSocket-Protocol", protocol);
          }
          attempt.complete(101);
        }
        @Override public void onMessage(WebSocket socket, String text) {
          synchronized (session) {
            if (attempt == session.handshake) {
              relay(session, WebSocketResponse.fromText(text, "UTF-8"), text.length() * 4);
            }
          }
        }
        @Override public void onMessage(WebSocket socket, ByteString bytes) {
          synchronized (session) {
            if (attempt == session.handshake) {
              relay(session, WebSocketResponse.fromBytes(bytes.toByteArray()), bytes.size());
            }
          }
        }
        @Override public void onFailure(WebSocket socket, Throwable error, Response received) {
          synchronized (session) {
            boolean opened = attempt.getNow(0) == 101;
            attempt.complete(received == null ? 502 : received.code());
            if (opened && attempt == session.handshake) {
              close(session);
            }
          }
        }
        @Override public void onClosing(WebSocket socket, int code, String reason) {
          socket.close(code, reason);
          synchronized (session) {
            if (attempt == session.handshake) {
              close(session);
            }
          }
        }
        @Override public void onClosed(WebSocket socket, int code, String reason) {
          synchronized (session) {
            if (attempt == session.handshake) {
              close(session);
            }
          }
        }
      });
      int status = attempt.get(EnvUtils.getInt("proxy.live.handshake-timeout-seconds", 30), TimeUnit.SECONDS);
      if (status != 101 || session.closed) {
        status = status == 101 ? 502 : status;
        release(session);
        response.setStatus(status);
        return response.respond(RespBodyVo.fail(status, "Live upstream WebSocket handshake failed"));
      }
      return response;
    } catch (Exception error) {
      release(session);
      throw error;
    }
  }

  private void relay(LiveCallSession session, WebSocketResponse packet, int bytes) {
    synchronized (session) {
      if (session.closed) {
        return;
      }
      if (session.ready) {
        if (!Tio.bSend(session.channel, packet)) {
          close(session);
        }
      } else {
        session.pendingBytes += bytes;
        if (session.pendingBytes > 4194304) {
          close(session);
          return;
        }
        session.pending.add(packet);
      }
    }
  }

  public boolean owns(ChannelContext channel) {
    return connections.containsKey(channel);
  }

  public void opened(ChannelContext channel) {
    LiveCallSession session = connections.get(channel);
    if (session == null) {
      return;
    }
    synchronized (session) {
      session.ready = true;
      for (WebSocketResponse packet : session.pending) {
        if (!Tio.bSend(channel, packet)) {
          close(session);
          break;
        }
      }
      session.pending.clear();
      session.pendingBytes = 0;
    }
  }

  public void send(ChannelContext channel, String text, byte[] bytes) {
    LiveCallSession session = connections.get(channel);
    ParameterValidator.require(session != null && !session.closed, "Live connection is closed");
    ParameterValidator.require((text == null ? bytes.length : text.length()) <= 4194304, "Live message exceeds size limit");
    boolean sent = text != null ? session.upstream.send(text) : session.upstream.send(ByteString.of(bytes));
    if (!sent) {
      close(session);
    }
  }

  private void release(LiveCallSession session) {
    synchronized (session) {
      session.handshake = new CompletableFuture<>();
      if (session.upstream != null) {
        session.upstream.cancel();
      }
      connections.remove(session.channel, session);
      session.channel = null;
      session.pending.clear();
      session.pendingBytes = 0;
    }
  }

  public void close(ChannelContext channel) {
    LiveCallSession session = connections.get(channel);
    if (session != null) {
      close(session);
    }
  }

  private void close(LiveCallSession session) {
    synchronized (session) {
      if (session.closed) {
        return;
      }
      session.closed = true;
      session.handshake.complete(502);
      calls.remove(session.id, session);
      if (session.upstream != null) {
        session.upstream.cancel();
      }
      if (session.channel != null) {
        connections.remove(session.channel, session);
        Tio.remove(session.channel, "Live connection closed");
      }
    }
  }
}
