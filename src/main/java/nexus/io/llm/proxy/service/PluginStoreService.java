package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.Credentials;
import okhttp3.Request;
import okhttp3.Response;

public class PluginStoreService {
  private static final int MAX_ARTIFACT = 128 * 1024 * 1024;
  private static final String OFFICIAL = "https://raw.githubusercontent.com/router-for-me/CLIProxyAPI-Plugins-Store/main/registry.json";

  public Kv list() throws Exception {
    PluginService plugins = Aop.get(PluginService.class);
    Kv installed = plugins.list();
    List<Kv> entries = new ArrayList<>();
    List<Kv> errors = new ArrayList<>();
    List<String> sources = new ArrayList<>();
    sources.add(EnvUtils.get("proxy.plugins.registry-url", OFFICIAL));
    for (Object source : ProxyJson.list(plugins.configuration().get("store-sources"))) {
      if (!sources.contains(source.toString())) { sources.add(source.toString()); }
    }
    for (String source : sources) {
      String sourceId = source.equals(sources.getFirst()) ? "official" : "source-" + digest(source.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
      Kv identity = Kv.create().set("source_id", sourceId).set("source_name", URI.create(source).getHost()).set("source_url", source);
      try {
        Kv registry = ProxyJson.parse(new String(download(source, "registry", 4 * 1024 * 1024), StandardCharsets.UTF_8));
        for (Object value : ProxyJson.list(registry.get("plugins"))) {
          Kv item = ProxyJson.object(value).set(identity);
          ManagementParameterValidator.pluginId(item.getStr("id"));
          item.set("store_id", sourceId + "/" + item.getStr("id"));
          Kv plan = ProxyJson.object(item.get("install"));
          item.set("install_type", ProxyJson.text(plan, "type", "github-release"));
          item.set("platforms", ProxyJson.list(plan.get("artifacts")).stream().map(artifact -> {
            Kv platform = ProxyJson.object(artifact);
            return Kv.create().set("goos", platform.get("goos")).set("goarch", platform.get("goarch"));
          }).toList());
          for (Object localValue : ProxyJson.list(installed.get("plugins"))) {
            Kv local = ProxyJson.object(localValue);
            if (item.getStr("id").equals(local.getStr("id"))) {
              item.set("installed", true).set("path", local.get("path")).set("registered", local.get("registered"))
                  .set("configured", local.get("configured")).set("enabled", local.get("enabled")).set("effective_enabled", local.get("effective_enabled"));
              Kv instance = ProxyJson.object(ProxyJson.object(plugins.configuration().get("configs")).get(item.get("id")));
              Kv store = ProxyJson.object(instance.get("store"));
              String version = ProxyJson.text(store, "version", ProxyJson.text(ProxyJson.object(local.get("metadata")), "version", ""));
              item.set("installed_version", version).set("installed_source_id", store.get("source_id"))
                  .set("update_available", item.get("version") != null && !item.get("version").equals(version));
            }
          }
          entries.add(item);
        }
      } catch (Exception error) { errors.add(Kv.create().set(identity).set("message", error.getMessage())); }
    }
    return Kv.create().set("plugins_enabled", installed.get("plugins_enabled")).set("plugins_dir", installed.get("plugins_dir"))
        .set("source_errors", errors).set("plugins", entries);
  }

  public synchronized Kv install(String id, String source, String requestedVersion) throws Exception {
    ManagementParameterValidator.pluginId(id);
    List<Kv> matches = ProxyJson.list(list().get("plugins")).stream().map(ProxyJson::object)
        .filter(item -> id.equals(item.getStr("id")) && (source == null || source.equals(item.getStr("source_id")))).toList();
    BusinessException.require(matches.size() == 1, 400, "STORE_SELECTION", "Select exactly one plugin registry source");
    Kv plugin = matches.getFirst();
    Kv plan = ProxyJson.object(plugin.get("install"));
    String version = requestedVersion == null || requestedVersion.isBlank() ? plugin.getStr("version") : requestedVersion;
    String installType = ProxyJson.text(plan, "type", "github-release");
    Kv artifact = Kv.create();
    if ("direct".equals(installType)) {
      if (requestedVersion != null && !requestedVersion.equals(plugin.getStr("version"))) {
        Kv pinned = ProxyJson.list(plugin.get("versions")).stream().map(ProxyJson::object)
            .filter(item -> requestedVersion.equals(item.getStr("version"))).findFirst().orElseThrow(() -> new BusinessException(404, "VERSION_NOT_FOUND", "Plugin version not found"));
        plan = ProxyJson.object(pinned.get("install"));
      }
      artifact = ProxyJson.list(plan.get("artifacts")).stream().map(ProxyJson::object)
          .filter(item -> PluginService.os().equals(item.getStr("goos")) && PluginService.arch().equals(item.getStr("goarch")))
          .findFirst().orElseThrow(() -> new BusinessException(404, "PLATFORM_NOT_FOUND", "Plugin has no artifact for this platform"));
    } else {
      String repository = plugin.getStr("repository").replace("https://github.com/", "").replaceAll("/+$", "");
      BusinessException.require(repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"), 400, "REPOSITORY", "Invalid GitHub repository");
      String releasePath = requestedVersion == null || requestedVersion.isBlank() ? "latest" : "tags/" + requestedVersion;
      BusinessException.require(releasePath.matches("[A-Za-z0-9_./+-]+") && !releasePath.contains(".."), 400, "VERSION", "Invalid release tag");
      Kv release = ProxyJson.parse(new String(download("https://api.github.com/repos/" + repository + "/releases/" + releasePath, "metadata", 4 * 1024 * 1024), StandardCharsets.UTF_8));
      version = release.getStr("tag_name").replaceFirst("^v", "");
      for (Object value : ProxyJson.list(release.get("assets"))) {
        Kv asset = ProxyJson.object(value);
        String name = ProxyJson.text(asset, "name", "").toLowerCase();
        if (name.contains(PluginService.os()) && name.contains(PluginService.arch()) && name.endsWith(".zip")) {
          artifact = Kv.create().set("url", asset.get("browser_download_url")).set("size", asset.get("size"));
          String digest = asset.getStr("digest");
          if (digest != null && digest.startsWith("sha256:")) { artifact.set("sha256", digest.substring(7)); }
          break;
        }
      }
    }
    BusinessException.require(version != null && version.matches("v?[0-9][0-9A-Za-z.+-]*"), 400, "VERSION", "Invalid plugin version");
    byte[] archive = download(artifact.getStr("url"), "artifact", MAX_ARTIFACT);
    long size = ProxyJson.number(artifact, "size", 0);
    BusinessException.require(size <= 0 || size == archive.length, 422, "ARTIFACT_SIZE", "Plugin artifact size mismatch");
    String expected = artifact.getStr("sha256");
    BusinessException.require(expected == null || expected.equalsIgnoreCase(digest(archive)), 422, "ARTIFACT_CHECKSUM", "Plugin artifact checksum mismatch");
    byte[] binary = extract(archive);
    PluginService plugins = Aop.get(PluginService.class);
    Path folder = plugins.directory().resolve(id).normalize();
    BusinessException.require(folder.startsWith(plugins.directory()) && !Files.isSymbolicLink(folder), 400, "PLUGIN_PATH", "Invalid plugin directory");
    Files.createDirectories(folder);
    Path destination = folder.resolve(id + PluginService.extension());
    byte[] previous = Files.exists(destination) ? Files.readAllBytes(destination) : null;
    Kv oldInstance = ProxyJson.object(ProxyJson.object(plugins.configuration().get("configs")).get(id));
    Path temporary = Files.createTempFile(folder, ".install-", ".tmp");
    try {
      Files.write(temporary, binary);
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally { Files.deleteIfExists(temporary); }
    Kv instance = ProxyJson.object(ProxyJson.object(plugins.configuration().get("configs")).get(id));
    instance.set("store", Kv.create().set("version", version).set("source_id", plugin.get("source_id")));
    Aop.get(DesktopConfigService.class).mutate("plugins/configs/" + id, "PUT", instance);
    plugins.reload(id);
    if (Boolean.TRUE.equals(plugins.configuration().getBoolean("enabled")) && !Boolean.FALSE.equals(instance.getBoolean("enabled"))) {
      try { plugins.require(id); }
      catch (Exception failure) {
        if (previous == null) { Files.deleteIfExists(destination); }
        else { Files.write(destination, previous); }
        Aop.get(DesktopConfigService.class).mutate("plugins/configs/" + id, oldInstance.isEmpty() ? "DELETE" : "PUT", oldInstance);
        plugins.reload(id);
        throw new BusinessException(422, "PLUGIN_INSTALL", "Plugin could not be initialized; the previous installation was restored");
      }
    }
    return Kv.create().set("status", "ok").set("id", id).set("version", version).set("install_type", installType)
        .set("source_id", plugin.get("source_id")).set("source_url", plugin.get("source_url")).set("source_name", plugin.get("source_name"))
        .set("path", destination.toString()).set("plugins_enabled", plugins.configuration().get("enabled")).set("restart_required", false);
  }

  private byte[] extract(byte[] archive) throws Exception {
    if (archive.length < 4 || archive[0] != 'P' || archive[1] != 'K') { return archive; }
    byte[] binary = null;
    long expanded = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName().replace('\\', '/');
        BusinessException.require(!name.startsWith("/") && !name.contains(":") && !List.of(name.split("/")).contains(".."), 422, "ARCHIVE_PATH", "Unsafe plugin archive path");
        byte[] bytes = zip.readNBytes(MAX_ARTIFACT + 1);
        expanded += bytes.length;
        BusinessException.require(expanded <= MAX_ARTIFACT, 413, "ARCHIVE_LIMIT", "Plugin archive is too large");
        if (name.endsWith(PluginService.extension())) {
          BusinessException.require(binary == null, 422, "ARCHIVE_AMBIGUOUS", "Plugin archive contains multiple native libraries");
          binary = bytes;
        }
      }
    }
    BusinessException.require(binary != null, 422, "ARCHIVE_EMPTY", "Plugin archive contains no native library");
    return binary;
  }

  private byte[] download(String url, String kind, int limit) throws Exception {
    for (int redirect = 0; redirect < 6; redirect++) {
      URI uri = ManagementParameterValidator.httpUrl(url);
      Request.Builder builder = new Request.Builder().url(url).header("User-Agent", "LLM-Proxy-Plugin-Store");
      for (Object value : ProxyJson.list(Aop.get(PluginService.class).configuration().get("store-auth"))) {
        Kv auth = ProxyJson.object(value);
        URI match = URI.create(ProxyJson.text(auth, "match", "https://invalid.invalid"));
        List<?> kinds = ProxyJson.list(auth.get("apply-to"));
        if (!uri.getScheme().equals(match.getScheme()) || !uri.getRawAuthority().equals(match.getRawAuthority())
            || !(uri.getPath().equals(match.getPath()) || uri.getPath().startsWith(match.getPath().endsWith("/") ? match.getPath() : match.getPath() + "/"))
            || (!kinds.isEmpty() && !kinds.contains(kind))) { continue; }
        BusinessException.require("https".equals(uri.getScheme()) || Boolean.TRUE.equals(auth.getBoolean("allow-insecure")), 400, "STORE_AUTH_TLS", "Store credentials require HTTPS");
        switch (ProxyJson.text(auth, "type", "none")) {
          case "bearer", "github-token" -> builder.header("Authorization", "Bearer " + EnvUtils.get(auth.getStr("token-env"), ""));
          case "basic" -> builder.header("Authorization", Credentials.basic(EnvUtils.get(auth.getStr("username-env"), ""), EnvUtils.get(auth.getStr("password-env"), "")));
          case "header" -> builder.header(auth.getStr("header-name"), EnvUtils.get(auth.getStr("header-value-env"), ""));
          default -> { }
        }
      }
      ManagementHttpService transport = Aop.get(ManagementHttpService.class);
      try (Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 90), builder.build())) {
        if (response.isRedirect()) {
          String location = response.header("Location");
          BusinessException.require(location != null, 502, "STORE_REDIRECT", "Invalid registry redirect");
          url = uri.resolve(location).toString();
          continue;
        }
        BusinessException.require(response.isSuccessful() && response.body() != null, 502, "STORE_HTTP", "Plugin store returned HTTP " + response.code());
        byte[] bytes = response.body().byteStream().readNBytes(limit + 1);
        BusinessException.require(bytes.length <= limit, 413, "STORE_SIZE", "Plugin store response is too large");
        return bytes;
      }
    }
    throw new BusinessException(502, "STORE_REDIRECT", "Too many registry redirects");
  }

  private String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
