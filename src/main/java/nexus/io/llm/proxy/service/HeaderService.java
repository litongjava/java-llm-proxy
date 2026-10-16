package nexus.io.llm.proxy.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.jfinal.kit.Kv;

public final class HeaderService {
  private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
      "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "content-encoding");
  private HeaderService() { }

  public static void captureClientContext(nexus.io.llm.proxy.model.RequestRecord record) {
    if (record.requestHeaders == null) return;
    record.originator = contextHeader(record.requestHeaders, "originator");
    record.session_id = contextHeader(record.requestHeaders, "session-id");
    record.thread_id = contextHeader(record.requestHeaders, "thread-id");
  }

  private static String contextHeader(Map<?, ?> headers, String name) {
    for (String alias : new String[] {name, name.replace('-', '_')}) {
      for (Map.Entry<?, ?> entry : headers.entrySet()) {
        if (alias.equalsIgnoreCase(String.valueOf(entry.getKey())) && entry.getValue() != null) {
          String value = entry.getValue().toString().trim();
          if (!value.isEmpty()) return value;
        }
      }
    }
    return null;
  }

  public static boolean transport(String name) {
    return HOP.contains(name.toLowerCase(Locale.ROOT));
  }

  public static boolean secret(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.contains("authorization") || lower.contains("api-key") || lower.contains("apikey")
        || lower.contains("token") || lower.contains("secret") || lower.equals("cookie") || lower.equals("set-cookie");
  }

  public static Kv redact(Map<?, ?> headers) {
    Kv result = Kv.create();
    if (headers != null) {
      headers.forEach((name, value) -> result.set(name.toString(), secret(name.toString()) ? "[REDACTED]" : value));
    }
    return result;
  }
}
