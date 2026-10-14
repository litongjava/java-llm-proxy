package nexus.io.llm.proxy.service;

import java.math.BigInteger;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.Provider;
import nexus.io.llm.proxy.model.UsageQuery;

public class ApiKeyUsageService {
  public Kv usage() {
    Kv providers = Kv.create();
    for (Provider provider : Aop.get(SettingsService.class).snapshot().providers) {
      UsageQuery query = new UsageQuery();
      query.provider = provider.name;
      query.pageSize = 1;
      Kv report = Aop.get(StorageService.class).usage(query);
      Kv stats = ProxyJson.object(report.get("stats"));
      long success = ProxyJson.number(stats, "success", 0);
      long total = ProxyJson.number(stats, "total", 0);
      String type = provider.upstreamType == null ? provider.protocol : provider.upstreamType;
      Kv entries = ProxyJson.object(providers.get(type));
      entries.set(provider.baseUrl + "|" + provider.apiKey, Kv.create().set("success", BigInteger.valueOf(success))
          .set("failed", BigInteger.valueOf(total - success)).set("recent_requests", Aop.get(StorageService.class).recentRequests(provider.name)));
      providers.set(type, entries);
    }
    return providers;
  }
}
