package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;

/** Incremental DSML framing; incomplete tool arguments are never reported as successful calls. */
public class CodeArtsDsmlParser {
  private static final String OPEN = "<｜DSML｜tool_calls>";
  private static final String CLOSE = "</｜DSML｜tool_calls>";
  private static final String THOUGHT = "<thought>";
  private static final String END_THOUGHT = "</thought>";
  private static final Pattern INVOKE = Pattern.compile("<｜DSML｜invoke\\s+([^>]*)>(.*?)</｜DSML｜invoke>", Pattern.DOTALL);
  private static final Pattern PARAMETER = Pattern.compile("<｜DSML｜parameter\\s+([^>]*)>(.*?)</｜DSML｜parameter>", Pattern.DOTALL);
  private final StringBuilder buffer = new StringBuilder();
  private String mode = "text";
  private final AtomicInteger toolIndex;
  private boolean emittedTools;

  public CodeArtsDsmlParser() { this(new AtomicInteger()); }
  public CodeArtsDsmlParser(AtomicInteger toolIndex) { this.toolIndex = toolIndex; }

  public boolean hasTools() { return emittedTools; }

  public Kv feed(String input, String textField, boolean finish) throws IOException {
    buffer.append(input);
    int maximum = Math.max(1024, EnvUtils.getInt("proxy.oauth.codearts.max-dsml-chars", 8 * 1024 * 1024));
    if (buffer.length() > maximum) { throw new IOException("CodeArts tool block exceeds the size limit"); }
    Kv delta = Kv.create();
    while (!buffer.isEmpty()) {
      String current = buffer.toString();
      if ("dsml".equals(mode)) {
        int end = current.indexOf(CLOSE);
        if (end < 0) { break; }
        List<Kv> calls = parseCalls(current.substring(0, end));
        List<Object> all = new ArrayList<>(ProxyJson.list(delta.get("tool_calls")));
        all.addAll(calls);
        delta.set("tool_calls", all);
        buffer.delete(0, end + CLOSE.length());
        mode = "text";
      } else if ("thought".equals(mode)) {
        int end = current.indexOf(END_THOUGHT);
        if (end >= 0) {
          append(delta, "reasoning_content", current.substring(0, end));
          buffer.delete(0, end + END_THOUGHT.length());
          mode = "text";
        } else {
          int keep = suffixPrefix(current, END_THOUGHT);
          append(delta, "reasoning_content", current.substring(0, current.length() - keep));
          buffer.delete(0, current.length() - keep);
          break;
        }
      } else {
        int dsml = current.indexOf(OPEN);
        int thought = current.indexOf(THOUGHT);
        int start = dsml < 0 ? thought : thought < 0 ? dsml : Math.min(dsml, thought);
        if (start >= 0) {
          append(delta, textField, current.substring(0, start));
          boolean isDsml = start == dsml;
          buffer.delete(0, start + (isDsml ? OPEN.length() : THOUGHT.length()));
          mode = isDsml ? "dsml" : "thought";
        } else {
          int keep = finish ? 0 : Math.max(suffixPrefix(current, OPEN), suffixPrefix(current, THOUGHT));
          append(delta, textField, current.substring(0, current.length() - keep));
          buffer.delete(0, current.length() - keep);
          break;
        }
      }
    }
    if (finish && !"text".equals(mode)) { throw new IOException("CodeArts stream contains an incomplete tool or thought block"); }
    return delta;
  }

  private int suffixPrefix(String text, String tag) {
    for (int size = Math.min(text.length(), tag.length() - 1); size > 0; size--) {
      if (text.endsWith(tag.substring(0, size))) { return size; }
    }
    return 0;
  }

  private List<Kv> parseCalls(String block) throws IOException {
    List<Kv> calls = new ArrayList<>();
    Matcher invokes = INVOKE.matcher(block);
    int consumed = 0;
    while (invokes.find()) {
      if (!block.substring(consumed, invokes.start()).isBlank()) { throw new IOException("CodeArts returned malformed tool syntax"); }
      String name = attribute(invokes.group(1), "name");
      if (name == null || name.isBlank()) { throw new IOException("CodeArts tool call omitted its name"); }
      Kv parameters = Kv.create();
      Matcher params = PARAMETER.matcher(invokes.group(2));
      int parameterEnd = 0;
      while (params.find()) {
        if (!invokes.group(2).substring(parameterEnd, params.start()).isBlank()) { throw new IOException("CodeArts returned malformed tool parameters"); }
        String parameter = attribute(params.group(1), "name");
        if (parameter == null || parameter.isBlank()) { throw new IOException("CodeArts tool parameter omitted its name"); }
        String raw = params.group(2);
        Object value = raw;
        if (!"true".equals(attribute(params.group(1), "string"))) {
          try { value = JsonUtils.parse(raw, Object.class); }
          catch (RuntimeException error) { throw new IOException("CodeArts tool parameter is not valid JSON"); }
        }
        parameters.set(parameter, value);
        parameterEnd = params.end();
      }
      if (!invokes.group(2).substring(parameterEnd).isBlank()) { throw new IOException("CodeArts returned incomplete tool parameters"); }
      emittedTools = true;
      calls.add(Kv.create().set("index", toolIndex.getAndIncrement()).set("id", "call_" + SnowflakeIdUtils.id()).set("type", "function")
          .set("function", Kv.create().set("name", name).set("arguments", JsonUtils.toJsonWithLongAsNumber(parameters))));
      consumed = invokes.end();
    }
    if (calls.isEmpty() || !block.substring(consumed).isBlank()) { throw new IOException("CodeArts returned an invalid tool block"); }
    return calls;
  }

  private String attribute(String attributes, String name) {
    Matcher matcher = Pattern.compile("(?:^|\\s)" + name + "\\s*=\\s*(['\"])(.*?)\\1", Pattern.DOTALL).matcher(attributes);
    return matcher.find() ? matcher.group(2) : null;
  }
  private void append(Kv delta, String field, String value) {
    if (!value.isEmpty()) { delta.set(field, (delta.get(field) == null ? "" : delta.get(field).toString()) + value); }
  }
}
