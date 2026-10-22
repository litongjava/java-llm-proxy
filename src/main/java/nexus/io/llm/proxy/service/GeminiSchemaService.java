package nexus.io.llm.proxy.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;

/** Adapts JSON Schema keywords that Gemini's typed Schema does not accept. */
public class GeminiSchemaService {
  public Kv convert(Object value) {
    return convert(value, ProxyJson.object(value), new HashSet<>(), new int[] {10000}, 0);
  }

  private Kv convert(Object value, Kv root, Set<String> references, int[] budget, int depth) {
    if (depth > 64 || --budget[0] < 0) {
      throw new IllegalArgumentException("Tool schema exceeds expansion limits");
    }
    Kv source = Kv.create().set(ProxyJson.object(value));
    Object reference = source.remove("$ref");
    if (reference instanceof String ref) {
      Object target = resolve(root, ref);
      if (target instanceof Map<?, ?> && references.add(ref)) {
        Kv expanded = convert(target, root, references, budget, depth + 1);
        references.remove(ref);
        source = Kv.create().set(expanded).set(source);
      } else {
        describe(source, "Schema reference: " + ref);
      }
    }
    Kv schema = Kv.create().set(source);
    schema.remove("$defs");
    schema.remove("definitions");
    schema.remove("$schema");
    for (String key : List.of("discriminator", "readOnly", "writeOnly", "contentMediaType", "contentEncoding")) {
      schema.remove(key);
    }
    // Recurse only through schema locations: property names and default values are user data.
    for (String key : List.of("properties", "patternProperties")) {
      if (source.get(key) instanceof Map<?, ?> entries) {
        Kv children = Kv.create();
        entries.forEach((name, child) -> children.set(name.toString(), convert(child, root, references, budget, depth + 1)));
        schema.set(key, children);
      }
    }
    for (String key : List.of("items", "additionalProperties", "not", "if", "then", "else")) {
      if (source.get(key) instanceof Map<?, ?>) { schema.set(key, convert(source.get(key), root, references, budget, depth + 1)); }
    }
    for (String key : List.of("anyOf", "oneOf", "allOf", "prefixItems")) {
      if (source.get(key) instanceof List<?> children) {
        schema.set(key, children.stream().map(child -> convert(child, root, references, budget, depth + 1)).toList());
      }
    }
    if (schema.containsKey("propertyNames")) {
      Object names = schema.remove("propertyNames");
      // JSON object keys are already strings; retain any stronger constraint as guidance.
      if (!Map.of("type", "string").equals(names)) { describe(schema, "Property names must satisfy JSON Schema: " + json(names)); }
    }
    if (schema.containsKey("const")) {
      Object constant = schema.remove("const");
      if (constant instanceof String) { schema.set("enum", List.of(constant)); }
      else { describe(schema, "Value must equal: " + json(constant)); }
    }
    if (schema.get("enum") instanceof List<?> values && values.stream().anyMatch(item -> !(item instanceof String))) {
      // Gemini's typed enum is repeated string; stringifying would change JSON value types.
      schema.remove("enum");
      describe(schema, "Value must be one of these JSON values: " + json(values));
    }
    exclusiveBound(schema, "exclusiveMinimum", "minimum", true);
    exclusiveBound(schema, "exclusiveMaximum", "maximum", false);
    return schema;
  }

  private Object resolve(Kv root, String reference) {
    if ("#".equals(reference)) { return root; }
    if (!reference.startsWith("#/")) { return null; }
    Object current = root;
    for (String segment : reference.substring(2).split("/", -1)) {
      String key = segment.replace("~1", "/").replace("~0", "~");
      if (!(current instanceof Map<?, ?> map)) { return null; }
      current = map.get(key);
    }
    return current;
  }

  private void exclusiveBound(Kv schema, String keyword, String bound, boolean lower) {
    Object exclusive = schema.remove(keyword);
    if (exclusive == null || Boolean.FALSE.equals(exclusive)) { return; }
    Object raw = Boolean.TRUE.equals(exclusive) ? schema.get(bound) : exclusive;
    if (!(raw instanceof Number)) { return; }
    BigDecimal limit = new BigDecimal(raw.toString());
    boolean integer = "integer".equalsIgnoreCase(schema.getStr("type"));
    BigDecimal inclusive = integer
        ? limit.setScale(0, lower ? RoundingMode.FLOOR : RoundingMode.CEILING).add(BigDecimal.valueOf(lower ? 1 : -1))
        : limit;
    Object existing = schema.get(bound);
    if (existing instanceof Number) {
      BigDecimal current = new BigDecimal(existing.toString());
      inclusive = lower ? inclusive.max(current) : inclusive.min(current);
    }
    schema.set(bound, inclusive);
    if (!integer) { describe(schema, "Value must be strictly " + (lower ? "greater" : "less") + " than " + limit.toPlainString() + "."); }
  }

  private void describe(Kv schema, String constraint) {
    String description = ProxyJson.text(schema, "description", "");
    schema.set("description", description.isEmpty() ? constraint : description + "\n" + constraint);
  }

  private String json(Object value) { return JsonUtils.toJsonWithLongAsNumber(value); }
}
