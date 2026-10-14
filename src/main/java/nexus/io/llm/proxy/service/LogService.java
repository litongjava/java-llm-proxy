package nexus.io.llm.proxy.service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.RequestRecord;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;

public class LogService {
  private Path directory() throws Exception {
    Path path = Path.of(EnvUtils.get("proxy.logs-dir", "logs")).toAbsolutePath().normalize();
    Files.createDirectories(path);
    return path.toRealPath();
  }
  private List<Path> files() throws Exception {
    try (Stream<Path> paths = Files.list(directory())) {
      return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName().toString().endsWith(".log")).sorted().toList();
    }
  }
  public Kv logs(int limit) throws Exception {
    ArrayDeque<String> lines = new ArrayDeque<>();
    for (Path file : files()) {
      if (!file.getFileName().toString().startsWith("log.")) { continue; }
      try (BufferedReader reader = Files.newBufferedReader(file)) {
        String line;
        while ((line = reader.readLine()) != null) {
          lines.addLast(line);
          if (lines.size() > limit) { lines.removeFirst(); }
        }
      }
    }
    return Kv.create().set("lines", new ArrayList<>(lines)).set("line-count", lines.size()).set("latest-timestamp", System.currentTimeMillis() / 1000);
  }
  public Kv clear() throws Exception {
    int count = 0;
    for (Path file : files()) {
      if (file.getFileName().toString().startsWith("log.")) { Files.writeString(file, ""); count++; }
    }
    return Kv.create().set("status", "ok").set("deleted", count);
  }
  public Kv errors() throws Exception {
    List<Kv> result = new ArrayList<>();
    for (Path file : files()) {
      if (file.getFileName().toString().startsWith("error-")) {
        result.add(Kv.create().set("name", file.getFileName().toString()).set("size", Files.size(file))
            .set("modified", Files.getLastModifiedTime(file).toMillis() / 1000));
      }
    }
    return Kv.create().set("files", result);
  }
  public String error(String name) throws Exception {
    BusinessException.require(name != null && name.matches("error-[0-9]+\\.log"), 400, "LOG_NAME", "Invalid error log name");
    Path path = directory().resolve(name);
    BusinessException.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), 404, "LOG_NOT_FOUND", "Log not found");
    return Files.readString(path);
  }
  public void recordError(RequestRecord record) {
    if (record.status < 400) { return; }
    try {
      Files.writeString(directory().resolve("error-" + record.id + ".log"), JsonUtils.toJson(record), StandardCharsets.UTF_8);
      List<Path> errors = files().stream().filter(file -> file.getFileName().toString().startsWith("error-")).toList();
      int retention = EnvUtils.getInt("proxy.logs.error-files", 100);
      for (int i = 0; i < errors.size() - retention; i++) { Files.deleteIfExists(errors.get(i)); }
    } catch (Exception error) { throw new IllegalStateException("Cannot persist request error log", error); }
  }
}
