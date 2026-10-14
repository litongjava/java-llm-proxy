package nexus.io.llm.proxy.validation;

import java.net.URI;
import java.util.Locale;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.ManagementApiCall;
import nexus.io.llm.proxy.model.VertexImportInput;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class ManagementParameterValidator {
  private ManagementParameterValidator() { }

  public static String filename(String value) {
    String name = ParameterValidator.text(value, "name", 180);
    ParameterValidator.require(name.endsWith(".json") && !name.contains("/") && !name.contains("\\")
        && !name.contains(":") && !name.contains("..") && !name.startsWith("."), "Invalid credential filename");
    return name;
  }

  public static String identifier(String value) {
    String result = ParameterValidator.text(value, "id", 128);
    ParameterValidator.require(result.matches("[a-zA-Z0-9_-]+"), "Invalid identifier");
    return result;
  }

  public static void credential(Kv input) {
    ParameterValidator.require(input != null && !input.isEmpty(), "Credential must be a JSON object");
    ParameterValidator.text(input.get("type"), "type", 80);
  }

  public static String pluginId(String value) {
    String id = ParameterValidator.text(value, "plugin id", 128);
    ParameterValidator.require(id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), "Invalid plugin identifier");
    return id;
  }

  public static URI httpUrl(String value) {
    String text = ParameterValidator.text(value, "url", 8192);
    URI uri;
    try {
      uri = URI.create(text);
    } catch (IllegalArgumentException error) {
      ParameterValidator.require(false, "Invalid HTTP URL");
      return null;
    }
    ParameterValidator.require(("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
        && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null, "Invalid HTTP URL");
    return uri;
  }

  public static void apiCall(ManagementApiCall input) {
    ParameterValidator.require(input != null, "API request is required");
    input.method = ParameterValidator.choice(input.method == null ? null : input.method.toUpperCase(Locale.ROOT),
        "method", "GET", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    httpUrl(input.url);
    ParameterValidator.require(input.header != null, "header must be an object");
    ParameterValidator.require(input.data == null || input.data.length() <= 16 * 1024 * 1024, "Request body is too large");
  }

  public static void vertex(VertexImportInput input) {
    ParameterValidator.require(input != null && input.credential != null, "Service account is required");
    ParameterValidator.text(input.credential.get("private_key"), "private_key", 32768);
    ParameterValidator.text(input.credential.get("client_email"), "client_email", 512);
    String project = ParameterValidator.text(input.credential.get("project_id"), "project_id", 256);
    ParameterValidator.require(project.matches("[a-zA-Z0-9._:-]+"), "Invalid Google project identifier");
    ParameterValidator.require(input.location == null || input.location.matches("[a-z0-9-]+"), "Invalid Vertex location");
  }
}
