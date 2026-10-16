package nexus.io.llm.proxy.model;

/** The captcha proof and SMS code must be supplied by the user. */
public class RaccoonSmsInput {
  public String phone;
  public String captcha_param;
  public String sms_code;
}
