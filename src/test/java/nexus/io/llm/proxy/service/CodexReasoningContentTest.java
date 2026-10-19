package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;

public class CodexReasoningContentTest {
  private final OAuthTransportService transport = new OAuthTransportService();

  @Test
  public void removesEveryReasoningItemFromStreamedAndNonStreamedPayloads() {
    for (boolean clientStream : new boolean[]{false, true}) {
      Kv body = reasoningRequest();
      body.set("stream", clientStream);
      List<?> originalInput = ProxyJson.list(body.get("input"));
      Kv originalReasoning = ProxyJson.object(originalInput.get(0));

      Kv outbound = transport.payload(codex("oauth"), body, "gpt-6-astra", "/responses");
      List<?> input = ProxyJson.list(outbound.get("input"));

      assertNotSame(originalInput, input);
      assertEquals(3, input.size());
      assertSame(originalInput.get(1), input.get(0));
      assertSame(originalInput.get(3), input.get(1));
      assertSame(originalInput.get(4), input.get(2));
      assertEquals("Synthetic user message", messageText(ProxyJson.object(input.get(0))));
      assertFunctionCall(ProxyJson.object(input.get(1)), "synthetic arguments");
      assertEquals("Synthetic tool output", ProxyJson.object(input.get(2)).getStr("output"));
      assertEquals("function", ProxyJson.object(ProxyJson.list(outbound.get("tools")).get(0)).getStr("type"));
      assertEquals(Boolean.TRUE, outbound.get("stream"));
      assertEquals(Boolean.FALSE, outbound.get("store"));

      assertEquals("Original input must remain intact", 5, originalInput.size());
      assertTrue("Original reasoning item must remain intact", originalReasoning.containsKey("content"));
      assertEquals("Synthetic reasoning one", contentText(originalReasoning));
      assertEquals("Synthetic summary one", summaryText(originalReasoning));
      assertEquals("synthetic-encrypted-one", originalReasoning.getStr("encrypted_content"));
      assertEquals("Synthetic reasoning two", contentText(ProxyJson.object(originalInput.get(2))));
      assertEquals("synthetic-encrypted-two", ProxyJson.object(originalInput.get(2)).getStr("encrypted_content"));

      String outboundJson = JsonUtils.toJsonWithLongAsNumber(outbound);
      assertFalse(outboundJson.contains("Synthetic reasoning one"));
      assertFalse(outboundJson.contains("Synthetic reasoning two"));
      assertFalse(outboundJson.contains("Synthetic summary one"));
      assertFalse(outboundJson.contains("Synthetic summary two"));
      assertFalse(outboundJson.contains("synthetic-encrypted-one"));
      assertFalse(outboundJson.contains("synthetic-encrypted-two"));
      assertTrue(outboundJson.contains("Synthetic user message"));
      assertTrue(outboundJson.contains("Synthetic tool output"));
    }
  }

