package nexus.io.llm.proxy.service;

import com.alibaba.fastjson2.JSON;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.io.IOException;
import nexus.io.tio.utils.environment.EnvUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Extracts images from captured log copies only; never alters upstream payloads. */
public class RequestImageService {
  private static final Logger log = LoggerFactory.getLogger(RequestImageService.class);
  private final Path directory;

  public RequestImageService() { this(Path.of(EnvUtils.get("proxy.data-dir", "data"))); }
  RequestImageService(Path dataDirectory) { directory = dataDirectory.toAbsolutePath().resolve("request-images"); }

  public static boolean isImagePath(String path) {
    return path != null && path.matches("request-images/[a-f0-9]{64}\\.(png|jpeg|jpg|gif|webp|avif)");
  }

  public byte[] read(String path) throws IOException {
    if (!isImagePath(path)) throw new IllegalArgumentException("Invalid request image path");
    Path file = directory.resolve(path.substring("request-images/".length()));
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(path);
    return Files.readAllBytes(file);
  }

  public String externalize(String body) {
    if (body == null || !(body.contains("data:image/") || body.contains("\"base64\"")
        || body.contains("\"inlineData\"") || body.contains("\"inline_data\""))) return body;
    try {
      Object value = JSON.parse(body);
      boolean[] changed = { false };
      Object result = visit(value, changed);
      return changed[0] ? JSON.toJSONString(result) : body;
    } catch (Exception error) {
      // A capture or disk failure must not break the live request or lose its log.
      log.warn("Cannot externalize captured request images ({})", error.getClass().getSimpleName());
      return body;
    }
  }

  @SuppressWarnings("unchecked")
  private Object visit(Object value, boolean[] changed) throws Exception {
    if (value instanceof String text && text.startsWith("data:image/")) {
      int separator = text.indexOf(";base64,");
      if (separator > 0) {
        String path = save(text.substring(5, separator), text.substring(separator + 8));
        if (path != null) { changed[0] = true; return path; }
      }
    } else if (value instanceof Map<?, ?> raw) {
      Map<String, Object> map = (Map<String, Object>) raw;
      if ("image".equals(map.get("type")) && map.get("source") instanceof Map<?, ?> source
          && "base64".equals(source.get("type"))) {
        String path = save(source.get("media_type"), source.get("data"));
        if (path != null) { map.put("source", new LinkedHashMap<>(Map.of("type", "url", "url", path))); changed[0] = true; }
      }
      for (String key : List.of("inlineData", "inline_data")) {
        if (map.get(key) instanceof Map<?, ?> inline) {
          Object mime = inline.containsKey("mimeType") ? inline.get("mimeType") : inline.get("mime_type");
          String path = save(mime, inline.get("data"));
          if (path != null) {
            boolean camel = key.equals("inlineData");
            map.remove(key);
            map.put(camel ? "fileData" : "file_data", new LinkedHashMap<>(Map.of(
                camel ? "mimeType" : "mime_type", mime, camel ? "fileUri" : "file_uri", path)));
            changed[0] = true;
          }
        }
      }
      for (Map.Entry<String, Object> entry : map.entrySet()) entry.setValue(visit(entry.getValue(), changed));
    } else if (value instanceof List<?> raw) {
      List<Object> list = (List<Object>) raw;
      for (int i = 0; i < list.size(); i++) list.set(i, visit(list.get(i), changed));
    }
    return value;
  }

  private String save(Object mime, Object data) throws Exception {
    if (!(mime instanceof String type) || !type.matches("image/(png|jpeg|jpg|gif|webp|avif)")
        || !(data instanceof String encoded)) return null;
    byte[] bytes;
    try { bytes = Base64.getDecoder().decode(encoded.replaceAll("\\s", "")); }
    catch (IllegalArgumentException invalid) { return null; }
    if (bytes.length == 0) return null;
    String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "." + type.substring(6);
    Files.createDirectories(directory);
    Path target = directory.resolve(name);
    if (!Files.exists(target)) {
      Path temporary = Files.createTempFile(directory, ".image-", ".tmp");
      try {
        Files.write(temporary, bytes);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
        catch (FileAlreadyExistsException concurrent) { /* Identical content already persisted. */ }
      } finally { Files.deleteIfExists(temporary); }
    }
    return "request-images/" + name;
  }
}
