package nexus.io.llm.proxy.validation;

import java.nio.charset.StandardCharsets;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.LiveCallRequest;
import nexus.io.model.upload.UploadFile;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class LiveRequestValidator {
  private LiveRequestValidator() { }

  public static LiveCallRequest input(HttpRequest request) {
    ParameterValidator.require(request.getBody() != null && request.getBody().length <= 16 * 1024 * 1024,
        "Live request body is required and must not exceed 16 MiB");
    String type = request.getHeader("content-type");
    LiveCallRequest input;
    if (type != null && type.toLowerCase().startsWith("multipart/form-data")) {
      input = new LiveCallRequest();
      input.sdp = field(request, "sdp");
      String session = field(request, "session");
      if (session != null) {
        input.session = JsonUtils.parse(session, Kv.class);
        ParameterValidator.require(input.session != null, "session must be a JSON object");
      }
    } else if (type != null && (type.startsWith("application/sdp") || type.startsWith("text/plain"))) {
      input = new LiveCallRequest();
      input.sdp = new String(request.getBody(), StandardCharsets.UTF_8);
    } else {
      input = request.getBodyObject(LiveCallRequest.class);
      input.extensions = request.getBodyObject(Kv.class);
      for (String key : new String[] {"sdp", "model", "session"}) {
        input.extensions.remove(key);
      }
    }
    ParameterValidator.require(input != null && input.sdp != null && !input.sdp.isBlank(), "sdp is required");
    return input;
  }

  private static String field(HttpRequest request, String name) {
    Object[] values = request.getParams().get(name);
    if (values == null || values.length == 0) {
      return null;
    }
    Object value = values[0];
    return value instanceof UploadFile file ? new String(file.getData(), StandardCharsets.UTF_8) : String.valueOf(value);
  }

  public static String callId(HttpRequest request) {
    String path = request.getRequestURI();
    String value = path.endsWith("/realtime") ? request.getParam("call_id") : path.substring(path.lastIndexOf('/') + 1);
    ParameterValidator.require(validId(value), "Invalid live call ID");
    return value;
  }

  public static boolean validId(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
  }
}
