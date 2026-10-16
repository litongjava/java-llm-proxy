package nexus.io.llm.proxy.validation;

import nexus.io.llm.proxy.model.LoomySmsInput;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class LoomyParameterValidator {
  private LoomyParameterValidator() { }
  public static void validate(LoomySmsInput input, boolean login) {
    ParameterValidator.require(input != null, "SMS request is required");
    input.phone = ParameterValidator.text(input.phone, "phone", 11);
    ParameterValidator.require(input.phone.matches("1[3-9][0-9]{9}"), "A valid mainland China mobile number is required");
    if (login) {
      input.code = ParameterValidator.text(input.code, "code", 12);
      ParameterValidator.require(input.code.matches("[0-9]{4,8}"), "Invalid SMS code");
      input.msgid = ParameterValidator.text(input.msgid, "msgid", 1024);
    }
  }
}
