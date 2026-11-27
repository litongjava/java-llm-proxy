package nexus.io.llm.proxy.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import nexus.io.llm.proxy.model.ApiAccessRemark;
import nexus.io.llm.proxy.model.ApiAccessRemarkLocator;
import nexus.io.llm.proxy.model.ApiAccessRemarkQuery;
import nexus.io.llm.proxy.model.ApiAccessRemarkUpdate;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;

/**
 * Stores Web console provider notes next to the Java data directory. Provider
 * configuration stays in config.yaml; this only keeps UI metadata.
 */
public class ApiAccessRemarkService {
  private static final Set<String> SECTIONS = Set.of(
      "gemini-api-key", "interactions-api-key", "vertex-api-key", "xai-api-key", "meta-api-key",
      "codex-api-key", "claude-api-key", "multi-protocol", "openai-compatibility");
  private static final Set<String> NAME_FALLBACK_SECTIONS = Set.of(
      "gemini-api-key", "codex-api-key", "claude-api-key");
  private static final int MAX_QUERIES = 5000;
  private static final int MAX_RECORDS = 10000;
  private static final int MAX_REMARK_LENGTH = 80;

  private final Path file;

  public ApiAccessRemarkService() {
    this(Path.of(EnvUtils.get("proxy.data-dir", "data")));
  }

  ApiAccessRemarkService(Path dataDirectory) {
    this.file = dataDirectory.toAbsolutePath().resolve("webui-api-access-remarks.json");
  }

  public synchronized List<String> resolve(List<ApiAccessRemarkQuery> queries) {
    BusinessException.require(queries != null && queries.size() <= MAX_QUERIES, 400,
        "INVALID_REMARKS", "Remark queries are invalid");
    List<ApiAccessRemark> entries = read();
    List<String> result = new ArrayList<>(queries.size());
    for (ApiAccessRemarkQuery query : queries) {
      result.add(resolveOne(entries, query));
    }
    return result;
  }

  public synchronized void save(ApiAccessRemarkUpdate update) {
    BusinessException.require(update != null, 400, "INVALID_REMARKS", "Remark update is required");
    String section = text(update.getProviderSection()).trim();
    validateSection(section);
    String remark = text(update.getRemark()).trim();
    validateRemark(remark);

    List<Identity> previous = identities(section, update.getPreviousRecords());
    List<Identity> next = identities(section, update.getRecords());
    List<Identity> all = identities(section, update.getAllRecords());
    Set<String> replaced = new HashSet<>();
    for (Identity identity : previous) {
      replaced.add(identity.recordHash());
    }
    for (Identity identity : next) {
      replaced.add(identity.recordHash());
    }
    Set<String> current = new HashSet<>();
    for (Identity identity : all) {
      current.add(identity.recordHash());
    }

    List<ApiAccessRemark> entries = read();
    entries.removeIf(entry -> section.equals(entry.getProviderSection())
        && !(notBlank(entry.getRecordHash()) && current.contains(entry.getRecordHash())
            && !replaced.contains(entry.getRecordHash())));

    Set<String> inserted = new HashSet<>();
    for (ApiAccessRemark entry : entries) {
      if (section.equals(entry.getProviderSection()) && notBlank(entry.getRecordHash())) {
        inserted.add(entry.getRecordHash());
      }
    }
    for (Identity identity : next) {
      if (!inserted.add(identity.recordHash())) {
        continue;
      }
      for (String apiKeyHash : identity.apiKeyHashes()) {
        ApiAccessRemark entry = new ApiAccessRemark();
        entry.setProviderSection(section);
        entry.setApiKeyHash(apiKeyHash);
        entry.setRecordHash(identity.recordHash());
        entry.setRemark(remark);
        entries.add(entry);
      }
    }
    write(entries);
  }

  private String resolveOne(List<ApiAccessRemark> entries, ApiAccessRemarkQuery query) {
    BusinessException.require(query != null, 400, "INVALID_REMARKS", "Remark query is required");
    String section = text(query.getProviderSection()).trim();
    validateSection(section);
    List<ApiAccessRemarkLocator> locators = new ArrayList<>();
    locators.add(locator(query));
    if (NAME_FALLBACK_SECTIONS.contains(section) && notBlank(query.getProviderName())) {
      ApiAccessRemarkLocator unnamed = locator(query);
      unnamed.setProviderName("");
      locators.add(unnamed);
    }

    for (ApiAccessRemarkLocator locator : locators) {
      Identity identity = identity(section, locator);
      if (identity == null) {
        continue;
      }
      String remark = exactRemark(entries, section, identity);
      if (remark != null) {
        return remark;
      }
      if (notBlank(locator.getConfigIdentity())) {
        ApiAccessRemarkLocator withoutConfig = locator(locator);
        withoutConfig.setConfigIdentity("");
        Identity fallback = identity(section, withoutConfig);
        remark = fallback == null ? null : exactRemark(entries, section, fallback);
        if (remark != null) {
          return remark;
        }
      }
    }

    Identity identity = identity(section, locator(query));
    if (identity == null) {
      return "";
    }
    for (String apiKeyHash : identity.apiKeyHashes()) {
      for (ApiAccessRemark entry : entries) {
        if (section.equals(entry.getProviderSection()) && !notBlank(entry.getRecordHash())
            && apiKeyHash.equals(entry.getApiKeyHash())) {
          return text(entry.getRemark());
        }
      }
    }
    return "";
  }

