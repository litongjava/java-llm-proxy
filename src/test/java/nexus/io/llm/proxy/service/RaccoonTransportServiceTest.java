package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import com.jfinal.kit.Kv;
import nexus.io.model.exception.BusinessException;
import org.junit.Test;

public class RaccoonTransportServiceTest {
  @Test
  public void thinkingControlsUseProviderExtraBodyWithoutInventedIntensity() {
    RaccoonTransportService service = new RaccoonTransportService();
    Kv body = Kv.create().set("reasoning_effort", "off").set("extra_body", Kv.create().set("existing", true));
    service.payload(body, "/chat/completions");
    assertEquals("disabled", ProxyJson.object(ProxyJson.object(body.get("extra_body")).get("thinking")).getStr("type"));
    assertEquals(Boolean.TRUE, ProxyJson.object(body.get("extra_body")).getBoolean("existing"));
    assertFalse(body.containsKey("reasoning_effort"));
    assertEquals(Boolean.TRUE, body.getBoolean("stream"));
    try {
      service.payload(Kv.create().set("reasoning_effort", "high"), "/chat/completions");
      fail("Raccoon exposes on/off only");
    } catch (BusinessException expected) {
      assertEquals("RACCOON_REASONING_UNSUPPORTED", expected.getCode());
    }
  }
}
