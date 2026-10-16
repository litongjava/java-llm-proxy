package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;

/** Fixed CLI authorization fields with extensible user and provider metadata. */
public class ZCodeLoginResponse {
  public String flow_id;
  public String authorize_url;
  public Long expires_at;
  public Integer poll_interval_sec;
  public String status;
  public String token;
  public Kv user;
  public Kv bigmodel;
  public Kv zai;
}
