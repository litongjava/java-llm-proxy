package nexus.io.llm.proxy.interceptor;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.SettingsService;
import nexus.io.llm.proxy.service.PluginHookService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.common.RequestLine;
import nexus.io.tio.http.common.HttpMethod;
import nexus.io.tio.http.server.intf.HttpRequestInterceptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class ProxyTokenInterceptor implements HttpRequestInterceptor {
  @Override
  public HttpResponse doBeforeHandler(HttpRequest request, RequestLine line, HttpResponse response) {
    if (request.getRequestURI().startsWith("/v0/resource/plugins/")
        || (line.getMethod() == HttpMethod.GET && request.getRequestURI().equals("/v8/management/oauth/callback"))) {
      response.addHeader("Cache-Control", "no-store");
      return null;
    }
    boolean management = request.getRequestURI().startsWith("/api/")
        || request.getRequestURI().startsWith("/v8/management/");
    String token = request.getBearerToken();
    if (token == null || token.isBlank()) {
      token = request.getHeader("x-api-key");
    }
    if (token == null || token.isBlank()) {
      token = request.getHeader("x-goog-api-key");
    }
    if (!management && (token == null || token.isBlank())) {
      token = request.getParam("key");
    }
    String identity = Aop.get(SettingsService.class).authenticate(token, management);
    if (!management) {
      try { identity = Aop.get(PluginHookService.class).authenticate(request, identity); }
      catch (Exception error) { response.setStatus(503); return response.respond(RespBodyVo.fail(503, "Authentication plugin failed")); }
    }
    response.addHeader("Cache-Control", "no-store");
    if (identity == null) {
      response.setStatus(401);
      return response.respond(RespBodyVo.fail(401, "Invalid or missing access token"));
    }
    request.setLocalAttribute("proxy.identity", identity);
    try {
      String owner = token == null || token.isBlank() ? identity : token;
      request.setLocalAttribute("proxy.owner", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8))));
    } catch (Exception error) { throw new IllegalStateException("Cannot identify API key", error); }
    return null;
  }

  @Override
  public void doAfterHandler(HttpRequest request, RequestLine line, HttpResponse response, long cost) { }
}
