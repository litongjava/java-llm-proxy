package nexus.io.llm.proxy.service;

import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiSchemaTest {
  @Test
  public void adaptsNonStringEnumsWithoutChangingValueTypesOrExamples() {
    Kv original = ProxyJson.parse("""
        {"type":"object","properties":{
          "allowAsync":{"type":"boolean","enum":[true],"description":"Run asynchronously"},
          "count":{"type":"integer","enum":[1,2]},
          "mixed":{"enum":["auto",null,false]},
          "rows":{"type":"array","items":{"type":"boolean","enum":[false]}},
          "mode":{"type":"string","enum":["fast","slow"]},
          "example":{"default":{"enum":[true]}}
        }}
        """);
    String before = JsonUtils.toJson(original);
    Kv properties = ProxyJson.object(new GeminiSchemaService().convert(original).get("properties"));
    Kv async = ProxyJson.object(properties.get("allowAsync"));
    assertEquals("boolean", async.getStr("type"));
    assertFalse(async.containsKey("enum"));
    assertEquals("Run asynchronously\nValue must be one of these JSON values: [true]", async.getStr("description"));
    assertFalse(ProxyJson.object(properties.get("count")).containsKey("enum"));
    assertTrue(ProxyJson.object(properties.get("mixed")).getStr("description").contains("null,false"));
    assertFalse(ProxyJson.object(ProxyJson.object(properties.get("rows")).get("items")).containsKey("enum"));
    assertEquals(List.of("fast", "slow"), ProxyJson.object(properties.get("mode")).get("enum"));
    assertEquals(List.of(true), ProxyJson.object(ProxyJson.object(properties.get("example")).get("default")).get("enum"));
    assertEquals(before, JsonUtils.toJson(original));
  }

  @Test
  public void convertsResponsesNamespaceBooleanEnumForGemini() {
    Kv body = ProxyJson.parse("""
        {"input":"hello","tools":[{"type":"namespace","name":"worktrees","tools":[
          {"type":"function","name":"create_worktree","parameters":{"type":"object",
           "properties":{"allowAsync":{"type":"boolean","enum":[true]}},"required":["allowAsync"]}}
        ]}]}
        """);
    Kv converted = new ProtocolConversionService().request(body, "responses", "gemini", "test", true);
    Kv declaration = ProxyJson.object(ProxyJson.list(ProxyJson.object(ProxyJson.list(converted.get("tools")).getFirst()).get("functionDeclarations")).getFirst());
    Kv parameters = ProxyJson.object(declaration.get("parametersJsonSchema"));
    Kv async = ProxyJson.object(ProxyJson.object(parameters.get("properties")).get("allowAsync"));
    assertFalse(async.containsKey("enum"));
    assertEquals("boolean", async.getStr("type"));
    assertEquals(List.of("allowAsync"), parameters.get("required"));
    assertSame(body, new ProtocolConversionService().request(body, "responses", "responses", "test", true));
  }

  @Test
  public void removesDiscriminatorMetadataButPreservesPropertyNamesAndExamples() {
    Kv original = ProxyJson.parse("""
        {"type":"object","discriminator":{"propertyName":"kind"},"properties":{
          "discriminator":{"type":"string","description":"User field"},
          "nested":{"oneOf":[{"type":"object","discriminator":{"propertyName":"type"}}]},
          "rows":{"type":"array","items":{"type":"object","discriminator":{"propertyName":"type"}}},
          "example":{"default":{"discriminator":"keep"},"examples":[{"discriminator":"keep"}]}
        },"$defs":{"variant":{"discriminator":{"propertyName":"kind"},"type":"object"}},
        "additionalProperties":{"type":"object","discriminator":{"propertyName":"type"}}}
        """);
    String before = JsonUtils.toJson(original);
    Kv converted = new GeminiSchemaService().convert(original);
    assertFalse(converted.containsKey("discriminator"));
    Kv properties = ProxyJson.object(converted.get("properties"));
    assertEquals("User field", ProxyJson.object(properties.get("discriminator")).getStr("description"));
    Kv nested = ProxyJson.object(ProxyJson.list(ProxyJson.object(properties.get("nested")).get("oneOf")).getFirst());
    assertFalse(nested.containsKey("discriminator"));
    assertFalse(ProxyJson.object(ProxyJson.object(properties.get("rows")).get("items")).containsKey("discriminator"));
    assertFalse(ProxyJson.object(ProxyJson.object(converted.get("$defs")).get("variant")).containsKey("discriminator"));
    assertFalse(ProxyJson.object(converted.get("additionalProperties")).containsKey("discriminator"));
    Kv example = ProxyJson.object(properties.get("example"));
    assertEquals("keep", ProxyJson.object(example.get("default")).getStr("discriminator"));
    assertEquals("keep", ProxyJson.object(ProxyJson.list(example.get("examples")).getFirst()).getStr("discriminator"));
    assertEquals(before, JsonUtils.toJson(original));
  }

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
    Kv schema = ProxyJson.object(declaration.get("parametersJsonSchema"));
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
