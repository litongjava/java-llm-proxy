package nexus.io.llm.proxy.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;

public class PayloadRuleService {
  public Kv apply(Kv payload, String model, String target, String source, Kv headers) {
    Kv requests = ProxyJson.object(ProxyJson.object(Aop.get(DesktopConfigService.class).get("")).get("requests"));
    return applyRules(payload, ProxyJson.object(requests.get("payload")), model, target, source, headers);
  }

  public Kv applyRules(Kv payload, Kv rules, String model, String target, String source, Kv headers) {
    for (String phase : List.of("default", "default-raw", "override", "override-raw", "filter")) {
      for (Object value : ProxyJson.list(rules.get(phase))) {
        Kv rule = ProxyJson.object(value);
        boolean matches = ProxyJson.list(rule.get("models")).stream().anyMatch(entry -> matches(ProxyJson.object(entry), payload, model, target, source, headers));
        if (!matches) { continue; }
        if ("filter".equals(phase)) {
          for (Object path : ProxyJson.list(rule.get("params"))) { JSONPath.remove(payload, path(path.toString())); }
        } else {
          ProxyJson.object(rule.get("params")).forEach((key, raw) -> {
            String path = path(key.toString());
            if (phase.startsWith("default") && JSONPath.contains(payload, path)) { return; }
            Object parameter = phase.endsWith("-raw") && raw instanceof String text ? JSON.parse(text) : raw;
            JSONPath.set(payload, path, parameter);
          });
        }
      }
    }
    return payload;
  }

  private boolean matches(Kv condition, Kv body, String model, String target, String source, Kv headers) {
    if (!wildcard(ProxyJson.text(condition, "name", "*"), model)) { return false; }
    String protocol = condition.getStr("protocol");
    String from = condition.getStr("from-protocol");
    if (protocol != null && !normalize(protocol).equals(normalize(target))) { return false; }
    if (from != null && !normalize(from).equals(normalize(source))) { return false; }
    Kv requiredHeaders = ProxyJson.object(condition.get("headers"));
    for (Object key : requiredHeaders.keySet()) {
      Object actual = null;
      for (Object header : headers.keySet()) { if (header.toString().equalsIgnoreCase(key.toString())) { actual = headers.get(header); break; } }
      if (actual instanceof List<?> values) { actual = values.isEmpty() ? null : values.getFirst(); }
      if (actual == null || !wildcard(requiredHeaders.get(key).toString(), actual.toString())) { return false; }
    }
    for (String kind : List.of("match", "not-match")) {
      Object raw = condition.get(kind);
      List<?> checks = raw instanceof Map<?, ?> ? List.of(raw) : ProxyJson.list(raw);
      for (Object check : checks) {
        Kv expected = ProxyJson.object(check);
        for (Object key : expected.keySet()) {
          Object actual = JSONPath.eval(body, path(key.toString()));
          boolean equal = Objects.equals(JSON.toJSONString(actual), JSON.toJSONString(expected.get(key)));
          if (equal != "match".equals(kind)) { return false; }
        }
      }
    }
    for (String kind : List.of("exist", "not-exist")) {
      for (Object key : ProxyJson.list(condition.get(kind))) {
        Object actual = JSONPath.eval(body, path(key.toString()));
        boolean exists = actual != null && (!(actual instanceof List<?> list) || !list.isEmpty());
        if (exists != "exist".equals(kind)) { return false; }
      }
    }
    return true;
  }

  private String normalize(String protocol) { return switch (protocol) { case "anthropic" -> "claude"; case "responses", "openai-response" -> "codex"; default -> protocol; }; }
  private boolean wildcard(String pattern, String value) { return value.matches(Pattern.quote(pattern).replace("*", "\\E.*\\Q")); }
  private String path(String path) {
    if (path.startsWith("$")) { return path; }
    return "$." + path.replaceAll("\\.#\\(([^)]+)\\)", "[?(@.$1)]").replaceAll("\\.(\\d+)(?=\\.|$)", "[$1]");
  }
}
