package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReasoningConversionTest {
  @Test public void preservesEffortAcrossResponsesChatRoundTrip() {
    ProtocolConversionService service = new ProtocolConversionService();
    for (String level : new String[]{"none", "low", "medium", "high", "xhigh"}) {
      Kv original = ProxyJson.parse("{\"input\":\"hi\",\"reasoning\":{\"effort\":\"" + level + "\"}}");
      Kv chat = service.request(original, "responses", "openai", "test", true);
      assertEquals(level, chat.getStr("reasoning_effort"));
      Kv responses = service.request(chat, "openai", "responses", "test", true);
      assertEquals(level, ProxyJson.object(responses.get("reasoning")).getStr("effort"));
    }
    Kv absent = service.request(ProxyJson.parse("{\"input\":\"hi\"}"), "responses", "openai", "test", true);
    assertFalse(absent.containsKey("reasoning_effort"));
  }
}
