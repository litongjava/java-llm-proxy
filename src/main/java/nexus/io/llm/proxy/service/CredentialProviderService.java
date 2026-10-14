package nexus.io.llm.proxy.service;

import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.CredentialInfo;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;

public class CredentialProviderService {
  public List<Provider> providers() {
    List<Provider> providers = new ArrayList<>();
    try {
      CredentialService service = Aop.get(CredentialService.class);
      for (CredentialInfo info : service.list()) {
        if (info.disabled || info.unavailable || info.type == null) {
          continue;
        }
        Kv credential = service.read(info.name);
        if (ProxyJson.list(credential.get("excluded_models")).contains("*")) {
          continue;
        }
        Provider provider = new Provider();
        provider.id = info.auth_index;
        provider.name = info.name;
        provider.credentialName = info.name;
        provider.authType = "oauth";
        provider.upstreamType = info.type;
        provider.metadata = credential;
        provider.headers = ProxyJson.object(credential.get("headers"));
        provider.protocol = switch (info.type) {
          case "codex" -> "responses";
          case "claude", "kimi", "kimi-ai" -> "anthropic";
          case "antigravity", "vertex" -> "gemini";
          default -> "openai";
        };
        String base = switch (info.type) {
          case "codex" -> "https://chatgpt.com/backend-api/codex";
          case "claude" -> "https://api.anthropic.com/v1";
          case "kimi" -> "https://api.kimi.com/coding/v1";
          case "kimi-ai" -> "https://api.kimi.ai/coding/v1";
          case "xai" -> "https://cli-chat-proxy.grok.com/v1";
          case "meta" -> "https://api.meta.ai/v1";
          case "antigravity" -> "https://cloudcode-pa.googleapis.com/v1internal";
          case "vertex" -> "https://" + ProxyJson.text(credential, "location", "us-central1") + "-aiplatform.googleapis.com/v1/projects/"
              + credential.getStr("project_id") + "/locations/" + ProxyJson.text(credential, "location", "us-central1") + "/publishers/google/models";
          case "devin" -> "https://server.codeium.com";
          default -> "https://api.openai.com/v1";
        };
        provider.baseUrl = ProxyJson.text(credential, "base_url", EnvUtils.get("proxy.oauth." + info.type + ".api-base-url", base)).replaceAll("/+$", "");
        List<?> availableModels = ProxyJson.list(credential.get("models"));
        if (availableModels.isEmpty()) { availableModels = Aop.get(ModelCatalogService.class).definitions(info.type); }
        for (Object item : availableModels) {
          Kv details = ProxyJson.object(item);
          String model = item instanceof String ? item.toString() : ProxyJson.text(details, "id", ProxyJson.text(details, "ID", details.getStr("name")));
          if (model != null) {
            provider.models.add(model);
          }
        }
        providers.add(provider);
      }
      return providers;
    } catch (Exception error) {
      throw new IllegalStateException("Cannot load OAuth providers", error);
    }
  }

  public Provider authorize(Provider provider) {
    if (provider.credentialName == null) {
      return provider;
    }
    try {
      CredentialService service = Aop.get(CredentialService.class);
      Kv credential = service.refreshed(provider.credentialName, false);
      provider.metadata = credential;
      if (provider.metadata.getStr("id") == null) { provider.metadata.set("id", provider.credentialName); }
      provider.apiKey = service.accessToken(credential);
      BusinessException.require(provider.apiKey != null || Aop.get(PluginService.class).provider(provider.upstreamType, true) != null,
          401, "NO_ACCESS_TOKEN", "Credential has no access token");
      return provider;
    } catch (BusinessException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("Cannot refresh OAuth provider", error);
    }
  }
}
