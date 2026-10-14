package nexus.io.llm.proxy.validation;

import nexus.io.llm.proxy.model.PluginQuotaInput;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class PluginParameterValidator {
  private PluginParameterValidator() { }

  public static String authIndex(HttpRequest request) {
    String index = request.getParam("auth_index", request.getParam("authIndex"));
    if ((index == null || index.isBlank()) && request.getBody() != null && request.getBody().length > 0) {
      PluginQuotaInput input = request.getBodyObject(PluginQuotaInput.class);
      if (input != null) { index = input.auth_index == null ? input.authIndex : input.auth_index; }
    }
    return ParameterValidator.text(index, "auth_index", 256);
  }
}
