package nexus.io.llm.proxy.service;

import java.util.List;
import java.util.Map;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;

public final class ProxyJson {
  private ProxyJson() { }

  public static Kv object(Object value) {
    Kv result = Kv.create();
    if (value instanceof Map<?, ?> values) {
      values.forEach((key, item) -> result.set(String.valueOf(key), item));
    }
    return result;
  }

  public static Kv parse(String value) {
    return object(JsonUtils.parseToMap(value, String.class, Object.class));
  }

  public static String text(Kv value, String key, String fallback) {
    Object raw = value.get(key);
    return raw == null || raw.toString().isBlank() ? fallback : raw.toString();
  }

  public static long number(Kv value, String key, long fallback) {
    Object raw = value.get(key);
    try {
      return raw == null ? fallback : Long.parseLong(raw.toString());
    } catch (NumberFormatException error) {
      return fallback;
    }
  }

  public static List<?> list(Object value) {
    return value instanceof List<?> values ? values : List.of();
  }
}
