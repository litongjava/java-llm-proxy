package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class PayloadRuleTest {
  @Test
  public void appliesConditionalNestedDefaultsOverridesRawAndRemoval() {
    Kv payload = ProxyJson.parse("""
        {"metadata":{"client":"codex"},"temperature":0.2,"tools":[{"type":"web_search"}],"reasoning":{"effort":"low"}}
        """);
    Kv rules = ProxyJson.parse("""
        {
          "default":[{"models":[{"name":"gpt-*","protocol":"codex"}],"params":{"temperature":1,"max_output_tokens":500}}],
          "override":[{"models":[{"name":"gpt-*","from-protocol":"openai","headers":{"X-Tier":"pro-*"},"match":[{"metadata.client":"codex"}],"exist":["tools.#(type==\\\"web_search\\\").type"]}],"params":{"reasoning.effort":"high"}}],
          "override-raw":[{"models":[{"name":"gpt-*"}],"params":{"response_format":"{\\\"type\\\":\\\"json_object\\\"}"}}],
          "filter":[{"models":[{"name":"gpt-*"}],"params":["metadata.client"]}]
        }
        """);
    new PayloadRuleService().applyRules(payload, rules, "gpt-fixture", "responses", "openai", Kv.create().set("x-tier", "pro-eu"));
    assertEquals("0.2", payload.get("temperature").toString());
    assertEquals("high", ProxyJson.object(payload.get("reasoning")).getStr("effort"));
    assertEquals("json_object", ProxyJson.object(payload.get("response_format")).getStr("type"));
    assertFalse(ProxyJson.object(payload.get("metadata")).containsKey("client"));
    assertEquals(500, ProxyJson.number(payload, "max_output_tokens", 0));
  }
}
