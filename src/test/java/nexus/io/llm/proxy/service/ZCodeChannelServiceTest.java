package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.ZCodeChannelInput;
import nexus.io.llm.proxy.model.ZCodeChannelResult;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;

public class ZCodeChannelServiceTest {
  private static class Store extends CredentialService {
    Kv value = Kv.create().set("type", "zcode").set("access_token", "private-jwt").set("zcode_channel", "start-plan")
        .set("models", "old-models");
    int saves;
    @Override
    public synchronized Kv read(String name) {
      return Kv.create().set(value);
    }
    @Override
    public synchronized String save(String name, Kv value) {
      saves++;
      this.value = value;
      return name;
    }
  }

  private static class Channel extends ZCodeChannelService {
    final Store store = new Store();
    int cloudReads;
    boolean fail;
    boolean changeDuringRead;
    @Override
    protected CredentialService credentials() { return store; }
    @Override
    protected ZCodeService zcode() {
      return new ZCodeService() {
        @Override
        public Kv resolveCodingPlanKey(Kv credential) {
          cloudReads++;
          BusinessException.require(!fail, 404, "ZCODE_CODING_KEY_MISSING", "No existing key");
          if (changeDuringRead) {
            store.value.set("access_token", "replacement-jwt");
          }
          return Kv.create().set(credential).set("coding_plan_key", "private-api-key");
        }
      };
    }
  }

  private ZCodeChannelInput input(String channel) {
    ZCodeChannelInput input = new ZCodeChannelInput();
    input.name = "zcode-test.json";
    input.channel = channel;
    return input;
  }

  @Test
  public void explicitSwitchReadsKeyOnceAndReturnsOnlySafeFields() throws Exception {
    Channel service = new Channel();
    ZCodeChannelResult result = service.change(input("coding-plan"));
    assertEquals(1, service.cloudReads);
    assertEquals(1, service.store.saves);
    assertEquals("coding-plan", result.getChannel());
    assertTrue(result.isCodingPlanKeyAvailable());
    assertEquals("https://api.z.ai/api/anthropic/v1", service.store.value.getStr("base_url"));
    assertFalse(service.store.value.containsKey("models"));
    String json = JsonUtils.toJson(result);
    assertEquals("coding-plan", ProxyJson.parse(json).getStr("channel"));
    assertEquals("zcode-test.json", ProxyJson.parse(json).getStr("name"));
    assertEquals(Boolean.TRUE, ProxyJson.parse(json).getBoolean("codingPlanKeyAvailable"));
    assertFalse(json.contains("private"));
    assertFalse(json.contains("access_token"));
    assertFalse(json.contains("coding_plan_key"));
    service.change(input("start-plan"));
    assertEquals(1, service.cloudReads);
    assertEquals("start-plan", service.store.value.getStr("zcode_channel"));
    assertTrue(service.store.value.getStr("base_url").contains("/zcode-plan/anthropic/v1"));
  }

  @Test
  public void failureOrCredentialReplacementDoesNotCommitPartialSwitch() throws Exception {
    for (boolean replaced : new boolean[]{false, true}) {
      Channel service = new Channel();
      service.fail = !replaced;
      service.changeDuringRead = replaced;
      try {
        service.change(input("coding-plan"));
        fail("Expected channel change rejection");
      } catch (BusinessException expected) {
        assertEquals(replaced ? "ZCODE_CREDENTIAL_CHANGED" : "ZCODE_CODING_KEY_MISSING", expected.getCode());
      }
      assertEquals(0, service.store.saves);
      assertEquals("start-plan", service.store.value.getStr("zcode_channel"));
      assertNull(service.store.value.getStr("coding_plan_key"));
    }
  }

  @Test
  public void invalidChannelAndNonZcodeCredentialDoNotReadCloudKeys() throws Exception {
    Channel service = new Channel();
    try {
      service.change(input("unknown"));
      fail("Invalid channel must fail");
    } catch (RuntimeException expected) {
      assertEquals(0, service.cloudReads);
    }
    service.store.value.set("type", "cline");
    try {
      service.change(input("coding-plan"));
      fail("Different provider must fail");
    } catch (BusinessException expected) {
      assertEquals("ZCODE_CREDENTIAL_REQUIRED", expected.getCode());
    }
    assertEquals(0, service.cloudReads);
  }
}
