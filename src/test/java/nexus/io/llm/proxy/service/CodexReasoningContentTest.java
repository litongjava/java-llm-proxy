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
  public void removesEveryReasoningContentFromStreamedAndNonStreamedPayloads() {
    for (boolean clientStream : new boolean[]{false, true}) {
      Kv body = reasoningRequest();
      body.set("stream", clientStream);
      List<?> originalInput = ProxyJson.list(body.get("input"));
      Kv originalReasoning = ProxyJson.object(originalInput.get(0));

      Kv outbound = transport.payload(codex("oauth"), body, "gpt-6-astra", "/responses");
      List<?> input = ProxyJson.list(outbound.get("input"));

      assertEquals(5, input.size());
      assertReasoningSanitized(ProxyJson.object(input.get(0)), "rs_test_1", "Synthetic summary one", "synthetic-encrypted-one");
      assertReasoningSanitized(ProxyJson.object(input.get(2)), "rs_test_2", "Synthetic summary two", "synthetic-encrypted-two");
      assertEquals("Synthetic user message", messageText(ProxyJson.object(input.get(1))));
      assertFunctionCall(ProxyJson.object(input.get(3)));
      assertEquals("Synthetic tool output", ProxyJson.object(input.get(4)).getStr("output"));
      assertEquals("function", ProxyJson.object(ProxyJson.list(outbound.get("tools")).get(0)).getStr("type"));
      assertEquals(Boolean.TRUE, outbound.get("stream"));
      assertEquals(Boolean.FALSE, outbound.get("store"));

      assertTrue("Original reasoning item must remain intact", originalReasoning.containsKey("content"));
      String outboundJson = JsonUtils.toJsonWithLongAsNumber(outbound);
      assertFalse(outboundJson.contains("Synthetic reasoning one"));
      assertFalse(outboundJson.contains("Synthetic reasoning two"));
      assertTrue(outboundJson.contains("Synthetic summary one"));
      assertTrue(outboundJson.contains("synthetic-encrypted-one"));
      assertTrue(outboundJson.contains("Synthetic user message"));
    }
  }

  @Test
  public void copiesReasoningItemsInsteadOfMutatingSharedInputs() {
    Kv reasoning = Kv.create()
        .set("id", "rs_shared")
        .set("type", "reasoning")
        .set("summary", List.of(Kv.create().set("type", "summary_text").set("text", "Synthetic shared summary")))
        .set("content", List.of(Kv.create().set("type", "reasoning_text").set("text", "Synthetic shared reasoning")))
        .set("encrypted_content", "synthetic-shared-encrypted");
    List<Object> input = new ArrayList<>();
    input.add(reasoning);
    Kv body = Kv.create().set("input", input).set("stream", false);

    Kv outbound = transport.payload(codex("oauth"), body, "gpt-6-astra", "/responses");
    List<?> sanitized = ProxyJson.list(outbound.get("input"));

    assertNotSame(input, sanitized);
    assertSame(reasoning, input.get(0));
    assertTrue(reasoning.containsKey("content"));
    assertNotSame(reasoning, sanitized.get(0));
    Kv copy = ProxyJson.object(sanitized.get(0));
    assertFalse(copy.containsKey("content"));
    assertEquals("Synthetic shared summary", summaryText(copy));
    assertEquals("synthetic-shared-encrypted", copy.getStr("encrypted_content"));
    assertEquals("Synthetic shared reasoning", contentText(reasoning));
  }

  @Test
  public void handlesMissingStringAndContentlessInput() {
    Kv missing = Kv.create().set("stream", false);
    Kv missingOutbound = transport.payload(codex("oauth"), missing, "gpt-6-astra", "/responses");
    assertFalse(missingOutbound.containsKey("input"));
    assertEquals(Boolean.TRUE, missingOutbound.get("stream"));
    assertEquals(Boolean.FALSE, missingOutbound.get("store"));

    Kv stringBody = Kv.create().set("input", "Synthetic plain input").set("stream", false);
    Kv stringOutbound = transport.payload(codex("oauth"), stringBody, "gpt-6-astra", "/responses");
    assertEquals("Synthetic plain input", stringOutbound.getStr("input"));

    Kv contentless = Kv.create().set("id", "rs_empty").set("type", "reasoning").set("summary", List.of());
    Kv message = Kv.create().set("type", "message").set("content", "Synthetic message");
    Kv body = Kv.create().set("input", List.of(contentless, "Synthetic top-level string item", message)).set("stream", false);
    Kv outbound = transport.payload(codex("oauth"), body, "gpt-6-astra", "/responses");
    List<?> input = ProxyJson.list(outbound.get("input"));
    assertEquals(3, input.size());
    Kv copied = ProxyJson.object(input.get(0));
    assertEquals("reasoning", copied.getStr("type"));
    assertEquals("rs_empty", copied.getStr("id"));
    assertFalse(copied.containsKey("content"));
    assertEquals("Synthetic top-level string item", input.get(1));
    assertEquals(message, input.get(2));
  }

  @Test
  public void sanitizesOnlyOAuthCodexNonCompactAstraRequests() {
    assertReasoningContentKept("gpt-6-sol", codex("oauth"), "/responses");
    assertReasoningContentKept("gpt-6-astra", codex("api-key"), "/responses");
    assertReasoningContentKept("gpt-6-astra", provider("oauth", "openai"), "/responses");
    assertReasoningContentKept("gpt-6-astra", codex("oauth"), "/responses/compact");

    Kv compact = reasoningRequest();
    compact.set("stream", false);
    Kv outbound = transport.payload(codex("oauth"), compact, "gpt-6-astra", "/responses/compact");
    assertEquals(Boolean.FALSE, outbound.get("stream"));
  }

  private void assertReasoningContentKept(String model, Provider provider, String endpoint) {
    Kv body = reasoningRequest();
    body.set("stream", false);
    Kv outbound = transport.payload(provider, body, model, endpoint);
    Kv reasoning = ProxyJson.object(ProxyJson.list(outbound.get("input")).get(0));
    assertTrue(model + " " + provider.authType + " " + provider.upstreamType + " " + endpoint, reasoning.containsKey("content"));
    assertEquals("Synthetic reasoning one", contentText(reasoning));
  }

  private static void assertReasoningSanitized(Kv reasoning, String id, String summary, String encrypted) {
    assertEquals("reasoning", reasoning.getStr("type"));
    assertEquals(id, reasoning.getStr("id"));
    assertFalse(reasoning.containsKey("content"));
    assertEquals(summary, summaryText(reasoning));
    assertEquals(encrypted, reasoning.getStr("encrypted_content"));
  }

  private static void assertFunctionCall(Kv functionCall) {
    assertEquals("function_call", functionCall.getStr("type"));
    assertEquals("synthetic_tool", functionCall.getStr("name"));
    assertEquals("synthetic arguments", functionCall.getStr("arguments"));
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
