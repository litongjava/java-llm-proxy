package nexus.io.llm.proxy.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import nexus.io.tio.utils.environment.EnvUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public class OAuthTransportService {
  public Kv payload(Provider provider, Kv body, String model, String endpoint) {
    if (!"oauth".equals(provider.authType)) { return body; }
    if ("codex".equals(provider.upstreamType) && !endpoint.endsWith("compact")) {
      body.set("stream", true).set("store", false);
      body.putIfAbsent("instructions", "");
      for (String field : List.of("previous_response_id", "generate", "prompt_cache_retention", "safety_identifier", "stream_options")) { body.remove(field); }
    } else if ("antigravity".equals(provider.upstreamType)) {
      body.remove("model");
      body.remove("stream");
      return Kv.create().set("project", provider.metadata.get("project_id")).set("model", model).set("request", body)
          .set("requestType", "agent").set("userAgent", "antigravity").set("requestId", "agent-" + SnowflakeIdUtils.id());
    }
    return body;
  }
  public String url(Provider provider, String url, String endpoint, boolean stream) {
    if ("antigravity".equals(provider.upstreamType)) {
      return provider.baseUrl + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
    }
    return url;
  }
  public void headers(Provider provider, Request.Builder request) {
    if ("codex".equals(provider.upstreamType)) {
      request.header("originator", "codex_cli_rs").header("OpenAI-Beta", "responses=experimental").header("Accept", "text/event-stream");
    } else if ("kimi".equals(provider.upstreamType) || "kimi-ai".equals(provider.upstreamType)) {
      String version = EnvUtils.get("proxy.client-version", "1.0.0");
      request.header("User-Agent", "JavaLLMProxy/" + version).header("X-Msh-Platform", "JavaLLMProxy")
          .header("X-Msh-Version", version).header("X-Msh-Device-Name", EnvUtils.get("HOSTNAME", EnvUtils.get("COMPUTERNAME", "llm-proxy")))
          .header("X-Msh-Device-Model", PluginService.os() + "/" + PluginService.arch())
          .header("X-Msh-Device-Id", ProxyJson.text(provider.metadata, "device_id", provider.id));
    } else if ("antigravity".equals(provider.upstreamType)) {
      request.header("User-Agent", EnvUtils.get("proxy.oauth.antigravity.user-agent", "antigravity/hub/2.9.1 darwin/arm64 google-api-nodejs-client/10.3.0"))
          .header("X-Goog-Api-Client", "gl-node/22.21.1");
    }
  }
  public Response response(Provider provider, Response response, boolean clientStream) throws Exception {
    if (!response.isSuccessful() || response.body() == null) { return response; }
    String type = response.header("Content-Type", "");
    if ("codex".equals(provider.upstreamType) && !clientStream && type.contains("text/event-stream")) {
      String completed = null;
      try (response; BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
        String line;
        int total = 0;
        while ((line = reader.readLine()) != null) {
          total += line.length();
          BusinessException.require(total <= 32 * 1024 * 1024, 413, "RESPONSE_LIMIT", "Codex response is too large");
          if (!line.startsWith("data:")) { continue; }
          String data = line.substring(5).trim();
          if (data.equals("[DONE]") || data.isBlank()) { continue; }
          Kv event = ProxyJson.parse(data);
          if ("response.completed".equals(event.getStr("type"))) { completed = JsonUtils.toJsonWithLongAsNumber(event.get("response")); break; }
          BusinessException.require(!"response.failed".equals(event.getStr("type")) && !"error".equals(event.getStr("type")), 502, "CODEX_RESPONSE_FAILED", "Codex response failed");
        }
      }
      BusinessException.require(completed != null, 502, "INCOMPLETE_RESPONSE", "Codex stream ended before response.completed");
      return response.newBuilder().removeHeader("Content-Length").header("Content-Type", "application/json")
          .body(ResponseBody.create(completed, MediaType.get("application/json"))).build();
    }
    if ("antigravity".equals(provider.upstreamType)) {
      if (type.contains("text/event-stream")) {
        InputStream input = new UnwrapStream(response.body().byteStream());
        BufferedSource source = Okio.buffer(Okio.source(input));
        ResponseBody body = new ResponseBody() {
          public MediaType contentType() { return MediaType.get("text/event-stream"); }
          public long contentLength() { return -1; }
          public BufferedSource source() { return source; }
        };
        return response.newBuilder().removeHeader("Content-Length").body(body).build();
      }
      String body;
      try (response) { body = unwrap(response.body().string()); }
      return response.newBuilder().removeHeader("Content-Length").body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    return response;
  }
  private static String unwrap(String data) {
    Kv value = ProxyJson.parse(data);
    return value.containsKey("response") ? JsonUtils.toJsonWithLongAsNumber(value.get("response")) : data;
  }
  private static class UnwrapStream extends InputStream {
    private final BufferedReader reader;
    private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);
    UnwrapStream(InputStream input) { reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)); }
    public int read() throws IOException {
      int value;
      while ((value = current.read()) < 0) {
        String line = reader.readLine();
        if (line == null) { return -1; }
        if (line.startsWith("data:")) {
          String data = line.substring(5).trim();
          if (!data.isBlank() && !"[DONE]".equals(data)) { line = "data: " + unwrap(data); }
        }
        current = new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8));
      }
      return value;
    }
    public void close() throws IOException { reader.close(); }
  }
}
