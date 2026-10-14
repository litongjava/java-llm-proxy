package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.io.InputStream;
import java.io.IOException;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PluginHostCallbacks implements AutoCloseable {
  private final ConcurrentHashMap<String, Response> streams = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Call> operations = new ConcurrentHashMap<>();
  private final Set<String> cancelledOperations = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, String> streamOperations = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, OutputStreamBridge> outputs = new ConcurrentHashMap<>();
  private final Semaphore modelCalls = new Semaphore(8);

  public static final class OutputStreamBridge extends InputStream {
    private final LinkedBlockingQueue<Kv> queue = new LinkedBlockingQueue<>(256);
    private byte[] current = new byte[0];
    private int offset;
    private volatile boolean closed;
    private boolean done;
    public void emit(Kv value) throws Exception {
      BusinessException.require(!closed && queue.offer(value, 30, TimeUnit.SECONDS), 499, "STREAM_CLOSED", "Plugin stream is closed or stalled");
    }
    @Override
    public int read() throws IOException {
      byte[] value = new byte[1];
      return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
    }
    @Override
    public int read(byte[] target, int start, int length) throws IOException {
      if (length == 0) { return 0; }
      while (offset == current.length) {
        if (done || closed) { return -1; }
        Kv chunk;
        try { chunk = queue.poll(120, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        if (chunk == null) { throw new IOException("Plugin stream timed out"); }
        if (chunk.getStr("error") != null && !chunk.getStr("error").isBlank()) { throw new IOException(chunk.getStr("error")); }
        done = Boolean.TRUE.equals(chunk.getBoolean("done"));
        current = decode(chunk.get("payload"));
        offset = 0;
      }
      int count = Math.min(length, current.length - offset);
      System.arraycopy(current, offset, target, start, count);
      offset += count;
      return count;
    }
    @Override
    public void close() { closed = true; queue.clear(); queue.offer(Kv.create().set("done", true)); }
  }

  public OutputStreamBridge output(String id) {
    OutputStreamBridge stream = new OutputStreamBridge();
    outputs.put(id, stream);
    return stream;
  }
  public void closeOutput(String id) {
    OutputStreamBridge stream = outputs.remove(id);
    if (stream != null) { stream.close(); }
  }

  public Kv call(String method, Kv input) throws Exception {
    CredentialService credentials = Aop.get(CredentialService.class);
    switch (method) {
      case "host.stream.emit":
      case "host.stream.close": {
        OutputStreamBridge stream = outputs.get(input.getStr("stream_id"));
        BusinessException.require(stream != null, 404, "STREAM_NOT_FOUND", "Unknown plugin output stream");
        if (method.endsWith("close")) { input.set("done", true); }
        stream.emit(input);
        return Kv.create();
      }
      case "host.http.do":
      case "host.http.do_stream":
        return http(input, method.endsWith("do_stream"));
      case "host.model.execute":
      case "host.model.execute_stream": {
        boolean streaming = method.endsWith("execute_stream");
        BusinessException.require(modelCalls.tryAcquire(), 508, "PLUGIN_RECURSION", "Too many nested model executions");
        Response response;
        try { response = Aop.get(PluginModelService.class).execute(input, streaming); }
        finally { modelCalls.release(); }
        Kv result = Kv.create().set("status_code", response.code()).set("headers", Kv.create().set(response.headers().toMultimap()));
        if (streaming) {
          String id = String.valueOf(SnowflakeIdUtils.id());
          streams.put(id, response);
          return result.set("stream_id", id);
        }
        try (response) {
          byte[] bytes = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(32 * 1024 * 1024 + 1);
          BusinessException.require(bytes.length <= 32 * 1024 * 1024, 413, "MODEL_BODY_LIMIT", "Model response is too large");
          return result.set("body", encode(bytes));
        }
      }
      case "host.http.operation_open":
        return Kv.create().set("operation_id", String.valueOf(SnowflakeIdUtils.id()));
      case "host.http.cancel": {
        String id = input.getStr("operation_id");
        if (cancelledOperations.size() < 10000) { cancelledOperations.add(id); }
        Call operation = operations.remove(id);
        if (operation != null) { operation.cancel(); }
        return Kv.create();
      }
      case "host.model.stream_read":
      case "host.http.stream_read": {
        String id = input.getStr("stream_id");
        Response response = streams.get(id);
        BusinessException.require(response != null, 404, "STREAM_NOT_FOUND", "Unknown plugin HTTP stream");
        byte[] bytes = new byte[8192];
        int count;
        try { count = response.body() == null ? -1 : response.body().byteStream().read(bytes); }
        catch (IOException error) { closeStream(id); return Kv.create().set("done", true).set("error", error.getMessage()); }
        if (count < 0) {
          closeStream(id);
          return Kv.create().set("done", true);
        }
        return Kv.create().set("payload", encode(Arrays.copyOf(bytes, count)));
      }
      case "host.model.stream_close":
      case "host.http.stream_close": {
        closeStream(input.getStr("stream_id"));
        return Kv.create();
      }
      case "host.routing.reset_cooldown":
        return Aop.get(RoutingService.class).reset(input.getStr("auth_index"));
      case "host.affinity.lookup":
        return Aop.get(RoutingService.class).affinity(input);
      case "host.auth.list":
        return Kv.create().set("files", credentials.list());
      case "host.auth.get": {
        String name = credentials.resolve(input.getStr("auth_index"));
        return Kv.create().set("auth_index", input.get("auth_index")).set("name", name).set("json", credentials.read(name));
      }
      case "host.auth.get_runtime": {
        String index = input.getStr("auth_index");
        return Kv.create().set("auth", credentials.list().stream().filter(item -> item.auth_index.equals(index)).findFirst().orElseThrow());
      }
      case "host.auth.save":
        return Kv.create().set("name", credentials.save(input.getStr("name"), ProxyJson.object(input.get("json"))));
      case "host.log":
        // Plugin logs are not echoed because extensions may include credential material.
        return Kv.create();
      default:
        throw new BusinessException(501, "HOST_METHOD", "Unsupported plugin host callback: " + method);
    }
  }

  private Kv http(Kv input, boolean streaming) throws Exception {
    Kv request = input.containsKey("request") ? ProxyJson.object(input.get("request")) : input;
    request = ProxyJson.object(request);
    for (String key : List.of("URL", "Method", "Headers", "Body")) {
      if (request.containsKey(key)) { request.set(key.toLowerCase(), request.get(key)); }
    }
    String url = request.getStr("url");
    ManagementParameterValidator.httpUrl(url);
    String method = ProxyJson.text(request, "method", "GET").toUpperCase();
    byte[] bytes = decode(request.get("body"));
    Request.Builder builder = new Request.Builder().url(url);
    ProxyJson.object(request.get("headers")).forEach((name, value) -> {
      for (Object item : value instanceof List<?> list ? list : List.of(value)) {
        builder.addHeader(name.toString(), item.toString());
      }
    });
    RequestBody body = "GET".equals(method) || "HEAD".equals(method) ? null : RequestBody.create(bytes, MediaType.get("application/octet-stream"));
    builder.method(method, body);
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    Call call = UniChatClient.newCall(transport.client(transport.configuredProxy(), 120), builder.build());
    String operation = input.getStr("operation_id");
    if (operation != null) {
      operations.put(operation, call);
      if (cancelledOperations.remove(operation)) { call.cancel(); }
    }
    Response response;
    try { response = call.execute(); }
    catch (Exception error) {
      if (operation != null) { operations.remove(operation, call); }
      throw error;
    }
    Kv headers = Kv.create().set(response.headers().toMultimap());
    if (streaming) {
      String id = String.valueOf(SnowflakeIdUtils.id());
      streams.put(id, response);
      if (operation != null) { streamOperations.put(id, operation); }
      return Kv.create().set("status_code", response.code()).set("headers", headers).set("stream_id", id);
    }
    try (response) {
      byte[] payload = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(32 * 1024 * 1024 + 1);
      BusinessException.require(payload.length <= 32 * 1024 * 1024, 413, "HTTP_BODY_LIMIT", "Plugin HTTP response is too large");
      return Kv.create().set("StatusCode", response.code()).set("Headers", headers).set("Body", encode(payload));
    } finally { if (operation != null) { operations.remove(operation, call); } }
  }

  private void closeStream(String id) {
    Response response = streams.remove(id);
    if (response != null) { response.close(); }
    String operation = streamOperations.remove(id);
    if (operation != null) { operations.remove(operation); }
  }

  public static byte[] decode(Object value) {
    return value == null ? new byte[0] : Base64.getDecoder().decode(value.toString());
  }
  public static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
  public static String text(Object value) { return new String(decode(value), StandardCharsets.UTF_8); }

  @Override
  public void close() {
    operations.values().forEach(Call::cancel);
    operations.clear();
    cancelledOperations.clear();
    streams.values().forEach(Response::close);
    streams.clear();
    streamOperations.clear();
    outputs.values().forEach(OutputStreamBridge::close);
    outputs.clear();
  }
}
