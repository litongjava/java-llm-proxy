package nexus.io.llm.proxy.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.jfinal.kit.Kv;
import com.sun.jna.Platform;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.llm.proxy.handler.PluginManagementHandler;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.tio.http.server.router.HttpRequestRouter;
import org.yaml.snakeyaml.Yaml;

public class PluginService {
  private final Map<String, NativePlugin> loaded = new ConcurrentHashMap<>();
  private final ReentrantLock lifecycle = new ReentrantLock();
  private final Map<String, String> failures = new ConcurrentHashMap<>();
  private final Map<String, String> failedInputs = new ConcurrentHashMap<>();

  public Kv configuration() {
    return ProxyJson.object(Aop.get(DesktopConfigService.class).section("plugins"));
  }
  public Path directory() {
    Path config = Path.of(EnvUtils.get("proxy.config", "data/config.yaml")).toAbsolutePath();
    Path path = Path.of(ProxyJson.text(configuration(), "dir", "plugins"));
    return (path.isAbsolute() ? path : config.getParent().resolve(path)).normalize();
  }
  public static String extension() { return Platform.isWindows() ? ".dll" : Platform.isMac() ? ".dylib" : ".so"; }
  public static String os() { return Platform.isWindows() ? "windows" : Platform.isMac() ? "darwin" : "linux"; }
  public static String arch() { return Platform.isARM() ? "arm64" : "amd64"; }

  public Kv list() throws Exception {
    refresh();
    Kv settings = configuration();
    Kv configs = ProxyJson.object(settings.get("configs"));
    Kv entries = discover();
    configs.keySet().forEach(id -> entries.putIfAbsent(id, null));
    List<Kv> result = new ArrayList<>();
    for (Object rawId : entries.keySet()) {
      String id = rawId.toString();
      NativePlugin plugin = loaded.get(id);
      Kv instance = ProxyJson.object(configs.get(id));
      Kv metadata = plugin == null ? Kv.create() : metadata(ProxyJson.object(plugin.registration.get("metadata")));
      boolean enabled = !Boolean.FALSE.equals(instance.getBoolean("enabled"));
      Kv entry = Kv.create().set("id", id).set("path", entries.get(id)).set("configured", configs.containsKey(id))
          .set("enabled", enabled).set("registered", plugin != null).set("effective_enabled", plugin != null && enabled)
          .set("metadata", metadata).set("logo", metadata.get("logo")).set("config_fields", metadata.get("config_fields"))
          .set("supports_oauth", plugin != null && plugin.supports("auth_provider"))
          .set("oauth_provider", plugin == null ? null : plugin.authProvider)
          .set("supports_quota", plugin != null && plugin.supports("quota_provider"))
          .set("quota_provider", plugin == null || !plugin.supports("quota_provider") ? null : plugin.call("quota.identifier", Kv.create()).get("identifier"))
          .set("menus", plugin == null ? List.of() : menus(plugin));
      if (failures.containsKey(id)) { entry.set("error", failures.get(id)); }
      result.add(entry);
    }
    result.sort(Comparator.comparing(entry -> entry.getStr("id")));
    return Kv.create().set("plugins_enabled", Boolean.TRUE.equals(settings.getBoolean("enabled")))
        .set("plugins_dir", directory().toString()).set("plugins", result);
  }

  private List<Kv> menus(NativePlugin plugin) {
    List<Kv> menus = new ArrayList<>();
    for (Object value : ProxyJson.list(plugin.management.get("resources"))) {
      Kv item = ProxyJson.object(value);
      menus.add(Kv.create().set("path", item.get("Path")).set("menu", item.get("Menu")).set("description", item.get("Description")));
    }
    return menus;
  }

  private Kv metadata(Kv raw) {
    List<Kv> fields = new ArrayList<>();
    for (Object value : ProxyJson.list(raw.get("ConfigFields"))) {
      Kv field = ProxyJson.object(value);
      fields.add(Kv.create().set("name", field.get("Name")).set("type", field.get("Type"))
          .set("description", field.get("Description")).set("enum_values", field.get("EnumValues")));
    }
    return Kv.create().set("name", raw.get("Name")).set("version", raw.get("Version")).set("author", raw.get("Author"))
        .set("github_repository", raw.get("GitHubRepository")).set("logo", raw.get("Logo")).set("config_fields", fields);
  }

