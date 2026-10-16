package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.jfinal.kit.Kv;
import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.json.FastJson2Utils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/** Native CLIProxy plugin ABI. Loaded Go libraries remain mapped until process exit. */
public final class NativePlugin {
  @Structure.FieldOrder({"ptr", "length"})
  public static class Buffer extends Structure {
    public Pointer ptr;
    public Pointer length;
    public Buffer() { }
    public Buffer(Pointer pointer) { super(pointer); read(); }
  }

  public interface HostCall extends Callback {
    int invoke(Pointer context, Pointer method, Pointer request, Pointer length, Pointer response);
  }
  public interface HostFree extends Callback {
    void invoke(Pointer pointer, Pointer length);
  }
  @Structure.FieldOrder({"abiVersion", "hostCtx", "call", "freeBuffer"})
  public static class HostApi extends Structure {
    public int abiVersion = 1;
    public Pointer hostCtx;
    public HostCall call;
    public HostFree freeBuffer;
  }
  @Structure.FieldOrder({"abiVersion", "call", "freeBuffer", "shutdown"})
  public static class PluginApi extends Structure {
    public int abiVersion;
    public Pointer call;
    public Pointer freeBuffer;
    public Pointer shutdown;
  }

  private static final List<NativePlugin> MAPPED = new ArrayList<>();
  private final ConcurrentHashMap<Long, Memory> allocated = new ConcurrentHashMap<>();
  private final NativeLibrary library;
  private final HostApi host = new HostApi();
  private final PluginApi api = new PluginApi();
  private final PluginHostCallbacks callbacks = new PluginHostCallbacks();
  public final String id;
  public final Path path;
  public Kv registration;
  public Kv capabilities;
  public String config;
  public String authProvider;
  public String executorProvider;
  public Kv management = Kv.create();
  public Kv models = Kv.create();
  private volatile boolean stopped;

  public NativePlugin(String id, Path path, String config) throws Exception {
    this.id = id;
    this.path = path;
    this.config = config;
    Path shadow = Files.createTempDirectory("llm-proxy-plugin-").resolve(path.getFileName());
    Files.copy(path, shadow);
    library = NativeLibrary.getInstance(shadow.toString());
    host.call = this::hostCall;
    host.freeBuffer = (pointer, length) -> {
      Memory memory = allocated.remove(Pointer.nativeValue(pointer));
      if (memory != null) { memory.close(); }
    };
    host.write();
    api.write();
    int result = library.getFunction("cliproxy_plugin_init").invokeInt(new Object[]{host.getPointer(), api.getPointer()});
    api.read();
    BusinessException.require(result == 0 && api.abiVersion == 1 && api.call != null && api.freeBuffer != null,
        422, "PLUGIN_ABI", "Plugin ABI initialization failed");
    synchronized (MAPPED) { MAPPED.add(this); }
    try { configure("plugin.register", config); }
    catch (Exception error) {
      try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
      throw error;
    }
  }

  public synchronized void configure(String method, String yaml) throws Exception {
    Kv nextRegistration = call(method, Kv.create().set("schema_version", 6).set("config_yaml", PluginHostCallbacks.encode(yaml.getBytes(StandardCharsets.UTF_8))));
    BusinessException.require(ProxyJson.number(nextRegistration, "schema_version", 1) <= 6, 422, "PLUGIN_SCHEMA", "Plugin requires a newer RPC schema");
    Kv nextCapabilities = ProxyJson.object(nextRegistration.get("capabilities"));
    String nextAuth = Boolean.TRUE.equals(nextCapabilities.getBoolean("auth_provider")) ? call("auth.identifier", Kv.create()).getStr("identifier") : null;
    String nextExecutor = Boolean.TRUE.equals(nextCapabilities.getBoolean("executor")) ? call("executor.identifier", Kv.create()).getStr("identifier") : null;
    Kv nextModels = Kv.create();
    if (Boolean.TRUE.equals(nextCapabilities.getBoolean("model_registrar"))) { nextModels = call("model.register", Kv.create().set("Plugin", nextRegistration.get("metadata"))); }
    else if (Boolean.TRUE.equals(nextCapabilities.getBoolean("model_provider"))) { nextModels = call("model.static", Kv.create().set("Plugin", nextRegistration.get("metadata")).set("Host", Kv.create())); }
    Kv nextManagement = Kv.create();
    if (Boolean.TRUE.equals(nextCapabilities.getBoolean("management_api"))) {
      nextManagement = call("management.register", Kv.create().set("Plugin", nextRegistration.get("metadata"))
          .set("BasePath", "/v8/management/plugins/" + id).set("ResourceBasePath", "/v0/resource/plugins/" + id));
    }
    registration = nextRegistration; capabilities = nextCapabilities; authProvider = nextAuth;
    executorProvider = nextExecutor; models = nextModels; management = nextManagement; config = yaml;
  }

  public boolean supports(String name) { return capabilities != null && Boolean.TRUE.equals(capabilities.getBoolean(name)); }

