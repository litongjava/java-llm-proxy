package nexus.io.llm.proxy.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded capture helpers that never split a UTF-8 character. */
final class Utf8Capture extends ByteArrayOutputStream {
  String utf8() { return new String(buf, 0, completeLength(buf, count), StandardCharsets.UTF_8); }

  static String prefix(byte[] bytes, int limit) {
    return new String(bytes, 0, completeLength(bytes, Math.min(bytes.length, Math.max(0, limit))), StandardCharsets.UTF_8);
  }

  private static int completeLength(byte[] bytes, int end) {
    if (end == 0) { return 0; }
    int start = end - 1;
    while (start > 0 && (bytes[start] & 0xc0) == 0x80) { start--; }
    int lead = bytes[start] & 0xff;
    int width = lead >= 0xf0 && lead <= 0xf4 ? 4 : lead >= 0xe0 && lead <= 0xef ? 3 : lead >= 0xc2 && lead <= 0xdf ? 2 : 1;
    return end - start < width ? start : end;
  }

  static String prefix(String value, int limit) {
    int end = 0;
    int remaining = Math.max(0, limit);
    while (end < value.length()) {
      char current = value.charAt(end);
      int chars = Character.isHighSurrogate(current) && end + 1 < value.length() && Character.isLowSurrogate(value.charAt(end + 1)) ? 2 : 1;
      // The JDK UTF-8 encoder replaces an unpaired surrogate with one '?' byte.
      int width = chars == 2 ? 4 : current < 0x80 || Character.isSurrogate(current) ? 1 : current < 0x800 ? 2 : 3;
      if (width > remaining) { break; }
      remaining -= width;
      end += chars;
    }
    return end == value.length() ? value : value.substring(0, end);
  }
}
