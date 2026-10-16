package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jfinal.kit.Kv;

/** Request-local mapping between Responses namespaces and flat function names. */
public class ResponsesToolMapping {
  private final Map<String, Kv> names = new LinkedHashMap<>();
  private final List<Kv> tools = new ArrayList<>();

  public ResponsesToolMapping(Kv request) {
    collect(ProxyJson.list(request.get("tools")), null);
    Object choice = request.get("tool_choice");
    if (choice instanceof Map<?, ?>) {
      Kv selected = ProxyJson.object(choice);
      if (!"function".equals(selected.getStr("type")) || !names.containsKey(name(selected.getStr("namespace"), selected.getStr("name")))) {
        throw new IllegalArgumentException("Selected Responses tool is not supported by this upstream protocol");
      }
    }
    if ("required".equals(choice) && tools.isEmpty()) {
      throw new IllegalArgumentException("No supported function tools remain for required tool_choice");
    }
  }

  private void collect(List<?> entries, String namespace) {
    for (Object value : entries) {
      Kv tool = ProxyJson.object(value);
      if ("namespace".equals(tool.getStr("type"))) {
        collect(ProxyJson.list(tool.get("tools")), tool.getStr("name"));
      } else if ("function".equals(tool.getStr("type"))) {
        String name = tool.getStr("name");
        if (name == null || name.isBlank()) { throw new IllegalArgumentException("Function tool name is required"); }
        String alias = name(namespace, name);
        Kv identity = Kv.create().set("name", name);
        if (namespace != null) { identity.set("namespace", namespace); }
        if (names.putIfAbsent(alias, identity) != null) { throw new IllegalArgumentException("Duplicate function tool: " + alias); }
        Kv function = Kv.create().set("name", alias);
        for (String key : List.of("description", "parameters", "strict")) {
          if (tool.containsKey(key)) { function.set(key, tool.get(key)); }
        }
        tools.add(Kv.create().set("type", "function").set("function", function));
      }
    }
  }

  public List<Kv> tools() { return tools; }

  public static String name(String namespace, String name) {
    if (namespace == null || namespace.isBlank()) { return name; }
    try {
      return "ns_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest((namespace + "\u0000" + name).getBytes(StandardCharsets.UTF_8))).substring(0, 60);
    } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
  }

  @SuppressWarnings("unchecked")
  public void restore(Object value) {
    if (value instanceof Map<?, ?> raw) {
      Map<String, Object> item = (Map<String, Object>) raw;
      if ("function_call".equals(item.get("type")) && !item.containsKey("namespace")) {
        Kv identity = names.get(item.get("name"));
        if (identity != null) { item.putAll(identity); }
      }
      for (Object child : item.values()) { restore(child); }
    } else if (value instanceof List<?> list) { list.forEach(this::restore); }
  }
}