  private Kv discover() throws Exception {
    Kv files = Kv.create();
    if (!Files.isDirectory(directory())) { return files; }
    try (Stream<Path> paths = Files.walk(directory(), 3)) {
      for (Path path : paths.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(extension())).sorted().toList()) {
        if (Files.isSymbolicLink(path)) { continue; }
        String file = path.getFileName().toString();
        String id = file.substring(0, file.length() - extension().length());
        id = id.replaceFirst("-v[0-9][0-9A-Za-z.+-]*$", "");
        // Store installations retain the plugin identity in a dedicated directory.
        if (!path.getParent().equals(directory()) && Files.exists(path.getParent().resolve("manifest.json"))) {
          id = directory().relativize(path).getName(0).toString();
        }
        files.set(id, path.toAbsolutePath().toString());
      }
    }
    return files;
  }

  public void refresh() throws Exception {
    // Native callbacks can re-enter from a different thread during registration.
    if (lifecycle.isHeldByCurrentThread() || !lifecycle.tryLock()) { return; }
    try { refreshLoaded(); }
    finally { lifecycle.unlock(); }
  }

  private void refreshLoaded() throws Exception {
    Kv settings = configuration();
    Kv configs = ProxyJson.object(settings.get("configs"));
    Kv files = Boolean.TRUE.equals(settings.getBoolean("enabled")) ? discover() : Kv.create();
    for (String id : new ArrayList<>(loaded.keySet())) {
      if (!files.containsKey(id) || Boolean.FALSE.equals(ProxyJson.object(configs.get(id)).getBoolean("enabled"))) {
        loaded.remove(id).close();
      }
    }
    for (Object rawId : files.keySet()) {
      String id = rawId.toString();
      Kv config = ProxyJson.object(configs.get(id));
      if (Boolean.FALSE.equals(config.getBoolean("enabled"))) { continue; }
      String yaml = new Yaml().dump(new Yaml().load(JsonUtils.toJson(config)));
      Path path = Path.of(files.getStr(id));
      String fingerprint = yaml + "\n" + Files.size(path) + "\n" + Files.getLastModifiedTime(path).toMillis();
      if (fingerprint.equals(failedInputs.get(id))) { continue; }
      try {
        NativePlugin plugin = loaded.get(id);
        if (plugin == null) {
          plugin = new NativePlugin(id, Path.of(files.getStr(id)), yaml);
          loaded.put(id, plugin);
          registerManagement(plugin);
        }
        else if (!yaml.equals(plugin.config)) { plugin.configure("plugin.reconfigure", yaml); registerManagement(plugin); }
        failures.remove(id);
        failedInputs.remove(id);
      } catch (Exception | LinkageError error) {
        failures.put(id, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        failedInputs.put(id, fingerprint);
      }
    }
  }

  private void registerManagement(NativePlugin plugin) {
    HttpRequestRouter router = TioBootServer.me().getRequestRouter();
    for (String collection : List.of("routes", "resources")) {
      boolean resource = "resources".equals(collection);
      String base = resource ? "/v0/resource/plugins/" + plugin.id : "/v8/management/plugins/" + plugin.id;
      for (Object value : ProxyJson.list(plugin.management.get(collection))) {
        Kv route = ProxyJson.object(value);
        String path = route.getStr("Path");
        String method = resource ? "GET" : ProxyJson.text(route, "Method", "GET").toUpperCase();
        if (path == null) { continue; }
        String resolved = path.startsWith("/") ? path : base + "/" + path;
        BusinessException.require(resolved.startsWith(base + "/") && !resolved.contains("..") && !resolved.contains("*")
            && !resolved.contains("{"), 422, "PLUGIN_ROUTE", "Plugin management route must stay under its own prefix");
        PluginManagementHandler handler = new PluginManagementHandler(plugin.id, resolved, method, resource);
        if ("GET".equals(method)) { router.get(resolved, handler::handle); }
        else { router.post("DELETE".equals(method) ? resolved + "/delete" : resolved, handler::handle); }
      }
    }
  }

  public List<NativePlugin> enabled(String capability) throws Exception {
    refresh();
    return loaded.values().stream().filter(plugin -> plugin.supports(capability)).sorted(Comparator.comparing(plugin -> plugin.id)).toList();
  }

  public List<Provider> staticProviders() {
    try {
      List<Provider> result = new ArrayList<>();
      for (NativePlugin plugin : enabled("executor")) {
        if ("oauth".equals(plugin.capabilities.getStr("executor_model_scope")) || ProxyJson.list(plugin.models.get("Models")).isEmpty()) { continue; }
        Provider provider = new Provider();
        provider.id = "plugin:" + plugin.id; provider.name = plugin.id; provider.upstreamType = plugin.executorProvider;
        provider.baseUrl = "http://plugin.invalid/v1";
        List<?> formats = ProxyJson.list(plugin.capabilities.get("executor_input_formats"));
        String format = formats.isEmpty() ? "openai" : formats.getFirst().toString();
        provider.protocol = switch (format) { case "claude" -> "anthropic"; case "openai-response", "codex" -> "responses"; default -> format; };
        for (Object value : ProxyJson.list(plugin.models.get("Models"))) {
          Kv model = ProxyJson.object(value);
          String id = ProxyJson.text(model, "ID", model.getStr("id"));
          if (id != null) { provider.models.add(id); }
        }
        result.add(provider);
      }
      return result;
    } catch (Exception error) { throw new IllegalStateException("Cannot register plugin models", error); }
  }

  public NativePlugin require(String id) throws Exception {
    ManagementParameterValidator.pluginId(id);
    refresh();
    NativePlugin plugin = loaded.get(id);
    BusinessException.require(plugin != null, 404, "PLUGIN_NOT_FOUND", "Plugin is not enabled or could not be loaded: " + id);
    return plugin;
  }

  public NativePlugin provider(String name, boolean executor) throws Exception {
    refresh();
    return loaded.values().stream().filter(plugin -> name.equals(executor ? plugin.executorProvider : plugin.authProvider)).findFirst().orElse(null);
  }

  public synchronized Kv delete(String id) throws Exception {
    lifecycle.lock();
    try {
    ManagementParameterValidator.pluginId(id);
    Kv discovered = discover();
    String file = discovered.getStr(id);
    NativePlugin plugin = loaded.remove(id);
    if (plugin != null) { plugin.close(); }
    boolean deleted = false;
    if (file != null) {
      Path path = Path.of(file).toAbsolutePath().normalize();
      BusinessException.require(path.startsWith(directory()) && !Files.isSymbolicLink(path), 400, "PLUGIN_PATH", "Invalid plugin path");
      deleted = Files.deleteIfExists(path);
    }
    Aop.get(DesktopConfigService.class).mutate("plugins/configs/" + id, "DELETE", null);
    return Kv.create().set("status", "ok").set("id", id).set("file_deleted", deleted).set("configured_removed", true).set("restart_required", false);
    } finally { lifecycle.unlock(); }
  }

  public Kv quota(String id, String index, String method) throws Exception {
    NativePlugin plugin = require(id);
    BusinessException.require(plugin.supports("quota_provider"), 404, "NO_QUOTA", "Plugin has no quota provider");
    if ("GET".equals(method)) { return plugin.call("quota.describe", Kv.create().set("plugin", plugin.registration.get("metadata"))); }
    CredentialService credentials = Aop.get(CredentialService.class);
    String name = credentials.resolve(index);
    Kv credential = credentials.refreshed(name, false);
    return plugin.call("DELETE".equals(method) ? "quota.reset" : "quota.fetch", Kv.create().set("auth_index", index).set("auth_id", name)
        .set("provider", credential.get("type")).set("storage_json", PluginHostCallbacks.encode(JsonUtils.toJson(credential).getBytes(StandardCharsets.UTF_8)))
        .set("metadata", credential).set("attributes", Kv.create()).set("host", Kv.create()));
  }

  public synchronized void reload(String id) throws Exception {
    lifecycle.lock();
    try {
    failedInputs.remove(id);
    NativePlugin plugin = loaded.remove(id);
    if (plugin != null) { plugin.close(); }
    refreshLoaded();
    } finally { lifecycle.unlock(); }
  }
}