  @Test
  public void removesEncryptedOnlyAndContentlessReasoningWhilePreservingMessagesAndCalls() {
    Kv encryptedOnly = Kv.create()
        .set("id", "rs_encrypted_only")
        .set("type", "reasoning")
        .set("encrypted_content", "synthetic-encrypted-only");
    Kv contentless = Kv.create()
        .set("id", "rs_contentless")
        .set("type", "reasoning")
        .set("summary", List.of());
    Kv user = Kv.create()
        .set("type", "message")
        .set("role", "user")
        .set("content", List.of(Kv.create().set("type", "input_text").set("text", "Synthetic user message")));
    Kv assistant = Kv.create()
        .set("type", "message")
        .set("role", "assistant")
        .set("content", List.of(Kv.create().set("type", "output_text").set("text", "Synthetic assistant message")));
    Kv functionCall = Kv.create()
        .set("id", "fc_test_1")
        .set("type", "function_call")
        .set("name", "synthetic_tool")
        .set("arguments", "{\"city\":\"Shanghai\",\"unit\":\"celsius\"}")
        .set("call_id", "call_test_1");
    Kv functionOutput = Kv.create()
        .set("id", "fco_test_1")
        .set("type", "function_call_output")
        .set("call_id", "call_test_1")
        .set("output", "Synthetic tool output");
    List<Object> input = new ArrayList<>(List.of(encryptedOnly, user, contentless, assistant, functionCall, functionOutput));
    Kv body = Kv.create().set("input", input).set("stream", false);

    Kv outbound = transport.payload(codex("oauth"), body, "gpt-6-astra", "/responses");
    List<?> sanitized = ProxyJson.list(outbound.get("input"));

    assertNotSame(input, sanitized);
    assertEquals(4, sanitized.size());
    assertSame(user, sanitized.get(0));
    assertSame(assistant, sanitized.get(1));
    assertSame(functionCall, sanitized.get(2));
    assertSame(functionOutput, sanitized.get(3));
    assertEquals("user", ProxyJson.object(sanitized.get(0)).getStr("role"));
    assertEquals("Synthetic user message", messageText(ProxyJson.object(sanitized.get(0))));
    assertEquals("assistant", ProxyJson.object(sanitized.get(1)).getStr("role"));
    assertEquals("Synthetic assistant message", messageText(ProxyJson.object(sanitized.get(1))));
    assertFunctionCall(ProxyJson.object(sanitized.get(2)), "{\"city\":\"Shanghai\",\"unit\":\"celsius\"}");
    Kv arguments = ProxyJson.parse(ProxyJson.object(sanitized.get(2)).getStr("arguments"));
    assertEquals("Shanghai", arguments.getStr("city"));
    assertEquals("celsius", arguments.getStr("unit"));
    assertEquals("Synthetic tool output", ProxyJson.object(sanitized.get(3)).getStr("output"));

    assertEquals("Original input must remain intact", 6, input.size());
    assertSame(encryptedOnly, input.get(0));
    assertTrue(encryptedOnly.containsKey("encrypted_content"));
    assertFalse(encryptedOnly.containsKey("content"));
    assertSame(contentless, input.get(2));
    assertTrue(contentless.containsKey("summary"));
    assertFalse(contentless.containsKey("content"));
  }

  @Test
  public void handlesMissingStringEmptyAndAllReasoningInput() {
    Kv missing = Kv.create().set("stream", false);
    Kv missingOutbound = transport.payload(codex("oauth"), missing, "gpt-6-astra", "/responses");
    assertFalse(missingOutbound.containsKey("input"));
    assertEquals(Boolean.TRUE, missingOutbound.get("stream"));
    assertEquals(Boolean.FALSE, missingOutbound.get("store"));

    Kv stringBody = Kv.create().set("input", "Synthetic plain input").set("stream", false);
    Kv stringOutbound = transport.payload(codex("oauth"), stringBody, "gpt-6-astra", "/responses");
    assertEquals("Synthetic plain input", stringOutbound.getStr("input"));

    List<Object> empty = new ArrayList<>();
    Kv emptyBody = Kv.create().set("input", empty).set("stream", false);
    Kv emptyOutbound = transport.payload(codex("oauth"), emptyBody, "gpt-6-astra", "/responses");
    List<?> emptyInput = ProxyJson.list(emptyOutbound.get("input"));
    assertNotSame(empty, emptyInput);
    assertTrue(emptyInput.isEmpty());
    assertTrue(empty.isEmpty());

    Kv encryptedOnly = Kv.create()
        .set("id", "rs_encrypted_only")
        .set("type", "reasoning")
        .set("encrypted_content", "synthetic-encrypted-only");
    Kv contentless = Kv.create()
        .set("id", "rs_contentless")
        .set("type", "reasoning")
        .set("summary", List.of());
    List<Object> allReasoning = new ArrayList<>(List.of(encryptedOnly, contentless));
    Kv allReasoningBody = Kv.create().set("input", allReasoning).set("stream", false);
    Kv allReasoningOutbound = transport.payload(codex("oauth"), allReasoningBody, "gpt-6-astra", "/responses");
    List<?> allReasoningInput = ProxyJson.list(allReasoningOutbound.get("input"));
    assertNotSame(allReasoning, allReasoningInput);
    assertTrue(allReasoningInput.isEmpty());
    assertEquals("Original input must remain intact", 2, allReasoning.size());
    assertSame(encryptedOnly, allReasoning.get(0));
    assertSame(contentless, allReasoning.get(1));
    assertTrue(encryptedOnly.containsKey("encrypted_content"));
    assertTrue(contentless.containsKey("summary"));
  }

