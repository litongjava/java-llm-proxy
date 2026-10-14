package nexus.io.llm.proxy.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.jfinal.kit.Kv;

public final class HeaderService {
  private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
      "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "content-encoding");
  private HeaderService() { }

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
