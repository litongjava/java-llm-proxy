package nexus.io.llm.proxy.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import com.jfinal.kit.Kv;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.CredentialInfo;
import nexus.io.llm.proxy.model.UsageQuery;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;

public class CredentialService {
  public List<CredentialInfo> listWithUsage() throws Exception {
    List<CredentialInfo> entries = list();
    StorageService storage = Aop.get(StorageService.class);
    for (CredentialInfo entry : entries) {
      UsageQuery query = new UsageQuery();
      query.provider = entry.name;
      query.pageSize = 1;
      Kv stats = ProxyJson.object(storage.usage(query).get("stats"));
      long success = ProxyJson.number(stats, "success", 0);
      entry.success = BigInteger.valueOf(success);
      entry.failed = BigInteger.valueOf(ProxyJson.number(stats, "total", 0) - success);
      entry.recent_requests = storage.recentRequests(entry.name);
    }
    return entries;
  }

  public String importCredential(String name, Kv value) throws Exception {
    String provider = value.getStr("type");
    for (NativePlugin plugin : Aop.get(PluginService.class).enabled("auth_provider")) {
      if (provider != null && !provider.equals(plugin.authProvider)) { continue; }
      Kv parsed = plugin.call("auth.parse", Kv.create().set("Provider", provider).set("FileName", name)
          .set("RawJSON", PluginHostCallbacks.encode(JsonUtils.toJson(value).getBytes(StandardCharsets.UTF_8))).set("Host", Kv.create()));
      if (!Boolean.TRUE.equals(parsed.getBoolean("Handled"))) { continue; }
      List<Object> auths = new ArrayList<>(ProxyJson.list(parsed.get("Auths")));
      if (!ProxyJson.object(parsed.get("Auth")).isEmpty()) { auths.add(parsed.get("Auth")); }
      BusinessException.require(!auths.isEmpty(), 422, "PLUGIN_AUTH", "Plugin recognized the file but returned no credentials");
      String saved = null;
      for (Object auth : auths) {
        Kv entry = ProxyJson.object(auth);
        if (auths.size() == 1 && entry.getStr("FileName") == null) { entry.set("FileName", name); }
        saved = Aop.get(OAuthService.class).savePluginAuth(entry);
      }
      return saved;
    }
    return save(name, value);
  }

  public Path directory() throws Exception {
    String explicit = EnvUtils.get("proxy.auth-dir");
    Path path;
    if (explicit != null && !explicit.isBlank()) {
      path = Path.of(explicit);
    } else {
      Kv config = ProxyJson.object(Aop.get(DesktopConfigService.class).get(""));
      String configured = ProxyJson.text(ProxyJson.object(config.get("oauth")), "auth-dir", ProxyJson.text(config, "auth-dir", "oauth"));
      Path base = Path.of(EnvUtils.get("proxy.config", "data/config.yaml")).toAbsolutePath().getParent();
      path = base.resolve(configured);
    }
    Files.createDirectories(path);
    return path.toRealPath();
  }

  private Path file(String name) throws Exception {
    Path root = directory();
    Path path = root.resolve(ManagementParameterValidator.filename(name)).normalize();
    BusinessException.require(path.startsWith(root) && !Files.isSymbolicLink(path), 400, "INVALID_PATH", "Invalid credential path");
    return path;
  }

