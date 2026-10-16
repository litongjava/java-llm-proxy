package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import okhttp3.Response;

public class RequestRetryService {
  @FunctionalInterface public interface Attempt { Response execute() throws Exception; }

  public Response execute(Provider provider, Attempt attempt) throws Exception {
    Kv root = ProxyJson.object(Aop.get(DesktopConfigService.class).get(""));
    Kv requests = ProxyJson.object(root.get("requests"));
    Kv retry = ProxyJson.object(ProxyJson.object(root.get("routing")).get("retry"));
    long global = ProxyJson.number(retry, "request-retry", ProxyJson.number(requests, "request-retry", 0));
    long configured = ProxyJson.number(provider.metadata, "request-retry", global);
    int retries = (int) Math.max(0, Math.min(10, configured < 0 ? global : configured));
    long maximumWait = Math.max(0, Math.min(60, ProxyJson.number(retry, "max-retry-interval", 30))) * 1000;
    long waited = 0;
    for (int index = 0; ; index++) {
      Response response;
      try { response = attempt.execute(); }
      catch (IOException error) {
        if (index >= retries) { throw error; }
        long delay = Math.min(2000, 100L << index);
        if (waited + delay > maximumWait) { throw error; }
        Thread.sleep(delay); waited += delay; continue;
      }
      int status = response.code();
      if ("zcode".equals(provider.upstreamType)) {
        Aop.get(ZCodeTransportService.class).rejectAccessChallenge(response);
      }
      if (index >= retries || (status != 403 && status != 429 && status != 408 && status < 500)) { return response; }
      long delay = retryDelay(response.header("Retry-After"), index);
      if (waited + delay > maximumWait) { return response; }
      response.close();
      Thread.sleep(delay); waited += delay;
    }
  }

  private long retryDelay(String header, int attempt) {
    if (header != null) {
      try { return Math.max(0, Math.min(3600000, Long.parseLong(header) * 1000)); }
      catch (NumberFormatException ignored) {
        try { return Math.max(0, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()); }
        catch (RuntimeException invalid) { }
      }
    }
    return Math.min(2000, 100L << attempt);
  }
}
