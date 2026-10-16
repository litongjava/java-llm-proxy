package nexus.io.llm.proxy.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpstreamUrlServiceTest {
  @Test public void appendsAnthropicVersionAfterGatewayPrefix() {
    assertEquals("https://api.anthropic.com/v1/messages",
        UpstreamUrlService.endpoint("https://api.anthropic.com", "anthropic", "/messages"));
    assertEquals("https://ai.gitee.com/anthropic/v1/messages",
        UpstreamUrlService.endpoint("https://ai.gitee.com/anthropic/", "anthropic", "/messages"));
    assertEquals("https://ai.gitee.com/anthropic/v1/messages/count_tokens",
        UpstreamUrlService.endpoint("https://ai.gitee.com/anthropic", "anthropic", "/messages/count_tokens"));
  }

  @Test public void acceptsExistingVersionedConfigurations() {
    assertEquals("https://ai.gitee.com/anthropic/v1/messages",
        UpstreamUrlService.endpoint("https://ai.gitee.com/anthropic/v1/", "anthropic", "/messages"));
  }

  @Test public void preservesOtherProtocols() {
    assertEquals("https://example.com/v1/chat/completions",
        UpstreamUrlService.endpoint("https://example.com/v1", "openai", "/chat/completions"));
    assertEquals("https://example.com/v1beta/models/test:generateContent",
        UpstreamUrlService.endpoint("https://example.com/v1beta/models", "gemini", "/test:generateContent"));
  }
}
