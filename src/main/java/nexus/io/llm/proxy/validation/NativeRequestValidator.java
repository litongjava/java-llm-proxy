package nexus.io.llm.proxy.validation;

import com.jfinal.kit.Kv;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class NativeRequestValidator {
  private NativeRequestValidator() { }
  public static String model(HttpRequest request, Kv body, String fallback) {
    String model = body.getStr("model");
    if (model == null) { model = request.getParam("model"); }
    return ParameterValidator.text(model == null ? fallback : model, "model", 256);
  }
  public static String taskId(HttpRequest request) {
    String value = ParameterValidator.text(request.getParam("id"), "id", 256);
    ParameterValidator.require(value.matches("[A-Za-z0-9_.:-]+") && !value.contains(".."), "Invalid upstream task ID");
    return value;
  }
}
