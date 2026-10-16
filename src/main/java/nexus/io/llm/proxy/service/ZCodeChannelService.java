package nexus.io.llm.proxy.service;

import java.util.Objects;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ZCodeChannelInput;
import nexus.io.llm.proxy.model.ZCodeChannelResult;
import nexus.io.llm.proxy.validation.ZCodeParameterValidator;
import nexus.io.model.exception.BusinessException;

/** Explicit management operation; changing to coding-plan never happens as a retry fallback. */
public class ZCodeChannelService {
  protected CredentialService credentials() {
    return Aop.get(CredentialService.class);
  }

  protected ZCodeService zcode() {
    return Aop.get(ZCodeService.class);
  }

  public ZCodeChannelResult change(ZCodeChannelInput input) throws Exception {
    ZCodeParameterValidator.channel(input);
    CredentialService store = credentials();
    Kv initial = store.read(input.name);
    BusinessException.require("zcode".equals(initial.getStr("type")), 400, "ZCODE_CREDENTIAL_REQUIRED", "This operation requires a ZCode credential");
    Kv selected = initial;
    if ("coding-plan".equals(input.channel)) {
      // This cloud read is performed only in response to an explicit channel selection.
      selected = zcode().resolveCodingPlanKey(initial);
    }
    synchronized (store) {
      Kv latest = store.read(input.name);
      BusinessException.require("zcode".equals(latest.getStr("type"))
          && Objects.equals(initial.getStr("access_token"), latest.getStr("access_token")),
          409, "ZCODE_CREDENTIAL_CHANGED", "The credential changed during channel selection; try again");
      latest.set("zcode_channel", input.channel);
      if ("coding-plan".equals(input.channel)) {
        latest.set("coding_plan_key", selected.get("coding_plan_key"));
      }
      latest.set("base_url", zcode().apiBaseUrl(latest));
      // Do not retain a manual or cached model override from the other channel.
      latest.remove("models");
      store.save(input.name, latest);
      String key = latest.getStr("coding_plan_key");
      return new ZCodeChannelResult(input.name, input.channel, key != null && !key.isBlank());
    }
  }
}
