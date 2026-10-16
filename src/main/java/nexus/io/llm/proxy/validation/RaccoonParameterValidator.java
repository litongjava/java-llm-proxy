package nexus.io.llm.proxy.validation;

import nexus.io.llm.proxy.model.RaccoonSmsInput;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class RaccoonParameterValidator {
  private RaccoonParameterValidator() { }

  public static void sms(RaccoonSmsInput input, boolean sending) {
    ParameterValidator.require(input != null, "SMS request is required");
    input.phone = ParameterValidator.text(input.phone, "phone", 11);
    ParameterValidator.require(input.phone.matches("1[3-9][0-9]{9}"), "A mainland China mobile number is required");
    if (sending) {
      input.captcha_param = ParameterValidator.text(input.captcha_param, "captcha_param", 16384);
    } else {
      input.sms_code = ParameterValidator.text(input.sms_code, "sms_code", 8);
      ParameterValidator.require(input.sms_code.matches("[0-9]{4,8}"), "Invalid SMS code");
    }
  }
}
