package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.RequestRecord;
import org.junit.Test;

public class UpstreamSyncTest {
  @Test public void codexProactiveRefreshUses96HoursOnlyWithARefreshToken() {
    var now = java.time.Instant.parse("2026-10-22T00:00:00Z");
    Kv credential = Kv.create().set("type", "codex").set("refresh_token", "test")
        .set("expired", now.plusSeconds(95 * 3600).toString());
    assertTrue(CredentialService.refreshDue(credential, now));
    credential.set("expired", now.plusSeconds(97 * 3600).toString());
    assertFalse(CredentialService.refreshDue(credential, now));
    credential.remove("refresh_token");
    credential.set("expired", now.plusSeconds(3600).toString());
    assertFalse(CredentialService.refreshDue(credential, now));
    credential.set("expired", now.plusSeconds(60).toString());
    assertTrue(CredentialService.refreshDue(credential, now));
    credential.remove("expired");
    assertFalse(CredentialService.refreshDue(credential, now));
  }

  @Test public void geminiToolSignaturesSurviveRoundTripAndStreaming() throws Exception {
    var converter = new ProtocolConversionService();
    Kv gemini = ProxyJson.parse("""
        {"contents":[{"role":"model","parts":[{"functionCall":{"name":"lookup","args":{}},"thoughtSignature":"signed-value"}]}]}
        """);
    Kv chat = converter.request(gemini, "gemini", "openai", "m", false);
    Kv roundTrip = converter.request(chat, "openai", "gemini", "m", false);
    Kv part = ProxyJson.object(ProxyJson.list(ProxyJson.object(ProxyJson.list(roundTrip.get("contents")).getFirst()).get("parts")).getFirst());
    assertEquals("signed-value", part.getStr("thoughtSignature"));
    String source = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"lookup\",\"args\":{}},\"thoughtSignature\":\"signed-value\"}]},\"finishReason\":\"STOP\"}]}\n\n";
    String translated;
    try (var stream = new ProtocolEventStream(new java.io.ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "gemini", "openai", "m")) {
      translated = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(translated.contains("\"thought_signature\":\"signed-value\""));
    }
    try (var stream = new ProtocolEventStream(new java.io.ByteArrayInputStream(translated.getBytes(StandardCharsets.UTF_8)), "openai", "gemini", "m")) {
      assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("\"thoughtSignature\":\"signed-value\""));
    }
  }

  @Test public void localSchemaReferencesExpandWithoutChangingLiteralData() {
    Kv source = ProxyJson.parse("""
        {"$defs":{"a/b":{"type":"object","properties":{"child":{"$ref":"#/$defs/a~1b"}}}},
         "properties":{"one":{"$ref":"#/$defs/a~1b","description":"override"},
                       "two":{"$ref":"#/$defs/a~1b"}},
         "default":{"$ref":"literal","readOnly":true}}
        """);
    Kv result = new GeminiSchemaService().convert(source);
    assertFalse(result.containsKey("$defs"));
    Kv properties = ProxyJson.object(result.get("properties"));
    Kv one = ProxyJson.object(properties.get("one"));
    assertEquals("object", one.getStr("type"));
    assertEquals("override", one.getStr("description"));
    assertEquals("object", ProxyJson.object(properties.get("two")).getStr("type"));
    assertEquals(source.get("default"), result.get("default"));
    assertTrue(source.containsKey("$defs"));
    assertFalse(ProxyJson.object(ProxyJson.object(one.get("properties")).get("child")).containsKey("$ref"));
  }

  @Test public void claudeTerminalReasonsMatchInBufferedAndStreamingResponses() throws Exception {
    for (String reason : List.of("max_tokens", "refusal", "model_context_window_exceeded")) {
      Kv body = Kv.create().set("content", List.of(Kv.create().set("type", "text").set("text", "partial")))
          .set("stop_reason", reason).set("usage", Kv.create());
      Kv result = new ProtocolConversionService().response(body, "anthropic", "responses", "m");
      String status = reason.equals("model_context_window_exceeded") ? "failed" : "incomplete";
      assertEquals(status, result.getStr("status"));
      String source = "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + reason
          + "\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n";
      try (var stream = new ProtocolEventStream(new java.io.ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "anthropic", "responses", "m")) {
        String translated = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(translated.contains("event: response." + status));
        assertFalse(translated.contains("event: response.completed"));
      }
    }
  }

  @Test public void quotaTimeoutBudgetAppliesToReadingAndWriting() {
    var client = new ManagementHttpService().client("direct", 60);
    assertEquals(60000, client.callTimeoutMillis());
    assertEquals(60000, client.readTimeoutMillis());
    assertEquals(60000, client.writeTimeoutMillis());
  }

  @Test public void flatReasoningUsageSurvivesBufferedAndFragmentedSse() {
    String body = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":6,\"reasoning_tokens\":4}}";
    for (boolean stream : new boolean[] {false, true}) {
      RequestRecord record = new RequestRecord();
      var formatter = new ProtocolFormatService();
      if (stream) {
        var accumulator = new StreamUsageAccumulator(formatter, record);
        for (byte b : ("data: " + body + "\n\n").getBytes(StandardCharsets.UTF_8)) {
          accumulator.accept(new byte[] {b}, 1);
        }
      } else { formatter.usage(record, body); }
      assertEquals(4, record.reasoningTokens);
      assertEquals(6, record.outputTokens);
      assertEquals(10, record.inputTokens);
    }
  }

  @Test public void responsesRetainsReasoningDetailsIncludingExplicitZero() {
    for (String details : List.of("\"reasoning_tokens\":4", "\"completion_tokens_details\":{\"reasoning_tokens\":4}",
        "\"output_tokens_details\":{\"reasoning_tokens\":4}")) {
      Kv input = ProxyJson.parse("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":6," + details + "}}");
      Kv result = new ProtocolConversionService().response(input, "openai", "responses", "model");
      assertEquals(4, ProxyJson.number(ProxyJson.object(ProxyJson.object(result.get("usage")).get("output_tokens_details")), "reasoning_tokens", -1));
    }
  }

  @Test public void toolChoiceAndExplicitParallelFalseSurviveProtocolConversion() {
    var service = new ProtocolConversionService();
    Kv chat = ProxyJson.parse("""
        {"messages":[{"role":"user","content":"hi"}],"parallel_tool_calls":false,
         "tool_choice":{"type":"function","function":{"name":"lookup"}},
         "tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}]}
        """);
    for (boolean stream : new boolean[] {false, true}) {
      Kv responses = service.request(chat, "openai", "responses", "m", stream);
      assertEquals(false, responses.get("parallel_tool_calls"));
      Kv claude = service.request(responses, "responses", "anthropic", "m", stream);
      Kv choice = ProxyJson.object(claude.get("tool_choice"));
      assertEquals("tool", choice.getStr("type"));
      assertEquals("lookup", choice.getStr("name"));
      assertEquals(true, choice.get("disable_parallel_tool_use"));
      assertEquals(false, service.request(claude, "anthropic", "openai", "m", stream).get("parallel_tool_calls"));
    }
  }

  @Test public void schemaMetadataRemovalPreservesNamesAndLiteralValues() {
    for (String keyword : List.of("readOnly", "writeOnly", "contentMediaType", "contentEncoding")) {
      Kv original = Kv.create().set(keyword, true).set("properties", Kv.create().set(keyword,
          Kv.create().set("type", "string").set(keyword, true).set("default", Kv.create().set(keyword, "keep"))));
      Kv converted = new GeminiSchemaService().convert(original);
      assertFalse(converted.containsKey(keyword));
      Kv property = ProxyJson.object(ProxyJson.object(converted.get("properties")).get(keyword));
      assertEquals("string", property.getStr("type"));
      assertFalse(property.containsKey(keyword));
      assertEquals("keep", ProxyJson.object(property.get("default")).get(keyword));
      assertTrue(original.containsKey(keyword));
    }
  }
}
