package nexus.io.llm.proxy.service;

import java.util.List;
import java.util.ArrayList;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.llm.proxy.exception.OAuthRequestException;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;

/** Dispatches account-specific catalogs without duplicating provider construction. */
public class NativeChannelService {
  private static final class Catalog {
    volatile long nextAttempt;
    volatile long staleUntil;
    List<Kv> models;
  }

  private final ConcurrentHashMap<String, Catalog> catalogs = new ConcurrentHashMap<>();

  protected long now() {
    return System.currentTimeMillis();
  }

  public boolean supports(String provider) {
    return provider != null && List.of("cline", "minimax", "buddy", "workbuddy", "lobsterai", "trae", "raccoon", "codearts", "loomy", "zcode", "qoder", "qodercn").contains(provider);
  }

  public String protocol(String provider) {
    return "minimax".equals(provider) || "zcode".equals(provider) ? "anthropic" : "openai";
  }

  public String baseUrl(String provider) {
    return switch (provider) {
      case "qoder", "qodercn" -> Aop.get(QoderService.class).apiBaseUrl(provider);
      case "cline" -> Aop.get(ClineService.class).apiBaseUrl();
      case "minimax" -> Aop.get(MiniMaxService.class).apiBaseUrl();
      case "buddy", "workbuddy" -> Aop.get(BuddyService.class).apiBaseUrl(provider);
      case "lobsterai" -> Aop.get(LobsterAiService.class).apiBaseUrl() + "/api/proxy/v1";
      case "trae" -> Aop.get(TraeService.class).apiBaseUrl();
      case "raccoon" -> Aop.get(RaccoonService.class).apiBaseUrl();
      case "codearts" -> Aop.get(CodeArtsService.class).apiBaseUrl();
      case "loomy" -> Aop.get(LoomyService.class).apiBaseUrl();
      case "zcode" -> Aop.get(ZCodeService.class).apiBaseUrl();
      default -> throw new IllegalArgumentException("Unknown native channel");
    };
  }

  public List<Kv> models(String provider, Kv credential) throws Exception {
    Kv identity = Kv.create();
    if (credential != null) {
      identity.set(credential);
      identity.remove("models");
    }
    // Hash credentials rather than retaining raw secrets in cache keys. Channel and organization
    // fields remain part of identity, preventing cross-account and cross-entitlement reuse.
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(JsonUtils.toJson(identity).getBytes(StandardCharsets.UTF_8)));
    String key = provider + "|" + baseUrl(provider) + "|" + digest;
    long now = now();
    final long pruningTime = now;
    if (catalogs.size() >= 256) {
      catalogs.entrySet().removeIf(entry -> entry.getValue().staleUntil < pruningTime && entry.getValue().nextAttempt < pruningTime);
    }
    Catalog catalog = catalogs.get(key);
    if (catalog == null) {
      if (catalogs.size() >= 256) {
        return copy(discoverModels(provider, credential));
      }
      catalog = catalogs.computeIfAbsent(key, ignored -> new Catalog());
    }
    synchronized (catalog) {
      now = now();
      if (catalog.nextAttempt > now) {
        if (catalog.models != null && catalog.staleUntil > now) {
          return copy(catalog.models);
        }
        throw new OAuthRequestException("Native model discovery is temporarily unavailable; retry shortly");
      }
      long ttl = Math.max(1, Math.min(300, EnvUtils.getInt("proxy.oauth.catalog-ttl-seconds", 60))) * 1000L;
      long stale = Math.max(ttl, Math.min(900, Math.max(0, EnvUtils.getInt("proxy.oauth.catalog-stale-seconds", 300))) * 1000L);
      try {
        List<Kv> fetched = discoverModels(provider, credential);
        // A successful empty response is authoritative and replaces a previously nonempty list.
        catalog.models = copy(fetched);
        catalog.nextAttempt = now + ttl;
        catalog.staleUntil = now + stale;
        return copy(catalog.models);
      } catch (Exception error) {
        catalog.nextAttempt = now + 5000;
        if (catalog.models != null && catalog.staleUntil > now) {
          return copy(catalog.models);
        }
        throw error;
      }
    }
  }

  private List<Kv> copy(List<Kv> models) {
    List<Kv> result = new ArrayList<>();
    for (Kv model : models) {
      result.add(ProxyJson.parse(JsonUtils.toJson(model)));
    }
    return result;
  }

  protected List<Kv> discoverModels(String provider, Kv credential) throws Exception {
    return switch (provider) {
      case "qoder", "qodercn" -> Aop.get(QoderModelService.class).models(provider);
      case "cline" -> Aop.get(ClineService.class).models(credential);
      case "minimax" -> Aop.get(MiniMaxService.class).models(credential);
      case "buddy", "workbuddy" -> credential == null ? List.of() : Aop.get(BuddyService.class).models(provider, credential);
      case "lobsterai" -> credential == null ? List.of() : Aop.get(LobsterAiService.class).models(credential);
      case "trae" -> credential == null ? List.of() : Aop.get(TraeService.class).models(credential);
      case "raccoon" -> Aop.get(RaccoonService.class).models(credential);
      case "codearts" -> credential == null ? List.of() : Aop.get(CodeArtsService.class).models(credential);
      case "loomy" -> credential == null ? List.of() : Aop.get(LoomyService.class).models(credential);
      case "zcode" -> credential == null ? List.of() : Aop.get(ZCodeService.class).models(credential);
      default -> List.of();
    };
  }
}
