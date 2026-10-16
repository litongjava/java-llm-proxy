package nexus.io.llm.proxy.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class CodexLiveServiceTest {
  @Test
  public void locationAcceptsOriginalCallFormatsWithoutUsingUpstreamHost() {
    assertEquals("rtc_1", CodexLiveService.locationId("rtc_1"));
    assertEquals("rtc_1", CodexLiveService.locationId("/v1/realtime/calls/rtc_1"));
    assertEquals("rtc_2", CodexLiveService.locationId("https://api.openai.com/v1/realtime?call_id=rtc_2"));
    assertNull(CodexLiveService.locationId(null));
    assertNull(CodexLiveService.locationId("/v1/realtime/calls/.."));
    assertNull(CodexLiveService.locationId("/v1/realtime/calls/rtc%2Fevil"));
  }

  @Test
  public void sidebandPreservesRouteStyleAndAddsQuicksilverIntent() {
    assertEquals("https://api.openai.com/v1/live/rtc_1",
        CodexLiveService.sidebandUrl("https://api.openai.com/v1/", "/tools/codex/v1/live/rtc_1", "rtc_1"));
    assertEquals("https://api.openai.com/v1/realtime/calls/rtc_1",
        CodexLiveService.sidebandUrl("https://api.openai.com/v1", "/v1/realtime/calls/rtc_1", "rtc_1"));
    assertEquals("https://api.openai.com/v1/realtime?intent=quicksilver&call_id=rtc_1",
        CodexLiveService.sidebandUrl("https://api.openai.com/v1", "/v1/realtime", "rtc_1"));
  }

  @Test
  public void realtimeAliasesUseCodexLiveModel() {
    assertEquals("gpt-live-1-codex", CodexLiveService.normalizeModel(null));
    assertEquals("gpt-live-1-codex", CodexLiveService.normalizeModel("gpt-realtime"));
    assertEquals("gpt-live-1-codex", CodexLiveService.normalizeModel("gpt-realtime-2025-08-28"));
    assertEquals("gpt-live-1-codex", CodexLiveService.normalizeModel("gpt-4o-realtime-preview"));
    assertEquals("custom-live", CodexLiveService.normalizeModel("custom-live"));
  }
}
