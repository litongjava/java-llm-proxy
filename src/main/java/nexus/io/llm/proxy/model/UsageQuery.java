package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class UsageQuery {
  public int page = 1;
  public int pageSize = 50;
  public long from;
  public long to = Long.MAX_VALUE;
  public String model;
  public String provider;
  public String apiKeyName;
  public String source;
  public String originator;
  public String session_id;
  public String thread_id;
  public String result;
}
