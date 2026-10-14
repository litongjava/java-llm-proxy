package nexus.io.llm.proxy.service;

import java.util.HashSet;
import java.util.Set;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;

/** Request-local credential exclusions prevent retrying an already exhausted credential. */
public class CredentialRetryState {
  public static class NextCredential extends RuntimeException { private static final long serialVersionUID = 1L; }
  private final Set<String> attempted = new HashSet<>();
  public final String requestId = String.valueOf(SnowflakeIdUtils.id());
  public final long startedAt = System.currentTimeMillis();
  private final int maximum;
  public SettingsService.Selection next;
  public String tool = "";

  public CredentialRetryState() {
    Kv root = ProxyJson.object(Aop.get(DesktopConfigService.class).get(""));
    Kv retry = ProxyJson.object(ProxyJson.object(root.get("routing")).get("retry"));
    long configured = ProxyJson.number(retry, "max-retry-credentials", 0);
    maximum = configured <= 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, configured);
  }

  public boolean advance(Provider failed, String model, String protocol, String forced, String session) {
    attempted.add(failed.id);
    if (attempted.size() >= maximum) { return false; }
    try {
      next = Aop.get(SettingsService.class).select(model, protocol, forced, null, session, attempted, tool);
      return true;
    } catch (BusinessException unavailable) { return false; }
  }

  public static boolean retryable(int status) {
    return status == 401 || status == 402 || status == 403 || status == 408 || status == 429 || status >= 500;
  }
}
