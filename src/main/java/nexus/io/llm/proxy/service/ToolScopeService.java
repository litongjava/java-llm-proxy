package nexus.io.llm.proxy.service;

import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.http.common.HttpRequest;

/** Tool identity scopes aliases only; authentication remains in the interceptor. */
public class ToolScopeService {
  public static final List<String> TOOLS = List.of("claude-code", "claude-desktop", "codex", "opencode",
      "openclaw", "hermes", "deepseek-harness", "zcode", "workbuddy", "antigravity-cli", "copilot-cli",
      "vscode-copilot", "kimi-code", "grok-build", "omp", "pi");

  public String tool(HttpRequest request) {
    String path = request.getRequestURI();
    if (path.startsWith("/tools/")) {
      String name = path.substring(7).split("/", 2)[0];
      return TOOLS.contains(name) ? name : "";
    }
    String header = request.getHeader("x-llm-proxy-tool");
    if (TOOLS.contains(header == null ? "" : header)) {
      return header;
    }
    String agent = request.getHeader("user-agent");
    String normalized = agent == null ? "" : agent.toLowerCase(Locale.ROOT);
    if (normalized.contains("claude-cli") || normalized.contains("claude-code")) {
      return "claude-code";
    }
    if (normalized.contains("claude") && (normalized.contains("electron") || normalized.contains("desktop"))) {
      return "claude-desktop";
    }
    for (String tool : TOOLS) {
      if (normalized.equals(tool) || normalized.startsWith(tool + "/") || normalized.startsWith(tool + " ")) {
        return tool;
      }
    }
    return "";
  }

  public Kv config(String tool) {
    Kv root = ProxyJson.object(Aop.get(DesktopConfigService.class).get(""));
    return ProxyJson.object(ProxyJson.object(root.get("tool-model-aliases")).get(tool));
  }

  public List<?> aliases(Provider provider, String tool, List<Kv> legacy) {
    if (tool == null || tool.isBlank()) {
      return List.of();
    }
    Kv scope = config(tool);
    if (scope.isEmpty() && "claude-desktop".equals(tool)) {
      return legacy;
    }
    if (provider.credentialName != null) {
      return ProxyJson.list(ProxyJson.object(scope.get("oauth-model-alias")).get(provider.upstreamType));
    }
    for (Object value : ProxyJson.list(scope.get("providers"))) {
      Kv entry = ProxyJson.object(value);
      if (provider.upstreamType != null && provider.upstreamType.equals(entry.getStr("section"))
          && identity(provider).equals(entry.getStr("identity"))) {
        return ProxyJson.list(entry.get("models"));
      }
    }
    return List.of();
  }

  public String identity(Provider provider) {
    Kv metadata = provider.metadata;
    String key = "openai-compatibility".equals(provider.upstreamType) ? "name" : "api-key";
    String value = provider.upstreamType + "\n" + ProxyJson.text(metadata, "base-url", "") + "\n"
        + ProxyJson.text(metadata, "prefix", "") + "\n" + ProxyJson.text(metadata, key, "");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("Cannot identify alias provider", error);
    }
  }
}
