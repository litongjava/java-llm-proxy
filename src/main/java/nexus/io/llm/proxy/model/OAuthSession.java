package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;

public class OAuthSession {
  public OAuthProviderSpec spec;
  public String state;
  public String verifier;
  public String deviceCode;
  public long expiresAt;
  public long nextPoll;
  public int interval = 5;
  public String status = "wait";
  public String error;
  public String credentialName;
  public String pluginId;
  public String pluginProvider;
  public String pluginState;
  public Kv pluginMetadata;
}
