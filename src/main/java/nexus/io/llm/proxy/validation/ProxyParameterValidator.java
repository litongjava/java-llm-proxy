package nexus.io.llm.proxy.validation;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.ProxySettings;
import nexus.io.llm.proxy.model.UsageQuery;
import nexus.io.llm.proxy.model.UsageAcknowledgement;
import nexus.io.llm.proxy.model.DesktopConfigMutation;
import nexus.io.llm.proxy.model.WebUsageQuery;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class ProxyParameterValidator {
  private ProxyParameterValidator() {
  }

  public static String id(String value) {
    return ParameterValidator.text(value, "id", 128);
  }

  public static int queueCount(HttpRequest request) {
    return (int) ParameterValidator.longValue(request.getParam("count"), "count", 1, 10000, 200L);
  }

  public static void configMutation(String node, DesktopConfigMutation input) {
    ParameterValidator.require(input != null, "Configuration mutation is required");
    input.setOperation(
        ParameterValidator.choice(input.getOperation(), "operation", "PUT", "PUT", "PATCH", "DELETE", "POST"));
    ParameterValidator.require(!node.contains("..") && node.length() <= 1024, "Invalid configuration node");
  }

  public static void acknowledgement(UsageAcknowledgement input) {
    ParameterValidator.require(input != null && input.getIds() != null && input.getIds().size() <= 10000,
        "ids must contain at most 10000 request IDs");
    for (String value : input.getIds()) {
      id(value);
    }
  }

  public static Provider provider(Provider input) {
    ParameterValidator.require(input != null, "Provider is required");
    input.name = ParameterValidator.text(input.name, "name", 120);
    nexus.io.llm.proxy.service.MultiProtocolService.validate(input);
    input.baseUrl = ParameterValidator.text(input.baseUrl, "baseUrl", 2048);
    URI uri;
    try {
      uri = URI.create(input.baseUrl);
    } catch (IllegalArgumentException e) {
      ParameterValidator.require(false, "baseUrl is invalid");
      return input;
    }
    ParameterValidator.require(List.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
        && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
        "baseUrl must be an HTTP API prefix");
    input.baseUrl = input.baseUrl.replaceAll("/+$", "");
    input.protocol = ParameterValidator.choice(input.protocol, "protocol", "openai", "openai", "anthropic", "gemini",
        "responses");
    ParameterValidator.require(input.models != null && input.models.size() <= 1000, "Invalid models");
    for (String model : input.models) {
      ParameterValidator.text(model, "model", 256);
    }
    ParameterValidator.require(input.inputPrice >= 0 && input.outputPrice >= 0, "Prices must be positive");
    return input;
  }

  public static ProxySettings settings(ProxySettings input) {
    ParameterValidator.require(input != null, "Settings are required");
    input.routing = ParameterValidator.choice(input.routing, "routing", "round-robin", "round-robin", "fill-first");
    ParameterValidator.longValue(input.retentionDays, "retentionDays", 1, 3650, null);
    ParameterValidator.require(
        input.providers != null && input.apiKeys != null && input.aliases != null && input.payloadDefaults != null
            && input.payloadOverrides != null && input.payloadRemove != null && input.sensitiveWords != null,
        "Settings collections are required");
    for (Provider provider : input.providers) {
      provider(provider);
    }
    return input;
  }

  public static UsageQuery query(HttpRequest request) {
    UsageQuery query = new UsageQuery();
    query.page = (int) ParameterValidator.longValue(request.getParam("page"), "page", 1, 1000000, 1L);
    query.pageSize = (int) ParameterValidator.longValue(request.getParam("pageSize"), "pageSize", 1, 200, 50L);
    query.from = ParameterValidator.longValue(request.getParam("from"), "from", 0, Long.MAX_VALUE, 0L);
    query.to = ParameterValidator.longValue(request.getParam("to"), "to", query.from, Long.MAX_VALUE, Long.MAX_VALUE);
    query.model = request.getParam("model");
    query.provider = request.getParam("provider");
    query.apiKeyName = request.getParam("apiKeyName");
    query.source = request.getParam("source");
    query.originator = request.getParam("originator");
    query.session_id = request.getParam("session_id");
    query.thread_id = request.getParam("thread_id");

    query.result = ParameterValidator.choice(request.getParam("result"), "result", "all", "all", "success", "error");
    return query;
  }

  public static WebUsageQuery webUsageQuery(HttpRequest request) {
    WebUsageQuery query = new WebUsageQuery();
    query.start = timestamp(request, "start");
    query.end = timestamp(request, "end");
    query.model = request.getParam("model");
    query.provider = request.getParam("provider");
    query.source = request.getParam("source");
    query.upstream = request.getParam("upstream");
    query.originator = request.getParam("originator");
    query.sessionId = request.getParam("session_id");
    query.threadId = request.getParam("thread_id");
    query.apiKey = request.getParam("api_key_hash");
    query.failed = bool(request, "failed");
    query.canceled = bool(request, "canceled");
    query.page = (int) ParameterValidator.longValue(request.getParam("page"), "page", 1, 1000000, 1L);
    query.pageSize = (int) ParameterValidator.longValue(request.getParam("page_size"), "page_size", 1, 5000, 50L);
    return query;
  }

  private static Long timestamp(HttpRequest request, String name) {
    String raw = request.getParam(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
    }
    try {
      return OffsetDateTime.parse(value).toInstant().toEpochMilli();
    } catch (DateTimeParseException ignored) {
    }
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (DateTimeParseException ignored) {
    }
    ParameterValidator.require(false, "Invalid " + name);
    return null;
  }

  private static Boolean bool(HttpRequest request, String name) {
    String raw = request.getParam(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    if ("true".equalsIgnoreCase(raw.trim())) {
      return true;
    }
    if ("false".equalsIgnoreCase(raw.trim())) {
      return false;
    }
    ParameterValidator.require(false, "Invalid " + name);
    return null;
  }
}
