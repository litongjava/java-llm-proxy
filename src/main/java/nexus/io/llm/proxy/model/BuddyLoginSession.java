package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;

/** Server-only state; token and upstream state must not be serialized to clients. */
public class BuddyLoginSession {
  public String provider;
  public String state;
  public String authUrl;
  public long expiresAt;
  public long nextPoll;
  public Kv token;
  public Kv credential;
}
