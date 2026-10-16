package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.security.SecureRandom;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.llm.proxy.model.QoderPreparedRequest;

/** Isolated wasm-bindgen runtime. A fresh instance owns each request and its object heap. */
public final class QoderWasmRuntime {
  private static final Object UNDEFINED = new Object();
  private static final Object GLOBAL = new Object();
  private static final Object CRYPTO = new Object();
  private final List<Object> objects = new ArrayList<>();
  private int free;
  private final SecureRandom random = new SecureRandom();
  private Instance instance;

  private QoderWasmRuntime(Path file) throws Exception {
    if (Files.size(file) != 298606) { throw new IllegalStateException("Unexpected Qoder WASM size"); }
    byte[] bytes = Files.readAllBytes(file);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    if (!"6419471effa631519def7797d76d7ede38b9fcfa9a83c2148c8ef5d43355b43d".equals(hash)) { throw new IllegalStateException("Unexpected WASM hash"); }
    for (int i = 0; i < 1024; i++) { objects.add(UNDEFINED); }
    objects.add(UNDEFINED); objects.add(null); objects.add(true); objects.add(false); free = objects.size();
    WasmModule module = Parser.parse(bytes);
    ImportValues.Builder imports = ImportValues.builder();
    for (int i = 0; i < module.importSection().importCount(); i++) {
      Import entry = module.importSection().getImport(i);
      if (!(entry instanceof FunctionImport function)) { throw new IllegalStateException("Unexpected nonfunction import"); }
      FunctionType type = module.typeSection().getType(function.typeIndex());
      imports.addFunction(new HostFunction(function.module(), function.name(), type, (current, args) -> {
        instance = current;
        long result = host(function.name(), args);
        return type.returns().isEmpty() ? new long[0] : new long[] {result};
      }));
    }
    instance = Instance.builder(module).withImportValues(imports.build()).withMemoryLimits(new MemoryLimits(17, 512)).build();
  }

  private Memory memory() { return instance.memory(); }
  private long call(String name, long... args) { long[] values = instance.export(name).apply(args); return values == null || values.length == 0 ? 0 : values[0]; }
  private int push(Object value) {
    if (free == objects.size()) { objects.add(objects.size() + 1); }
    int index = free; free = (Integer) objects.get(index); objects.set(index, value); return index;
  }
  private Object object(long index) { return objects.get((int) index); }
  private Object take(long index) {
    Object value = object(index);
    if (index >= 1028) { objects.set((int) index, free); free = (int) index; }
    return value;
  }
  private byte[] random(int length) {
    byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }

  private final class Bytes {
    final byte[] local;
    final int start;
    final int size;
    Bytes(byte[] local, int start, int size) { this.local = local; this.start = start; this.size = size; }
    byte[] read() { return local == null ? memory().readBytes(start, size) : Arrays.copyOfRange(local, start, start + size); }
    void write(byte[] bytes, int offset) {
      if (offset < 0 || bytes.length + offset > size) { throw new IndexOutOfBoundsException("Byte view overflow"); }
      if (local == null) { memory().write(start + offset, bytes); }
      else { System.arraycopy(bytes, 0, local, start + offset, bytes.length); }
    }
  }