  public synchronized Kv read(String name) throws Exception {
    Path path = file(name);
    BusinessException.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), 404, "NOT_FOUND", "Credential not found");
    BusinessException.require(Files.size(path) <= 4 * 1024 * 1024, 400, "TOO_LARGE", "Credential file is too large");
    return ProxyJson.parse(Files.readString(path));
  }

  public synchronized String save(String name, Kv value) throws Exception {
    ManagementParameterValidator.credential(value);
    if (name == null || name.isBlank()) {
      name = ManagementParameterValidator.identifier(value.getStr("type")) + "-" + SnowflakeIdUtils.id() + ".json";
    }
    Path path = file(name);
    Path temp = Files.createTempFile(directory(), "credential-", ".tmp");
    try {
      Files.writeString(temp, JsonUtils.toJson(value));
      try {
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException error) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
    return name;
  }

  public synchronized Kv patch(Kv changes) throws Exception {
    String name = ManagementParameterValidator.filename(changes.getStr("name"));
    Kv credential = read(name);
    for (Object rawKey : changes.keySet()) {
      String key = rawKey.toString();
      if ("name".equals(key) || "auth_index".equals(key)) {
        continue;
      }
      Object value = changes.get(key);
      if (value == null) {
        credential.remove(key);
      } else if ("headers".equals(key)) {
        Kv headers = ProxyJson.object(credential.get(key));
        ProxyJson.object(value).forEach((header, item) -> {
          if (item == null || item.toString().isEmpty()) {
            headers.remove(header);
          } else {
            headers.put(header, item);
          }
        });
        credential.set(key, headers);
      } else {
        credential.set(key, value);
      }
    }
    save(name, credential);
    return Kv.create().set("status", "ok");
  }

  public synchronized Kv delete(String name) throws Exception {
    BusinessException.require(Files.deleteIfExists(file(name)), 404, "NOT_FOUND", "Credential not found");
    return Kv.create().set("status", "ok").set("deleted", 1);
  }

  public List<CredentialInfo> list() throws Exception {
    List<CredentialInfo> files = new ArrayList<>();
    try (Stream<Path> paths = Files.list(directory())) {
      for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")
          && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
        CredentialInfo info = new CredentialInfo();
        info.name = path.getFileName().toString();
        info.id = info.name;
        info.auth_index = index(info.name);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        info.size = attributes.size();
        info.created_at = attributes.creationTime().toInstant().toString();
        info.updated_at = attributes.lastModifiedTime().toInstant().toString();
        try {
          Kv value = read(info.name);
          info.type = value.getStr("type");
          info.provider = info.type;
          info.email = value.getStr("email");
          info.note = value.getStr("note");
          info.prefix = value.getStr("prefix");
          info.disabled = Boolean.TRUE.equals(value.get("disabled"));
          info.last_refresh = value.getStr("last_refresh");
          info.status = info.disabled ? "disabled" : "active";
          for (String key : List.of("account_id", "project_id", "plan_type", "priority", "weight")) {
            if (value.containsKey(key)) {
              info.metadata.set(key, value.get(key));
            }
          }
        } catch (Exception error) {
          info.status = "error";
          info.status_message = "Invalid credential file";
          info.unavailable = true;
        }
        files.add(info);
      }
    }
    return files;
  }

  public String resolve(String index) throws Exception {
    for (CredentialInfo info : list()) {
      if (info.auth_index.equals(index) || info.name.equals(index)) {
        return info.name;
      }
    }
    BusinessException.require(false, 404, "NOT_FOUND", "Credential not found");
    return null;
  }

  public Kv refresh(Kv input) throws Exception {
    List<String> names = new ArrayList<>();
    if (input.getStr("name") != null) {
      names.add(input.getStr("name"));
    } else {
      for (Object value : ProxyJson.list(input.get("names"))) {
        names.add(String.valueOf(value));
      }
    }
    BusinessException.require(!names.isEmpty(), 400, "MISSING_NAMES", "Select credentials to refresh");
    List<Kv> results = new ArrayList<>();
    int succeeded = 0;
    for (String name : names) {
      try {
        refreshed(name, true);
        succeeded++;
        results.add(Kv.create().set("name", name).set("status", "ok"));
      } catch (Exception error) {
        results.add(Kv.create().set("name", name).set("status", "error").set("error", "Credential refresh failed"));
      }
    }
    return Kv.create().set("results", results).set("success", succeeded).set("failed", results.size() - succeeded);
  }

  public synchronized Kv refreshed(String name, boolean force) throws Exception {
    Kv value = read(name);
    String expiry = ProxyJson.text(value, "expired", ProxyJson.text(value, "expires_at", ""));
    long expiresAt = Long.MAX_VALUE;
    if (!expiry.isBlank()) {
      try {
        expiresAt = Instant.parse(expiry).getEpochSecond();
      } catch (Exception ignored) {
        try {
          expiresAt = Long.parseLong(expiry);
        } catch (NumberFormatException invalid) {
          expiresAt = 0;
        }
      }
    }
    if (force || ("vertex".equals(value.getStr("type")) && accessToken(value) == null)
        || expiresAt < Instant.now().getEpochSecond() + 120) {
      value = Aop.get(OAuthService.class).refreshCredential(value);
      save(name, value);
    }
    return value;
  }

  public String accessToken(Kv credential) {
    for (String name : List.of("access_token", "api_key", "token", "id_token", "cookie")) {
      Object value = credential.get(name);
      if (value instanceof String text && !text.isBlank()) {
        return text;
      }
    }
    Kv token = ProxyJson.object(credential.get("token"));
    return token.getStr("access_token");
  }

  public String index(String name) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest).substring(0, 16);
  }
}
