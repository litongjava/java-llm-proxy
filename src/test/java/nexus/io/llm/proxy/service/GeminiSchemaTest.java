package nexus.io.llm.proxy.service;

import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiSchemaTest {
  @Test
  public void convertsDesktopToolsWithoutMutatingClientPayload() {
    Kv body = ProxyJson.parse("""
        {"model":"claude-opus-4-6","metadata":{"user_id":"test"},"max_tokens":128,
         "messages":[{"role":"user","content":"hello"}],"tools":[{
          "name":"desktop_tool","description":"Test tool","input_schema":{
            "$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
            "required":["limit","status"],"properties":{
              "limit":{"type":"integer","exclusiveMinimum":0},
              "status":{"anyOf":[{"type":"string","enum":["pending"]},{"type":"string","const":"deleted"}]},
              "answers":{"type":"object","propertyNames":{"type":"string"},"additionalProperties":{"type":"string"}},
              "rows":{"type":"array","items":{"type":"object","properties":{"width":{"type":"integer","exclusiveMinimum":0}}}},
              "$schema":{"type":"string"},"const":{"type":"string"},
              "example":{"type":"object","default":{"const":"keep","$schema":"keep"}}
            }}}]}
        """);
    String original = JsonUtils.toJson(body);
    ProtocolConversionService converter = new ProtocolConversionService();
    Kv converted = converter.request(body, "anthropic", "gemini", "claude-sonnet-4-6", true);
    assertFalse(converted.containsKey("metadata"));
    Kv declaration = ProxyJson.object(ProxyJson.list(ProxyJson.object(ProxyJson.list(converted.get("tools")).getFirst()).get("functionDeclarations")).getFirst());
    Kv schema = ProxyJson.object(declaration.get("parameters"));
    assertFalse(schema.containsKey("$schema"));
    assertEquals(List.of("limit", "status"), schema.get("required"));
    Kv properties = ProxyJson.object(schema.get("properties"));
    assertEquals(1, ProxyJson.number(ProxyJson.object(properties.get("limit")), "minimum", -1));
    assertFalse(ProxyJson.object(properties.get("limit")).containsKey("exclusiveMinimum"));
    Kv deleted = ProxyJson.object(ProxyJson.list(ProxyJson.object(properties.get("status")).get("anyOf")).get(1));
    assertEquals(List.of("deleted"), deleted.get("enum"));
    assertFalse(deleted.containsKey("const"));
    assertFalse(ProxyJson.object(properties.get("answers")).containsKey("propertyNames"));
    Kv row = ProxyJson.object(ProxyJson.object(properties.get("rows")).get("items"));
    assertEquals(1, ProxyJson.number(ProxyJson.object(ProxyJson.object(row.get("properties")).get("width")), "minimum", -1));
    assertTrue(properties.containsKey("$schema"));
    assertTrue(properties.containsKey("const"));
    assertEquals("keep", ProxyJson.object(ProxyJson.object(properties.get("example")).get("default")).get("$schema"));
    assertEquals(original, JsonUtils.toJson(body));
    assertSame(body, converter.request(body, "anthropic", "anthropic", "claude-opus-4-6", false));
    assertTrue(converter.request(body, "anthropic", "openai", "test", false).containsKey("metadata"));
  }

  @Test
  public void preservesStrongerBoundsAndDescribesConstraintsWithoutExactEquivalent() {
    GeminiSchemaService converter = new GeminiSchemaService();
    Kv integer = converter.convert(ProxyJson.parse("{\"type\":\"integer\",\"exclusiveMinimum\":-1.5,\"exclusiveMaximum\":4.2}"));
    assertEquals(-1, ProxyJson.number(integer, "minimum", -99));
    assertEquals(4, ProxyJson.number(integer, "maximum", -99));
    Kv stronger = converter.convert(ProxyJson.parse("{\"type\":\"integer\",\"minimum\":5,\"exclusiveMinimum\":0,\"maximum\":10,\"exclusiveMaximum\":true}"));
    assertEquals(5, ProxyJson.number(stronger, "minimum", -1));
    assertEquals(9, ProxyJson.number(stronger, "maximum", -1));
    Kv number = converter.convert(ProxyJson.parse("{\"type\":\"number\",\"exclusiveMinimum\":0,\"description\":\"Ratio\"}"));
    assertTrue(number.getStr("description").contains("strictly greater than 0"));
    Kv object = converter.convert(ProxyJson.parse("{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^[a-z]+$\"}}"));
    assertTrue(object.getStr("description").contains("^[a-z]+$"));
  }

  @Test
  public void openAiFunctionOptionsDoNotLeakIntoGeminiDeclarations() {
    Kv body = ProxyJson.parse("""
        {"messages":[{"role":"user","content":"hello"}],"tools":[{"type":"function",
         "function":{"name":"test","strict":true,"parameters":{"type":"object","properties":{}}}}]}
        """);
    Kv converted = new ProtocolConversionService().request(body, "openai", "gemini", "test", false);
    Kv declaration = ProxyJson.object(ProxyJson.list(ProxyJson.object(ProxyJson.list(converted.get("tools")).getFirst()).get("functionDeclarations")).getFirst());
    assertFalse(declaration.containsKey("strict"));
    assertFalse(declaration.containsKey("type"));
    assertEquals("test", declaration.getStr("name"));
  }
}
