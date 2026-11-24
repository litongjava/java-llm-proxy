package nexus.io.llm.proxy.model;

import lombok.Data;

@Data
public class WebUsageQuery {
  public Long start;
  public Long end;
  public String model;
  public String provider;
  public String source;
  public String upstream;
  public String originator;
  public String sessionId;
  public String threadId;
  public String apiKey;
  public Boolean failed;
  public Boolean canceled;
  public int page = 1;
  public int pageSize = 50;
}