  @Test
  public void removesReasoningOnlyForOAuthCodexNonCompactAstraRequests() {
    assertReasoningItemKept("gpt-6-sol", codex("oauth"), "/responses");
    assertReasoningItemKept("gpt-6-astra", codex("api-key"), "/responses");
    assertReasoningItemKept("gpt-6-astra", provider("oauth", "openai"), "/responses");
    assertReasoningItemKept("gpt-6-astra", codex("oauth"), "/responses/compact");

    Kv compact = reasoningRequest();
    compact.set("stream", false);
    Kv outbound = transport.payload(codex("oauth"), compact, "gpt-6-astra", "/responses/compact");
    assertEquals(Boolean.FALSE, outbound.get("stream"));
  }

  private void assertReasoningItemKept(String model, Provider provider, String endpoint) {
    Kv body = reasoningRequest();
    body.set("stream", false);
    Kv outbound = transport.payload(provider, body, model, endpoint);
    List<?> input = ProxyJson.list(outbound.get("input"));
    assertEquals(5, input.size());
    Kv reasoning = ProxyJson.object(input.get(0));
    assertTrue(model + " " + provider.authType + " " + provider.upstreamType + " " + endpoint, reasoning.containsKey("content"));
    assertEquals("Synthetic reasoning one", contentText(reasoning));
    assertEquals("Synthetic summary one", summaryText(reasoning));
    assertEquals("synthetic-encrypted-one", reasoning.getStr("encrypted_content"));
  }

  private static void assertFunctionCall(Kv functionCall, String arguments) {
    assertEquals("function_call", functionCall.getStr("type"));
    assertEquals("synthetic_tool", functionCall.getStr("name"));
    assertEquals(arguments, functionCall.getStr("arguments"));
    assertEquals("call_test_1", functionCall.getStr("call_id"));
  }

  private static String summaryText(Kv reasoning) {
    return ProxyJson.object(ProxyJson.list(reasoning.get("summary")).get(0)).getStr("text");
  }

  private static String contentText(Kv reasoning) {
    return ProxyJson.object(ProxyJson.list(reasoning.get("content")).get(0)).getStr("text");
  }

  private static String messageText(Kv message) {
    return ProxyJson.object(ProxyJson.list(message.get("content")).get(0)).getStr("text");
  }

  private static Provider codex(String authType) {
    return provider(authType, "codex");
  }

  private static Provider provider(String authType, String upstreamType) {
    Provider provider = new Provider();
    provider.authType = authType;
    provider.upstreamType = upstreamType;
    return provider;
  }

  private static Kv reasoningRequest() {
    return ProxyJson.parse("""
        {
          "model": "gpt-6-astra",
          "stream": false,
          "input": [
            {
              "id": "rs_test_1",
              "type": "reasoning",
              "summary": [{"type": "summary_text", "text": "Synthetic summary one"}],
              "content": [{"type": "reasoning_text", "text": "Synthetic reasoning one"}],
              "encrypted_content": "synthetic-encrypted-one"
            },
            {
              "id": "msg_test_1",
              "type": "message",
              "role": "user",
              "content": [{"type": "input_text", "text": "Synthetic user message"}]
            },
            {
              "id": "rs_test_2",
              "type": "reasoning",
              "summary": [{"type": "summary_text", "text": "Synthetic summary two"}],
              "content": [{"type": "reasoning_text", "text": "Synthetic reasoning two"}],
              "encrypted_content": "synthetic-encrypted-two"
            },
            {
              "id": "fc_test_1",
              "type": "function_call",
              "name": "synthetic_tool",
              "arguments": "synthetic arguments",
              "call_id": "call_test_1"
            },
            {
              "id": "fco_test_1",
              "type": "function_call_output",
              "call_id": "call_test_1",
              "output": "Synthetic tool output"
            }
          ],
          "tools": [{"type": "function", "name": "synthetic_tool", "parameters": {"type": "object"}}]
        }
        """);
  }
}