  public Response execute(Request request, Kv credential, String model, String protocol, boolean streaming, String payload) throws Exception {
    String format = switch (protocol) { case "anthropic" -> "claude"; case "responses" -> "openai-response"; default -> protocol; };
    Kv input = Kv.create().set("AuthID", credential.get("id")).set("AuthProvider", executorProvider).set("Model", model)
        .set("Format", format).set("SourceFormat", format).set("Stream", streaming).set("Headers", Kv.create().set(request.headers().toMultimap()))
        .set("Query", Kv.create()).set("Payload", PluginHostCallbacks.encode(payload.getBytes(StandardCharsets.UTF_8)))
        .set("OriginalRequest", PluginHostCallbacks.encode(payload.getBytes(StandardCharsets.UTF_8)))
        .set("StorageJSON", PluginHostCallbacks.encode(JsonUtils.toJson(credential).getBytes(StandardCharsets.UTF_8)))
        .set("AuthMetadata", credential).set("AuthAttributes", Kv.create()).set("Metadata", Kv.create());
    ResponseBody body;
    Kv result;
    if (streaming) {
      String id = String.valueOf(SnowflakeIdUtils.id());
      PluginHostCallbacks.OutputStreamBridge stream = callbacks.output(id);
      try {
        result = call("executor.execute_stream", input.set("stream_id", id));
        List<?> chunks = ProxyJson.list(result.get("chunks"));
        if (result.containsKey("chunks") && result.get("chunks") != null) {
          Thread.startVirtualThread(() -> {
            try {
              for (Object value : chunks) {
                Kv chunk = ProxyJson.object(value);
                Object error = chunk.get("Err");
                stream.emit(Kv.create().set("payload", chunk.get("Payload")).set("error", error == null ? null : error.toString()));
              }
              stream.emit(Kv.create().set("done", true));
            } catch (Exception error) { stream.close(); }
          });
        }
        BufferedSource source = Okio.buffer(Okio.source(stream));
        body = new ResponseBody() {
          public MediaType contentType() { return MediaType.get("text/event-stream"); }
          public long contentLength() { return -1; }
          public BufferedSource source() { return source; }
          public void close() { super.close(); callbacks.closeOutput(id); }
        };
      } catch (Exception error) { callbacks.closeOutput(id); throw error; }
    } else {
      result = call(request.url().encodedPath().endsWith("count_tokens") ? "executor.count_tokens" : "executor.execute", input);
      body = ResponseBody.create(PluginHostCallbacks.decode(result.get("Payload")), MediaType.get("application/json"));
    }
    Headers.Builder headers = new Headers.Builder();
    Kv rawHeaders = ProxyJson.object(result.get(streaming ? "headers" : "Headers"));
    rawHeaders.forEach((key, value) -> {
      for (Object entry : ProxyJson.list(value)) { headers.add(key.toString(), entry.toString()); }
    });
    headers.set("Content-Type", streaming ? "text/event-stream" : "application/json");
    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").headers(headers.build()).body(body).build();
  }

  /** Serializes a retained stream callback against shutdown; disabled plugins are skipped. */
  public synchronized Kv callIfRunning(String method, Kv input) throws Exception {
    return stopped ? null : call(method, input);
  }

  public Kv call(String method, Kv input) throws Exception {
    BusinessException.require(!stopped, 409, "PLUGIN_STOPPED", "Plugin is stopped");
    byte[] bytes = FastJson2Utils.toJson(input).getBytes(StandardCharsets.UTF_8);
    try (Memory request = new Memory(Math.max(1, bytes.length)); Memory name = new Memory(method.length() + 1L)) {
      request.write(0, bytes, 0, bytes.length);
      name.setString(0, method, "UTF-8");
      Buffer response = new Buffer();
      response.write();
      int result = Function.getFunction(api.call).invokeInt(new Object[]{name, request, Pointer.createConstant(bytes.length), response.getPointer()});
      response.read();
      try {
        long length = Pointer.nativeValue(response.length);
        BusinessException.require(length >= 0 && length <= 64 * 1024 * 1024 && response.ptr != null,
            502, "PLUGIN_BUFFER", "Plugin returned an invalid response buffer");
        Kv envelope = ProxyJson.parse(new String(response.ptr.getByteArray(0, (int) length), StandardCharsets.UTF_8));
        Kv error = ProxyJson.object(envelope.get("error"));
        BusinessException.require(result == 0 && Boolean.TRUE.equals(envelope.getBoolean("ok")),
            (int) ProxyJson.number(error, "http_status", 502), ProxyJson.text(error, "code", "PLUGIN_ERROR"),
            ProxyJson.text(error, "message", "Plugin call failed"));
        return ProxyJson.object(envelope.get("result"));
      } finally {
        if (response.ptr != null) { Function.getFunction(api.freeBuffer).invokeVoid(new Object[]{response.ptr, response.length}); }
      }
    }
  }

  private int hostCall(Pointer context, Pointer method, Pointer request, Pointer length, Pointer response) {
    Kv envelope;
    try {
      long size = Pointer.nativeValue(length);
      BusinessException.require(size >= 0 && size <= 64 * 1024 * 1024, 413, "PLUGIN_REQUEST", "Plugin host request is too large");
      Kv input = size == 0 ? Kv.create() : ProxyJson.parse(new String(request.getByteArray(0, (int) size), StandardCharsets.UTF_8));
      envelope = Kv.create().set("ok", true).set("result", callbacks.call(method.getString(0, "UTF-8"), input));
    } catch (Throwable error) {
      envelope = Kv.create().set("ok", false).set("error", Kv.create().set("code", "HOST_ERROR").set("message", error.getMessage()));
    }
    byte[] bytes = FastJson2Utils.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    Memory memory = new Memory(Math.max(1, bytes.length));
    memory.write(0, bytes, 0, bytes.length);
    allocated.put(Pointer.nativeValue(memory), memory);
    Buffer output = new Buffer(response);
    output.ptr = memory;
    output.length = Pointer.createConstant(bytes.length);
    output.write();
    return 0;
  }

  public synchronized void close() throws Exception {
    if (stopped) { return; }
    try { call("plugin.quiesce", Kv.create()); }
    finally {
      try { call("plugin.shutdown", Kv.create()); }
      finally {
        stopped = true;
        callbacks.close();
        if (api.shutdown != null) { Function.getFunction(api.shutdown).invokeVoid(new Object[]{}); }
      }
    }
  }
}
