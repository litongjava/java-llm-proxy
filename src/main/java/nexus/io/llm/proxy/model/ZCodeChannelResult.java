package nexus.io.llm.proxy.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Deliberately excludes tokens, API keys and third-party account secrets. */
@Data
@AllArgsConstructor
public class ZCodeChannelResult {
  private String name;
  private String channel;
  private boolean codingPlanKeyAvailable;
}
