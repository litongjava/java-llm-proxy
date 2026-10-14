package nexus.io.llm.proxy.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;

/** Adapts JSON Schema keywords that Gemini's typed Schema does not accept. */
public class GeminiSchemaService {
  public Kv convert(Object value) {
    Kv source = ProxyJson.object(value);
    Kv schema = Kv.create().set(source);
    schema.remove("$schema");
    // Recurse only through schema locations: property names and default values are user data.
    for (String key : List.of("properties", "$defs", "definitions", "patternProperties")) {
      if (source.get(key) instanceof Map<?, ?> entries) {
        Kv children = Kv.create();
        entries.forEach((name, child) -> children.set(name.toString(), convert(child)));
        schema.set(key, children);
      }
    }
    for (String key : List.of("items", "additionalProperties", "not", "if", "then", "else")) {
      if (source.get(key) instanceof Map<?, ?>) { schema.set(key, convert(source.get(key))); }
    }
    for (String key : List.of("anyOf", "oneOf", "allOf", "prefixItems")) {
      if (source.get(key) instanceof List<?> children) {
        schema.set(key, children.stream().map(this::convert).toList());
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
    exclusiveBound(schema, "exclusiveMinimum", "minimum", true);
    exclusiveBound(schema, "exclusiveMaximum", "maximum", false);
    return schema;
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
