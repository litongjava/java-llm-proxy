package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class ZCodeRetryGuardTest {
  @Test
  public void captchaAndAccountRestrictionsOverrideConfiguredRequestRetries() throws Exception {
    Path config = Files.createTempFile("zcode-retry-test-", ".yaml");
    Files.writeString(config, "requests:\n  request-retry: 3\n");
    String previous = EnvUtils.get("proxy.config");
    EnvUtils.set("proxy.config", config.toString());
    try {
      Provider provider = new Provider();
      provider.upstreamType = "zcode";
      provider.authType = "oauth";
      provider.metadata = Kv.create();
      for (int code : new int[]{3007, 3012}) {
        AtomicInteger calls = new AtomicInteger();
        try {
          new RequestRetryService().execute(provider, () -> {
            calls.incrementAndGet();
            return new Response.Builder().request(new Request.Builder().url("https://example.invalid/messages").build())
                .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden").header("Content-Type", "application/json")
                .body(ResponseBody.create("{\"code\":" + code + "}", MediaType.get("application/json"))).build();
          });
          fail("Provider restriction must stop before retry");
        } catch (BusinessException expected) {
          assertEquals(code == 3007 ? "ZCODE_CAPTCHA_REQUIRED" : "ZCODE_ACCESS_RESTRICTED", expected.getCode());
        }
        assertEquals(1, calls.get());
      }
    } finally {
      EnvUtils.set("proxy.config", previous);
      Files.deleteIfExists(config);
    }
  }
}