  private String exactRemark(List<ApiAccessRemark> entries, String section, Identity identity) {
    String fallback = null;
    for (ApiAccessRemark entry : entries) {
      if (!section.equals(entry.getProviderSection())
          || !identity.recordHash().equals(entry.getRecordHash())
          || !identity.apiKeyHashes().contains(entry.getApiKeyHash())) {
        continue;
      }
      if (notBlank(entry.getRemark())) {
        return entry.getRemark();
      }
      fallback = text(entry.getRemark());
    }
    return fallback;
  }

  private List<Identity> identities(String section, List<ApiAccessRemarkLocator> locators) {
    BusinessException.require(locators == null || locators.size() <= MAX_RECORDS, 400,
        "INVALID_REMARKS", "Too many provider records");
    List<Identity> result = new ArrayList<>();
    if (locators == null) {
      return result;
    }
    for (ApiAccessRemarkLocator locator : locators) {
      if (locator == null) {
        continue;
      }
      Identity identity = identity(section, locator);
      if (identity != null) {
        result.add(identity);
      }
    }
    return result;
  }

  private Identity identity(String section, ApiAccessRemarkLocator locator) {
    List<String> apiKeyHashes = new ArrayList<>();
    if (locator.getApiKeys() != null) {
      for (String key : locator.getApiKeys()) {
        String trimmed = text(key).trim();
        if (!trimmed.isEmpty()) {
          apiKeyHashes.add(sha256(trimmed));
        }
      }
    }
    List<String> distinct = apiKeyHashes.stream().distinct().sorted().toList();
    if (distinct.isEmpty()) {
      return null;
    }

    ByteArrayOutputStream identity = new ByteArrayOutputStream();
    writeComponent(identity, section.trim());
    writeComponent(identity, text(locator.getProviderName()).trim());
    writeComponent(identity, text(locator.getBaseUrl()).trim());
    writeLong(identity, distinct.size());
    for (String apiKeyHash : distinct) {
      writeComponent(identity, apiKeyHash);
    }
    if (notBlank(locator.getConfigIdentity())) {
      writeComponent(identity, locator.getConfigIdentity());
    }
    return new Identity(sha256(identity.toByteArray()), distinct);
  }

  private void validateSection(String section) {
    BusinessException.require(SECTIONS.contains(section), 400,
        "INVALID_REMARKS", "Invalid API access type");
  }

  private void validateRemark(String remark) {
    BusinessException.require(remark.codePointCount(0, remark.length()) <= MAX_REMARK_LENGTH
        && remark.codePoints().noneMatch(Character::isISOControl), 400,
        "INVALID_REMARKS", "Key note cannot exceed 80 characters or contain control characters");
  }

  private List<ApiAccessRemark> read() {
    try {
      if (!Files.isRegularFile(file)) {
        return new ArrayList<>();
      }
      String content = Files.readString(file, StandardCharsets.UTF_8);
      if (content.isBlank()) {
        return new ArrayList<>();
      }
      List<ApiAccessRemark> entries = JSON.parseArray(content, ApiAccessRemark.class);
      return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    } catch (IOException | RuntimeException error) {
      throw new IllegalStateException("Cannot read API access remarks", error);
    }
  }

  private void write(List<ApiAccessRemark> entries) {
    try {
      Path directory = file.getParent();
      Files.createDirectories(directory);
      Path temporary = Files.createTempFile(directory, "api-access-remarks-", ".json.tmp");
      try {
        Files.writeString(temporary, JSON.toJSONString(entries, JSONWriter.Feature.PrettyFormat),
            StandardCharsets.UTF_8);
        try {
          Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
          Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException error) {
      throw new IllegalStateException("Cannot save API access remarks", error);
    }
  }

  private static ApiAccessRemarkLocator locator(ApiAccessRemarkQuery query) {
    ApiAccessRemarkLocator locator = new ApiAccessRemarkLocator();
    locator.setProviderName(query.getProviderName());
    locator.setBaseUrl(query.getBaseUrl());
    locator.setApiKeys(query.getApiKeys());
    locator.setConfigIdentity(query.getConfigIdentity());
    return locator;
  }

  private static ApiAccessRemarkLocator locator(ApiAccessRemarkLocator source) {
    ApiAccessRemarkLocator locator = new ApiAccessRemarkLocator();
    locator.setProviderName(source.getProviderName());
    locator.setBaseUrl(source.getBaseUrl());
    locator.setApiKeys(source.getApiKeys());
    locator.setConfigIdentity(source.getConfigIdentity());
    return locator;
  }

  private static void writeComponent(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeLong(output, bytes.length);
    output.writeBytes(bytes);
  }

  private static void writeLong(ByteArrayOutputStream output, long value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
      output.write((int) (value >>> shift) & 0xff);
    }
  }

  private static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private record Identity(String recordHash, List<String> apiKeyHashes) {
  }
}
