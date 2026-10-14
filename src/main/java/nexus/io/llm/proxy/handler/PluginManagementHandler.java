package nexus.io.llm.proxy.handler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.NativePlugin;
import nexus.io.llm.proxy.service.PluginHostCallbacks;
import nexus.io.llm.proxy.service.PluginService;
import nexus.io.llm.proxy.service.ProxyJson;
import nexus.io.model.body.RespBodyVo;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class PluginManagementHandler {
  private final String pluginId;
  private final String path;
  private final String method;
  private final boolean resource;
  public PluginManagementHandler(String pluginId, String path, String method, boolean resource) {
    this.pluginId = pluginId;
    this.path = path;
    this.method = method;
    this.resource = resource;
  }
  public HttpResponse handle(HttpRequest request) throws Exception {
    NativePlugin plugin = Aop.get(PluginService.class).require(pluginId);
    String effectiveMethod = resource ? "GET" : request.getHeader("X-Proxy-Method");
    if (effectiveMethod == null || effectiveMethod.isBlank()) { effectiveMethod = method; }
    String selectedMethod = effectiveMethod.toUpperCase();
    boolean registered = ProxyJson.list(plugin.management.get(resource ? "resources" : "routes")).stream().anyMatch(value -> {
      Kv route = ProxyJson.object(value);
      String routePath = route.getStr("Path");
      String base = resource ? "/v0/resource/plugins/" : "/v8/management/plugins/";
      if (routePath != null && !routePath.startsWith("/")) { routePath = base + pluginId + "/" + routePath; }
      return path.equals(routePath) && selectedMethod.equals(resource ? "GET" : ProxyJson.text(route, "Method", "GET").toUpperCase());
    });
    BusinessException.require(registered, 405, "PLUGIN_METHOD", "Plugin operation is not registered");
    Kv headers = Kv.create();
    request.getHeaders().forEach((key, value) -> headers.set(key, List.of(value)));
    Kv query = Kv.create();
    request.getParams().forEach((key, value) -> query.set(key, value));
    String body = request.getBodyString();
    Kv result = plugin.call("management.handle", Kv.create().set("Method", selectedMethod).set("Path", path).set("Headers", headers)
        .set("Query", query).set("Body", PluginHostCallbacks.encode((body == null ? "" : body).getBytes(StandardCharsets.UTF_8))));
    HttpResponse response = TioRequestContext.getResponse();
    int status = (int) ProxyJson.number(result, "StatusCode", 200);
    response.setStatus(status == 0 ? 200 : status);
    ProxyJson.object(result.get("Headers")).forEach((key, value) -> {
      for (Object item : ProxyJson.list(value)) { response.addHeader(key.toString(), item.toString()); }
    });
    byte[] bytes = PluginHostCallbacks.decode(result.get("Body"));
    if (resource) { response.setBody(bytes); return response; }
    String text = new String(bytes, StandardCharsets.UTF_8);
    Object data;
    try { data = text.startsWith("{") ? ProxyJson.parse(text) : text; }
    catch (RuntimeException error) { data = text; }
    return response.respond(RespBodyVo.ok(data));
  }
}
