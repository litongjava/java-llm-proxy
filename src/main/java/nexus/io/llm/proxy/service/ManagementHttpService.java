package nexus.io.llm.proxy.service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.ManagementApiCall;
import nexus.io.llm.proxy.model.ManagementApiResponse;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Credentials;

public class ManagementHttpService {
  public OkHttpClient client(String proxyUrl, int timeoutSeconds) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false);
    if (proxyUrl != null && !proxyUrl.isBlank() && !"direct".equals(proxyUrl) && !"none".equals(proxyUrl)) {
      URI uri = URI.create(proxyUrl);
      BusinessException.require(uri.getScheme() != null && uri.getHost() != null
          && (uri.getScheme().equals("http") || uri.getScheme().equals("socks5") || uri.getScheme().equals("socks")),
          400, "INVALID_PROXY", "Proxy must be an HTTP or SOCKS URL");
      Proxy.Type type = uri.getScheme().startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
      builder.proxy(new Proxy(type, new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8080)));
      if (uri.getRawUserInfo() != null) {
        BusinessException.require(type == Proxy.Type.HTTP, 400, "PROXY_AUTH", "Authenticated SOCKS requires a configured system transport");
        String[] user = uri.getRawUserInfo().split(":", 2);
        String authorization = Credentials.basic(URLDecoder.decode(user[0], StandardCharsets.UTF_8),
            user.length > 1 ? URLDecoder.decode(user[1], StandardCharsets.UTF_8) : "");
        builder.proxyAuthenticator((route, response) -> response.request().header("Proxy-Authorization") == null
            ? response.request().newBuilder().header("Proxy-Authorization", authorization).build() : null);
      }
    } else {
      builder.proxy(Proxy.NO_PROXY);
    }
    return builder.build();
  }

  public ManagementApiResponse call(ManagementApiCall input) throws Exception {
    ManagementParameterValidator.apiCall(input);
    String index = input.authIndex == null ? input.auth_index : input.authIndex;
    Kv credential = Kv.create();
    String token = null;
    if (index != null && !index.isBlank()) {
      CredentialService credentials = Aop.get(CredentialService.class);
      credential = credentials.refreshed(credentials.resolve(index), false);
      token = credentials.accessToken(credential);
    }
    Request.Builder request = new Request.Builder().url(input.url);
    String contentType = "application/json";
    for (Object rawName : input.header.keySet()) {
      String name = rawName.toString();
      String value = String.valueOf(input.header.get(name));
      if (value.contains("$TOKEN$")) {
        BusinessException.require(token != null && !token.isBlank(), 400, "MISSING_TOKEN", "Credential has no usable token");
        value = value.replace("$TOKEN$", token);
      }
      request.header(name, value);
      if ("content-type".equalsIgnoreCase(name)) {
        contentType = value;
      }
    }
    RequestBody body = !"GET".equals(input.method) && !"HEAD".equals(input.method) && (ListMethods.requiresBody(input.method) || input.data != null)
        ? RequestBody.create(input.data == null ? "" : input.data, MediaType.parse(contentType)) : null;
    request.method(input.method, body);
    String configuredProxy = configuredProxy();
    String proxy = input.proxy_url != null ? input.proxy_url : ProxyJson.text(credential, "proxy_url", configuredProxy);
    NativePlugin plugin = credential.getStr("type") == null ? null : Aop.get(PluginService.class).provider(credential.getStr("type"), true);
    if (plugin != null) {
      Kv result = plugin.call("executor.http_request", Kv.create().set("AuthID", Aop.get(CredentialService.class).resolve(index))
          .set("AuthProvider", credential.get("type")).set("Method", input.method).set("URL", input.url)
          .set("Headers", Kv.create().set(request.build().headers().toMultimap())).set("Body", PluginHostCallbacks.encode((input.data == null ? "" : input.data).getBytes(StandardCharsets.UTF_8)))
          .set("StorageJSON", PluginHostCallbacks.encode(JsonUtils.toJson(credential).getBytes(StandardCharsets.UTF_8)))
          .set("Metadata", credential).set("Attributes", Kv.create()));
      return new ManagementApiResponse((int) ProxyJson.number(result, "StatusCode", 200), ProxyJson.object(result.get("Headers")), PluginHostCallbacks.text(result.get("Body")));
    }
    try (Response response = UniChatClient.execute(client(proxy, 60), request.build())) {
      Kv headers = Kv.create().set(response.headers().toMultimap());
      byte[] bytes = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(16 * 1024 * 1024 + 1);
      BusinessException.require(bytes.length <= 16 * 1024 * 1024, 413, "BODY_LIMIT", "API response is too large");
      String text = new String(bytes, StandardCharsets.UTF_8);
      return new ManagementApiResponse(response.code(), headers, text);
    }
  }

  public String configuredProxy() {
    DesktopConfigService config = Aop.get(DesktopConfigService.class);
    Object legacy = config.section("proxy-url");
    return ProxyJson.text(ProxyJson.object(config.section("requests")), "proxy-url",
        legacy instanceof String value && !value.isBlank() ? value : EnvUtils.get("proxy.outbound-proxy", ""));
  }

  private static final class ListMethods {
    private static boolean requiresBody(String method) {
      return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }
  }
}
