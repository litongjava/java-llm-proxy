package nexus.io.llm.proxy.model;

import lombok.Data;

import com.jfinal.kit.Kv;

@Data
public class RequestRecord {
  public String id;
  public long timestamp;
  public String method;
  public String path;
  public String protocol;
  public String model;
  public String provider;
  public String apiKeyName;
  public String source;
  public boolean stream;
  public int status;
  public long durationMs;
  public long firstByteMs;
  public long inputTokens;
  public long outputTokens;
  public long cachedTokens;
  public long reasoningTokens;
  public double cost;
  public Kv requestHeaders = Kv.create();
  public String requestBody;
  public Kv upstreamRequestHeaders = Kv.create();
  public String upstreamRequestBody;
  public Kv upstreamResponseHeaders = Kv.create();
  public String upstreamResponseBody;
  public Kv responseHeaders = Kv.create();
  public String responseBody;
  public boolean requestTruncated;
  public boolean responseTruncated;
  public String error;
}
