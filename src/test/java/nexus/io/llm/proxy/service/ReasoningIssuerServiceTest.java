package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/** Encrypted reasoning may only go back to the account and model that issued it. */
public class ReasoningIssuerServiceTest {
  private final ReasoningIssuerService service = new ReasoningIssuerService();

  private static Provider provider(String id, String apiKey) {
    Provider provider = new Provider();
    provider.id = id;
    provider.apiKey = apiKey;
    provider.metadata = Kv.create();
    return provider;
  }

  private void observe(String session, String issuer, String itemId) {
    Kv frame = Kv.create().set("type", "response.output_item.done")
        .set("item", Kv.create().set("id", itemId).set("type", "reasoning").set("summary", List.of()));
    service.observe(session, issuer, ("data: " + JsonUtils.toJsonWithLongAsNumber(frame) + "\n\n").getBytes(StandardCharsets.UTF_8));
  }

  private static Kv payload(String itemId) {
    return ProxyJson.parse("""
        {"input":[
          {"type":"reasoning","id":"%s","encrypted_content":"opaque","summary":[{"type":"summary_text","text":"think"}]},
          {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}
        ]}
        """.formatted(itemId));
  }

  private static boolean hasEncrypted(Kv payload) {
    return JsonUtils.toJsonWithLongAsNumber(payload).contains("opaque");
  }

  @Test
  public void replaysOnlyToTheIssuingAccountAndModel() {
    Provider openai = provider("proxy-1", "sk-a");
    String issuer = service.issuer(openai, "gpt-6.2-sol");
    observe("session-a", issuer, "rs_1");

    Kv same = payload("rs_1");
    service.filter(same, "responses", "session-a", openai, "gpt-6.2-sol");
    assertTrue(hasEncrypted(same));
    assertEquals(2, ProxyJson.list(same.get("input")).size());

    Kv otherModel = payload("rs_1");
    service.filter(otherModel, "responses", "session-a", openai, "gpt-6.2-astra");
    assertFalse(hasEncrypted(otherModel));
    assertEquals(2, ProxyJson.list(otherModel.get("input")).size());

    Kv otherAccount = payload("rs_1");
    service.filter(otherAccount, "responses", "session-a", provider("proxy-1", "sk-b"), "gpt-6.2-sol");
    assertFalse(hasEncrypted(otherAccount));

    Kv otherSession = payload("rs_1");
    service.filter(otherSession, "responses", "session-b", openai, "gpt-6.2-sol");
    assertFalse(hasEncrypted(otherSession));
  }

  @Test
  public void unknownOrEmptyReasoningLeavesNoReplayableState() {
    Provider openai = provider("proxy-1", "sk-a");
    Kv unknown = payload("rs_missing");
    service.filter(unknown, "responses", "session-a", openai, "gpt-6.2-sol");
    assertFalse(hasEncrypted(unknown));

    Kv empty = ProxyJson.parse("""
        {"input":[{"type":"reasoning","id":"rs_empty","encrypted_content":"opaque","summary":[]},
                  {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}]}
        """);
    service.filter(empty, "responses", "session-a", openai, "gpt-6.2-sol");
    assertEquals(1, ProxyJson.list(empty.get("input")).size());
    assertEquals("message", ProxyJson.object(ProxyJson.list(empty.get("input")).get(0)).getStr("type"));
  }

  @Test
  public void providerPolicyOverridesTheAutomaticDecision() {
    Provider openai = provider("proxy-1", "sk-a");
    String issuer = service.issuer(openai, "gpt-6.2-sol");
    observe("session-a", issuer, "rs_1");

    openai.metadata.set("reasoning", "strip");
    Kv stripped = payload("rs_1");
    service.filter(stripped, "responses", "session-a", openai, "gpt-6.2-sol");
    assertFalse(hasEncrypted(stripped));

    openai.metadata.set("reasoning", "pass");
    Kv passed = payload("rs_1");
    service.filter(passed, "responses", "session-a", openai, "gpt-6.2-sol");
    assertTrue(hasEncrypted(passed));
  }

  @Test
  public void otherProtocolsAndMissingInputAreUntouched() {
    Provider openai = provider("proxy-1", "sk-a");
    Kv chat = ProxyJson.parse("""
        {"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}
        """);
    String before = JsonUtils.toJsonWithLongAsNumber(chat);
    service.filter(chat, "openai", "session-a", openai, "gpt-6.2-sol");
    assertEquals(before, JsonUtils.toJsonWithLongAsNumber(chat));
  }

  @Test
  public void identitiesSeparateAccountsAndModels() {
    Provider openai = provider("proxy-1", "sk-a");
    String base = service.issuer(openai, "gpt-6.2-sol");
    assertNotEquals(base, service.issuer(openai, "gpt-6.2-astra"));
    assertNotEquals(base, service.issuer(provider("proxy-1", "sk-b"), "gpt-6.2-sol"));
    assertNotEquals(base, service.issuer(provider("proxy-2", "sk-a"), "gpt-6.2-sol"));
    assertEquals(base, service.issuer(provider("proxy-1", "sk-a"), "gpt-6.2-sol"));
  }
}
