package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.ApiKey;
import nexus.io.llm.proxy.model.ModelAlias;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public class DesktopConfigService {
  private String appliedContent;

  private Path path() {
    return Path.of(EnvUtils.get("proxy.config", "data/config.yaml")).toAbsolutePath();
  }

  private Yaml yaml() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(4 * 1024 * 1024);
    return new Yaml(new SafeConstructor(options));
  }

  public synchronized String text() {
    try {
      return Files.exists(path()) ? Files.readString(path()) : "{}\n";
    } catch (Exception error) {
      throw new IllegalStateException("Cannot read desktop configuration", error);
    }
  }

  public synchronized Object get(String node) {
    Object current = object(yaml().load(text()));
    if (!node.isEmpty()) {
      for (String key : node.split("/")) {
        current = object(current).get(key);
        BusinessException.require(current != null, 404, "NOT_FOUND", "not_found");
      }
    }
    return current;
  }

  public synchronized void mutate(String node, String operation, Object value) {
    Kv config = object(yaml().load(text()));
    if (node.isEmpty()) {
      BusinessException.require(!"DELETE".equals(operation), 400, "INVALID_CONFIG", "Cannot delete root configuration");
      config = object(value);
    } else {
      String[] keys = node.split("/");
      Kv parent = config;
      for (int i = 0; i < keys.length - 1; i++) {
        Kv next = object(parent.get(keys[i]));
        parent.set(keys[i], next);
        parent = next;
      }
      String key = keys[keys.length - 1];
      if ("DELETE".equals(operation)) {
        parent.remove(key);
      } else if ("PATCH".equals(operation) && value instanceof Map<?, ?>) {
        parent.set(key, merge(object(parent.get(key)), object(value)));
      } else {
        parent.set(key, value);
      }
    }
    write(config);
  }

  public synchronized void replaceYaml(String text) {
    Object parsed = yaml().load(text);
    BusinessException.require(parsed instanceof Map<?, ?>, 400, "INVALID_CONFIG", "Configuration must be a YAML object");
    write(object(parsed));
  }

  private void write(Kv config) {
    try {
      Files.createDirectories(path().getParent());
      DumperOptions options = new DumperOptions();
      options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      // Convert Kv and nested extension values to plain YAML-compatible containers.
      Object plain = yaml().load(JsonUtils.toJson(config));
      String content = new Yaml(options).dump(plain);
      Path temporary = Files.createTempFile(path().getParent(), "config-", ".yaml.tmp");
      try {
        Files.writeString(temporary, content);
        Files.move(temporary, path(), StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (Exception error) {
      throw new IllegalStateException("Cannot save desktop configuration", error);
    }
  }

  public synchronized ProxySettings refresh(ProxySettings previous) {
    if (!Files.exists(path())) {
      return previous;
    }
    String content = text();
    if (content.equals(appliedContent)) {
      return previous;
    }
    Kv config = object(yaml().load(content));
    ProxySettings settings = JsonUtils.parse(JsonUtils.toJson(previous), ProxySettings.class);
    Kv access = object(config.get("access"));
    if (access.containsKey("api-keys")) {
      settings.apiKeys = new ArrayList<>();
      for (Object entry : list(access.get("api-keys"))) {
        ApiKey key = new ApiKey();
        key.id = String.valueOf(SnowflakeIdUtils.id());
        key.key = entry instanceof String ? entry.toString() : object(entry).getStr("key");
        key.name = "desktop-" + (settings.apiKeys.size() + 1);
        settings.apiKeys.add(key);
      }
    }
    settings.routing = string(object(config.get("routing")), "strategy", settings.routing);
    if (config.containsKey("api-keys")) {
      settings.providers = new ArrayList<>();
      settings.aliases = new ArrayList<>();
      Kv sections = object(config.get("api-keys"));
      for (Object sectionKey : sections.keySet()) {
        String section = sectionKey.toString();
        for (Object groupValue : list(sections.get(section))) {
          Kv group = object(groupValue);
          List<?> keys = list(group.get("keys"));
          if (keys.isEmpty()) {
            keys = List.of(group);
          }
          for (Object keyValue : keys) {
            Kv effective = object(JsonUtils.parseToMap(JsonUtils.toJson(group), String.class, Object.class));
            object(keyValue).forEach((key, value) -> {
              if (value != null) {
                effective.put(key, value);
              }
            });
            Provider provider = new Provider();
            provider.id = String.valueOf(SnowflakeIdUtils.id());
            provider.name = string(effective, "name", section) + " / " + (settings.providers.size() + 1);
            provider.protocol = switch (section) {
              case "claude" -> "anthropic";
              case "gemini", "vertex" -> "gemini";
              case "codex" -> "responses";
              default -> "openai";
            };
            provider.upstreamType = section;
            provider.baseUrl = string(effective, "base-url", switch (provider.protocol) {
              case "anthropic" -> "https://api.anthropic.com/v1";
              case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models";
              default -> "https://api.openai.com/v1";
            }).replaceAll("/+$", "");
            provider.apiKey = effective.getStr("api-key");
            for (Provider existing : previous.providers) {
              if (Objects.equals(provider.name, existing.name) && Objects.equals(provider.baseUrl, existing.baseUrl)
                  && Objects.equals(provider.apiKey, existing.apiKey) && Objects.equals(provider.protocol, existing.protocol)) { provider.id = existing.id; break; }
            }
            provider.enabled = !Boolean.TRUE.equals(group.get("disabled")) && !Boolean.TRUE.equals(effective.get("disabled"));
            provider.headers = object(effective.get("headers"));
            provider.metadata = effective;
            for (Object modelValue : list(effective.get("models"))) {
              Kv model = object(modelValue);
              String name = model.getStr("name");
              if (name == null || name.isBlank()) {
                continue;
              }
              provider.models.add(name);
            }
            settings.providers.add(provider);
          }
        }
      }
    }
    appliedContent = content;
    return settings;
  }

  private Kv object(Object value) {
    Kv result = Kv.create();
    if (value instanceof Map<?, ?> map) {
      map.forEach((key, item) -> result.set(String.valueOf(key), item));
    }
    return result;
  }

  private List<?> list(Object value) {
    return value instanceof List<?> values ? values : List.of();
  }

  private Kv merge(Kv current, Kv patch) {
    patch.forEach((key, value) -> current.put(key, value instanceof Map<?, ?> ? merge(object(current.get(key)), object(value)) : value));
    return current;
  }

  private String string(Kv value, String key, String fallback) {
    String text = value.getStr(key);
    return text == null || text.isBlank() ? fallback : text;
  }
}