  @SuppressWarnings("unchecked")
  private long host(String name, long[] args) {
    int a = args.length > 0 ? (int) args[0] : 0;
    int b = args.length > 1 ? (int) args[1] : 0;
    int c = args.length > 2 ? (int) args[2] : 0;
    if (name.equals("__wbindgen_object_drop_ref")) { take(a); return 0; }
    if (name.equals("__wbindgen_object_clone_ref")) { return push(object(a)); }
    if (name.equals("__wbindgen_cast_0000000000000001")) { return push(new Bytes(null, a, b)); }
    if (name.equals("__wbindgen_cast_0000000000000002")) { return push(memory().readString(a, b)); }
    if (name.startsWith("__wbg_getRandomValues_d493")) { memory().write(a, random(b)); return 0; }
    if (name.startsWith("__wbg_getRandomValues_c44")) { Bytes bytes = (Bytes) object(b); bytes.write(random(bytes.size), 0); return 0; }
    if (name.startsWith("__wbg_randomFillSync")) { Bytes bytes = (Bytes) take(b); bytes.write(random(bytes.size), 0); return 0; }
    if (name.startsWith("__wbg_crypto_")) { return push(CRYPTO); }
    if (name.startsWith("__wbg_static_accessor_GLOBAL")) { return push(GLOBAL); }
    if (name.startsWith("__wbg_static_accessor_SELF") || name.startsWith("__wbg_static_accessor_WINDOW")) { return 0; }
    if (name.startsWith("__wbg_process_") || name.startsWith("__wbg_versions_") || name.startsWith("__wbg_node_") || name.startsWith("__wbg_msCrypto_")) { return push(UNDEFINED); }
    if (name.startsWith("__wbg_new_with_length")) { return push(new Bytes(new byte[a], 0, a)); }
    if (name.startsWith("__wbg_new_99")) { return push(Kv.create()); }
    if (name.startsWith("__wbg_length_")) { return ((Bytes) object(a)).size; }
    if (name.startsWith("__wbg_subarray_")) { Bytes bytes = (Bytes) object(a); return push(new Bytes(bytes.local, bytes.start + b, Math.max(0, Math.min(c, bytes.size) - b))); }
    if (name.startsWith("__wbg_prototypesetcall_")) { new Bytes(null, a, b).write(((Bytes) object(c)).read(), 0); return 0; }
    if (name.startsWith("__wbg_set_")) {
      Object target = object(a);
      if (target instanceof Map<?, ?>) { ((Map<Object, Object>) target).put(object(b), object(c)); return push(target); }
      ((Bytes) target).write(((Bytes) object(b)).read(), object(c) == UNDEFINED ? 0 : ((Number) object(c)).intValue()); return push(UNDEFINED);
    }
    if (name.startsWith("__wbg_now_")) { return Double.doubleToRawLongBits((double) System.currentTimeMillis()); }
    if (name.startsWith("__wbg_Error_")) { return push(new IllegalStateException(memory().readString(a, b))); }
    if (name.startsWith("__wbg___wbindgen_throw_")) { throw new IllegalStateException(memory().readString(a, b)); }
    if (name.startsWith("__wbg___wbindgen_is_object_")) { Object value = object(a); return value != null && value != UNDEFINED && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean) ? 1 : 0; }
    if (name.startsWith("__wbg___wbindgen_is_string_")) { return object(a) instanceof String ? 1 : 0; }
    if (name.startsWith("__wbg___wbindgen_is_function_")) { return 0; }
    if (name.startsWith("__wbg___wbindgen_is_undefined_")) { return object(a) == UNDEFINED ? 1 : 0; }
    // The browser-compatible crypto shim avoids Node-only runtime dependencies.
    if (name.startsWith("__wbg_require_") || name.startsWith("__wbg_call_")) {
      call("__wbindgen_export", push(new IllegalStateException("Unexpected Node-only crypto branch"))); return 0;
    }
    throw new IllegalStateException("Unimplemented host function: " + name);
  }

  private long[] strings(String... strings) {
    long[] values = new long[strings.length * 2];
    for (int i = 0; i < strings.length; i++) {
      byte[] bytes = strings[i].getBytes(StandardCharsets.UTF_8);
      int ptr = (int) call("__wbindgen_export2", bytes.length, 1);
      memory().write(ptr, bytes); values[i * 2] = ptr; values[i * 2 + 1] = bytes.length;
    }
    return values;
  }
  private long[] arguments(int stack, int context, String... strings) {
    long[] text = strings(strings);
    int offset = context == 0 ? 1 : 2;
    long[] args = new long[text.length + offset]; args[0] = stack;
    if (context != 0) { args[1] = context; }
    System.arraycopy(text, 0, args, offset, text.length); return args;
  }
  private String string(String name, String... strings) {
    int stack = (int) call("__wbindgen_add_to_stack_pointer", -16);
    int pointer = 0; int length = 0;
    try {
      call(name, arguments(stack, 0, strings));
      pointer = memory().readInt(stack); length = memory().readInt(stack + 4);
      if (memory().readInt(stack + 12) != 0) { throw new IllegalStateException(String.valueOf(take(memory().readInt(stack + 8)))); }
      return memory().readString(pointer, length);
    } finally {
      call("__wbindgen_add_to_stack_pointer", 16);
      if (pointer != 0) { call("__wbindgen_export4", pointer, length, 1); }
    }
  }
  private int pointer(String name, int context, String... strings) {
    int stack = (int) call("__wbindgen_add_to_stack_pointer", -16);
    try {
      call(name, arguments(stack, context, strings));
      if (memory().readInt(stack + 8) != 0) { throw new IllegalStateException(String.valueOf(take(memory().readInt(stack + 4)))); }
      return memory().readInt(stack);
    } finally { call("__wbindgen_add_to_stack_pointer", 16); }
  }
  private String resultString(String name, int result) {
    int stack = (int) call("__wbindgen_add_to_stack_pointer", -16);
    int pointer = 0; int length = 0;
    try { call(name, stack, result); pointer = memory().readInt(stack); length = memory().readInt(stack + 4); return memory().readString(pointer, length); }
    finally {
      call("__wbindgen_add_to_stack_pointer", 16);
      if (pointer != 0) { call("__wbindgen_export4", pointer, length, 1); }
    }
  }

  public static QoderPreparedRequest prepare(Kv credential, String host, String payload, String model) throws Exception {
    String path = EnvUtils.get("proxy.oauth.qoder.wasm-path", EnvUtils.get("QODER_WASM_PATH", ""));
    if (path.isBlank()) { throw new IllegalStateException("Qoder inference requires proxy.oauth.qoder.wasm-path or QODER_WASM_PATH"); }
    QoderWasmRuntime runtime = new QoderWasmRuntime(Path.of(path));
    Kv user = Kv.create().set("uid", credential.get("uid"))
        .set("security_oauth_token", ProxyJson.text(credential, "security_oauth_token", credential.getStr("access_token")))
        .set("organization_id", ProxyJson.text(credential, "organization_id", ""))
        .set("organization_tags", ProxyJson.list(credential.get("organization_tags")))
        .set("data_policy_agreed", Boolean.TRUE.equals(credential.get("data_policy_agreed")));
    Kv auth = ProxyJson.parse(runtime.string("generate_runtime_auth_fields", JsonUtils.toJson(user)));
    user.remove("security_oauth_token");
    user.set(auth);
    Kv metadata = Kv.create().set("client_type", "5").set("business_product", "cli").set("business_type", "agent").set("scene", "assistant");
    int context = runtime.pointer("qodercontext_new", 0, credential.getStr("machine_id"),
        EnvUtils.get("proxy.oauth.qoder.client-version", "1.1.49"), JsonUtils.toJson(user), JsonUtils.toJson(metadata));
    int result = 0;
    try {
      result = runtime.pointer("qodercontext_prepareInferRequest", context, host, payload, model, "system");
      Kv headers = ProxyJson.object(runtime.take(runtime.call("requestresult_headers", result)));
      return new QoderPreparedRequest(runtime.resultString("requestresult_url", result), runtime.resultString("requestresult_body", result), headers);
    } finally {
      if (result != 0) { runtime.call("__wbg_requestresult_free", result, 0); }
      runtime.call("__wbg_qodercontext_free", context, 0);
    }
  }
}
