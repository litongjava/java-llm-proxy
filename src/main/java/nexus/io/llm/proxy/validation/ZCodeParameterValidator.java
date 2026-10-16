package nexus.io.llm.proxy.validation;

import nexus.io.llm.proxy.model.ZCodeChannelInput;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class ZCodeParameterValidator {
  private ZCodeParameterValidator() { }

  public static void channel(ZCodeChannelInput input) {
    ParameterValidator.require(input != null, "Channel request is required");
    input.name = ManagementParameterValidator.filename(input.name);
    ParameterValidator.require("start-plan".equals(input.channel) || "coding-plan".equals(input.channel),
        "channel must be start-plan or coding-plan");
  }
}
