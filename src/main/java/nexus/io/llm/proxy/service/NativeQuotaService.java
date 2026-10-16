package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Read-only account quota queries. Credentials and provider signing stay on the server. */
public class NativeQuotaService {
  public Kv fetch(String index) throws Exception {
    CredentialService credentials = Aop.get(CredentialService.class);
    String name = credentials.resolve(index);
    BusinessException.require(!Boolean.TRUE.equals(credentials.read(name).get("disabled")), 400, "DISABLED", "Credential is disabled");
    Kv credential = credentials.refreshed(name, false);
    String provider = credential.getStr("type");
    Request request = prepare(provider, credential);
    ManagementHttpService http = Aop.get(ManagementHttpService.class);
    String proxy = ProxyJson.text(credential, "proxy_url", http.configuredProxy());
    try (Response response = UniChatClient.execute(http.client(proxy, 30), request)) {
      BusinessException.require(response.isSuccessful(), 502, "QUOTA_HTTP", "Quota upstream HTTP " + response.code());
      byte[] bytes = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(4 * 1024 * 1024 + 1);
      BusinessException.require(bytes.length <= 4 * 1024 * 1024, 502, "QUOTA_SIZE", "Quota response is too large");
      return Kv.create().set("provider", provider).set("payload", ProxyJson.parse(new String(bytes, StandardCharsets.UTF_8)))
          .set("source", request.url().toString()).set("fetchedAt", System.currentTimeMillis());
    }
  }

  public Request prepare(String provider, Kv credential) throws Exception {
    Kv headers = Kv.create().set("Accept", "application/json")
        .set("Authorization", "Bearer " + ProxyJson.text(credential, "access_token", ""));
    String url;
    String body = null;
    switch (provider) {
      case "buddy", "workbuddy", "workbuddycn" -> {
        BuddyService buddy = Aop.get(BuddyService.class);
        url = buddy.origin(provider) + "/v2/billing/meter/get-user-resource";
        headers.set(buddy.headers(provider, credential, null));
        if (credential.getStr("user_id") != null) headers.set("X-User-Id", credential.getStr("user_id"));
        body = "{}";
      }
      case "cline" -> {
        ClineService cline = Aop.get(ClineService.class);
        url = cline.apiBaseUrl() + "/users/me/plan/usage-limits";
        headers.set(cline.headers());
      }
      case "minimax" -> url = EnvUtils.get("proxy.oauth.minimax.quota-url", "https://agent.minimax.cn/minimax-cloud/api/v1/credit/details");
      case "lobsterai" -> {
        url = Aop.get(LobsterAiService.class).apiBaseUrl() + "/api/user/profile-summary";
        headers.set(Aop.get(LobsterAiService.class).headers(false));
      }
      case "raccoon" -> {
        url = Aop.get(RaccoonService.class).apiBaseUrl().replaceFirst("/api/web/llm/v2$", "") + "/api/web/points/v1/balance";
        headers.set(Aop.get(RaccoonService.class).headers(credential));
      }
      case "trae" -> {
        url = EnvUtils.get("proxy.oauth.trae.quota-url", "https://api.trae.cn/trae/api/v2/pay/ide_user_ent_usage");
        headers = traeHeaders(credential);
        body = "{\"require_usage\":true,\"req_source\":2}";
      }
      case "qoder", "qodercn" -> {
        url = EnvUtils.get("proxy.oauth." + provider + ".open-api-url", "qodercn".equals(provider)
            ? "https://openapi.qoder.com.cn" : "https://openapi.qoder.sh").replaceAll("/+$", "") + "/sash/api/v2/me/usage";
        headers.set("Authorization", "Bearer " + ProxyJson.text(credential, "security_oauth_token", credential.getStr("access_token")))
            .set("Cosy-ClientType", "10").set("User-Agent", "Qoder");
      }
      case "zcode" -> {
        url = EnvUtils.get("proxy.oauth.zcode.origin", "https://zcode.z.ai").replaceAll("/+$", "") + "/api/v1/zcode-plan/billing/balance";
        headers.set(Aop.get(ZCodeService.class).headers(credential));
      }
      case "codearts" -> {
        url = EnvUtils.get("proxy.oauth.codearts.quota-url", "https://snap-access.cn-north-4.myhuaweicloud.com/snap-manager/v1/statistics/plugin");
        headers = Aop.get(CodeArtsCryptoService.class).sign(credential, "GET", url, new byte[0], Kv.create());
        headers.set("Agent-Type", "PromptCenter").set("X-Language", "zh-cn");
      }
      case "loomy" -> {
        url = Aop.get(LoomyService.class).apiBaseUrl() + "/points/records?pageNo=1&pageSize=1&recordType=all";
        headers.remove("Authorization");
        headers.set("token", credential.getStr("access_token"));
      }
      default -> throw new IllegalArgumentException("Quota query is not supported for " + provider);
    }
    Request.Builder request = new Request.Builder().url(url);
    headers.forEach((key, value) -> { if (value != null) request.header(key.toString(), value.toString()); });
    if (body != null) request.post(RequestBody.create(body, MediaType.get("application/json")));
    return request.build();
  }

  private Kv traeHeaders(Kv credential) throws Exception {
    String uid = credential.getStr("uid");
    BusinessException.require(uid != null && !uid.isBlank(), 400, "TRAE_UID", "TRAE quota requires user ID");
    byte[] device = seeded(uid, "devid");
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < 15; i++) digits.append((device[i] & 255) % 10);
    byte[] market = seeded(uid, "market");
    market[6] = (byte) ((market[6] & 15) | 64);
    market[8] = (byte) ((market[8] & 63) | 128);
    String hex = HexFormat.of().formatHex(market, 0, 16);
    String marketId = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
        + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    return Kv.create().set("Authorization", "Cloud-IDE-JWT " + credential.getStr("access_token"))
        .set("Accept", "*/*").set("Accept-Language", "zh-CN").set("User-Agent", "VSCode 1.107.1 (TRAE SOLO CN)")
        .set("X-Market-Client-Id", "VSCode 1.107.1").set("X-Market-User-Id", marketId).set("X-User-Region", "CN")
        .set("X-Device-Id", digits.toString()).set("X-Lgw-Req-Sdk-Type", "3").set("Package-Type", "stable_cn")
        .set("X-Lscbd-Aid", "787976").set("X-Lscbd-Platform", "windows")
        .set("App-Version", EnvUtils.get("proxy.oauth.trae.client-version", "0.1.52"))
        .set("X-Tt-Trace-Id", "00-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01")
        .set("Vscode-Sessionid", HexFormat.of().formatHex(seeded(uid, "sess")))
        .set("X-Request-Id", UUID.randomUUID().toString()).set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "no-cors").set("Sec-Fetch-Site", "none");
  }

  private byte[] seeded(String uid, String salt) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update((salt + ":" + uid).getBytes(StandardCharsets.UTF_8));
    return digest.digest(new byte[4]);
  }
}
