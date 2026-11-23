package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Remembers which upstream account and model produced each reasoning item so a reasoning
 * item is replayed only to the backend that can decrypt it.
 *
 * <p>Encrypted reasoning is opaque state. The client cannot tell which account or model
 * issued an item, and a session may switch either between turns, so the proxy keeps the
 * issuer for the item ids it handed out and filters on the way back in. Unknown ids are
 * treated as foreign.
 */
public class ReasoningIssuerService {
  public static final String AUTO = "auto";
  public static final String STRIP = "strip";
  public static final String PASS = "pass";
  private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
  private static final int MAX_ENTRIES = 200_000;
  private static final long MAX_RECORDED_BODY = 8L * 1024 * 1024;

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicLong writes = new AtomicLong();

  private static final class Entry {
    final String issuer;
    final long at;

    Entry(String issuer, long at) {
      this.issuer = issuer;
      this.at = at;
    }
  }

  /** Provider, account and model identity of the backend that serves the current request. */
  public String issuer(Provider provider, String model) {
    String credential = provider.credentialName;
    if (credential == null || credential.isBlank()) {
      credential = digest(provider.apiKey == null ? "" : provider.apiKey);
    }
    return provider.id + "|" + credential + "|" + (model == null ? "" : model);
  }

  private static String digest(String value) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (Exception error) {
      return Integer.toHexString(value.hashCode());
    }
  }

  /** Provider-level policy: {@code auto} (default), {@code strip} or {@code pass}. */
  public String mode(Provider provider) {
    Object configured = provider.metadata == null ? null : provider.metadata.get("reasoning");
    String value = configured == null ? "" : configured.toString().trim().toLowerCase(Locale.ROOT);
    return STRIP.equals(value) || PASS.equals(value) ? value : AUTO;
  }

  /** Records reasoning item ids from one outbound SSE frame. */
  public void observe(String session, String issuer, byte[] frame) {
    if (session == null || session.isBlank() || frame == null || frame.length == 0 || frame.length > MAX_RECORDED_BODY) {
      return;
    }
    String text = new String(frame, StandardCharsets.UTF_8);
    if (!text.contains("\"reasoning\"")) { return; }
    for (String line : text.split("\n")) {
      if (!line.startsWith("data:")) { continue; }
      String data = line.substring(5).strip();
      if (!data.startsWith("{")) { continue; }
      Kv value;
      try {
        value = ProxyJson.parse(data);
      } catch (RuntimeException error) {
        continue;
      }
      String type = value.getStr("type");
      if ("response.output_item.done".equals(type)) {
        record(session, issuer, ProxyJson.object(value.get("item")));
      } else if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
        for (Object item : ProxyJson.list(ProxyJson.object(value.get("response")).get("output"))) {
          record(session, issuer, ProxyJson.object(item));
        }
      }
    }
  }

  /** Records reasoning item ids from a non-streaming Responses body and returns an equivalent response. */
  public Response observeResponse(Response response, String session, String issuer) throws IOException {
    ResponseBody body = response.body();
    if (body == null) { return response; }
    byte[] bytes = body.bytes();
    if (bytes.length <= MAX_RECORDED_BODY && session != null && !session.isBlank()) {
      try {
        Kv value = ProxyJson.parse(new String(bytes, StandardCharsets.UTF_8));
        for (Object item : ProxyJson.list(value.get("output"))) {
          record(session, issuer, ProxyJson.object(item));
        }
      } catch (RuntimeException ignored) {
        // Not JSON; leave it untouched.
      }
    }
    return response.newBuilder().body(ResponseBody.create(bytes, body.contentType())).build();
  }

  private void record(String session, String issuer, Kv item) {
    if (!"reasoning".equals(item.getStr("type"))) { return; }
    String id = item.getStr("id");
    if (id == null || id.isBlank()) { return; }
    entries.put(session + "\n" + id, new Entry(issuer, System.currentTimeMillis()));
    if ((writes.incrementAndGet() & 4095) == 0) { prune(); }
  }

  private void prune() {
    long cutoff = System.currentTimeMillis() - TTL_MS;
    entries.entrySet().removeIf(entry -> entry.getValue().at < cutoff);
    if (entries.size() <= MAX_ENTRIES) { return; }
    entries.entrySet().stream()
        .sorted((left, right) -> Long.compare(left.getValue().at, right.getValue().at))
        .limit(entries.size() - MAX_ENTRIES)
        .map(Map.Entry::getKey)
        .toList()
        .forEach(entries::remove);
  }

  /**
   * Applies the provider's reasoning policy to a Responses input list. Foreign reasoning
   * keeps its readable summary but loses the opaque state; contentless items are dropped.
   */
  public void filter(Kv payload, String protocol, String session, Provider provider, String model) {
    if (!"responses".equals(protocol) || !(payload.get("input") instanceof List<?> input)) { return; }
    String mode = mode(provider);
    if (PASS.equals(mode)) { return; }
    String issuer = issuer(provider, model);
    List<Object> kept = new ArrayList<>(input.size());
    for (Object value : input) {
      if (!(value instanceof Map<?, ?>)) { kept.add(value); continue; }
      Kv item = ProxyJson.object(value);
      if (!"reasoning".equals(item.getStr("type"))) { kept.add(value); continue; }
      if (!STRIP.equals(mode) && trusted(session, item.getStr("id"), issuer)) { kept.add(value); continue; }
      item.remove("encrypted_content");
      if (hasText(item)) { kept.add(item); }
    }
    payload.set("input", kept);
  }

  private boolean trusted(String session, String id, String issuer) {
    if (session == null || session.isBlank() || id == null || id.isBlank()) { return false; }
    String key = session + "\n" + id;
    Entry entry = entries.get(key);
    if (entry == null) { return false; }
    if (entry.at < System.currentTimeMillis() - TTL_MS) {
      entries.remove(key);
      return false;
    }
    return entry.issuer.equals(issuer);
  }

  private static boolean hasText(Kv item) {
    for (Object part : ProxyJson.list(item.get("summary"))) {
      if (!ProxyJson.text(ProxyJson.object(part), "text", "").isBlank()) { return true; }
    }
    for (Object part : ProxyJson.list(item.get("content"))) {
      if (!ProxyJson.text(ProxyJson.object(part), "text", "").isBlank()) { return true; }
    }
    return false;
  }
}
