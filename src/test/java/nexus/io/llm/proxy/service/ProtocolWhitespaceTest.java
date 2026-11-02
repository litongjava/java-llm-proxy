package nexus.io.llm.proxy.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolWhitespaceTest {
  @Test public void responsesPreserveWhitespaceDeltasAcrossEveryOutputProtocol() throws Exception {
    List<String> deltas = List.of("##", " ", "1. 中文", "\n", "\n", "```bat", "\r", "\n",
        "git clone --depth", " ", "1 repo", "\n", "\t", "  ", "echo done", "\n", "```", "\n");
    StringBuilder source = new StringBuilder();
    for (String delta : deltas) {
      source.append("data: ").append(JsonUtils.toJsonWithLongAsNumber(Kv.create()
          .set("type", "response.output_text.delta").set("delta", delta))).append("\n\n");
    }
    source.append("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":9,\"output_tokens\":4}}}\n\n");
    for (String target : List.of("anthropic", "openai", "gemini")) {
      byte[] input = source.toString().getBytes(StandardCharsets.UTF_8);
      // Also exercise split UTF-8 bytes and fragmented SSE frames.
      ByteArrayInputStream stream = new ByteArrayInputStream(input) {
        @Override public synchronized int read(byte[] bytes, int off, int len) {
          return super.read(bytes, off, Math.min(len, 1));
        }
      };
      String converted = new String(new ProtocolEventStream(stream, "responses", target, "fixture").readAllBytes(), StandardCharsets.UTF_8);
      List<String> actual = new ArrayList<>();
      for (String line : converted.split("\n")) {
        if (!line.startsWith("data:") || line.contains("[DONE]")) { continue; }
        Kv event = ProxyJson.parse(line.substring(5));
        if (target.equals("anthropic") && "content_block_delta".equals(event.getStr("type"))) {
          actual.add(ProxyJson.object(event.get("delta")).getStr("text"));
        } else if (target.equals("openai")) {
          for (Object choice : ProxyJson.list(event.get("choices"))) {
            Object text = ProxyJson.object(ProxyJson.object(choice).get("delta")).get("content");
            if (text instanceof String value) { actual.add(value); }
          }
        } else if (target.equals("gemini")) {
          for (Object candidate : ProxyJson.list(event.get("candidates"))) {
            for (Object part : ProxyJson.list(ProxyJson.object(ProxyJson.object(candidate).get("content")).get("parts"))) {
              if (ProxyJson.object(part).get("text") instanceof String value) { actual.add(value); }
            }
          }
        }
      }
      assertEquals(target, String.join("", deltas), String.join("", actual));
      assertTrue(target, actual.contains(" "));
      assertTrue(target, actual.contains("\n"));
      assertTrue(target, actual.contains("\t"));
    }
  }
}
