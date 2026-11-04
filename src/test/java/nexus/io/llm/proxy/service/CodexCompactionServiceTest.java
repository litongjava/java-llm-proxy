package nexus.io.llm.proxy.service;

import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import org.junit.Test;
import static org.junit.Assert.*;

public class CodexCompactionServiceTest {
  @Test public void nativeTriggerPreservesInputAndCanonicalOutput() {
    Kv user = Kv.create().set("role", "user").set("content", "Keep my constraints");
    Kv reasoning = Kv.create().set("type", "reasoning").set("encrypted_content", "signed-state");
    List<?> input = List.of(user, reasoning, Kv.create().set("role", "assistant").set("content", "Work"));
    Kv original = Kv.create().set("model", "gpt-6-astra").set("input", input);
    Kv request = CodexCompactionService.request(original);
    List<?> outgoing = ProxyJson.list(request.get("input"));
    assertEquals(4, outgoing.size());
    assertSame(reasoning, outgoing.get(1));
    assertEquals("compaction_trigger", ProxyJson.object(outgoing.getLast()).getStr("type"));
    assertEquals(3, input.size());
    assertEquals(Boolean.TRUE, request.get("stream"));
    Kv encrypted = Kv.create().set("type", "compaction").set("encrypted_content", "opaque-new-state");
    Kv usage = Kv.create().set("input_tokens", 80).set("output_tokens", 20);
    Kv result = CodexCompactionService.compacted(Kv.create().set("id", "r").set("status", "completed")
        .set("output", List.of(encrypted)).set("usage", usage), input);
    assertEquals("response.compaction", result.getStr("object"));
    assertEquals(List.of(user, encrypted), result.get("output"));
    assertSame(usage, result.get("usage"));
  }
  @Test public void ordinaryProvidersKeepTheirStandaloneEndpoint() {
    Provider provider = new Provider();
    provider.upstreamType = "codex";
    assertFalse(CodexCompactionService.supports(provider, "/responses/compact"));
    provider.authType = "oauth";
    assertTrue(CodexCompactionService.supports(provider, "/responses/compact"));
    assertFalse(CodexCompactionService.supports(provider, "/responses"));
  }
  @Test(expected=BusinessException.class) public void rejectsIncompleteNativeResponse() {
    CodexCompactionService.compacted(Kv.create().set("status", "incomplete").set("output", List.of()), List.of());
  }
  @Test(expected=BusinessException.class) public void rejectsOrdinaryTextAsCompaction() {
    CodexCompactionService.compacted(Kv.create().set("status", "completed").set("output", List.of(
        Kv.create().set("type", "message").set("content", "This is just text"))), List.of());
  }
}
