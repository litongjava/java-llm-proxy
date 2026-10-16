package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.jfinal.kit.Kv;
import nexus.io.chat.UniChatClient;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.LoomySmsInput;
import nexus.io.llm.proxy.model.LoomySmsResult;
import nexus.io.llm.proxy.model.LoomyLoginResult;
import nexus.io.llm.proxy.validation.LoomyParameterValidator;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Xunfei SMS sessions and OpenAI-compatible inference; sessions cannot be refreshed. */
public class LoomyService {
  public String apiBaseUrl() { return setting("api-base-url", "https://loomyad.xunfei.cn/api/v1"); }
  private String setting(String key, String fallback) { return EnvUtils.get("proxy.oauth.loomy." + key, fallback); }

  public LoomySmsResult sendSms(LoomySmsInput input) throws Exception {
    LoomyParameterValidator.validate(input, false);
    Kv data = account("/login/phone/sendMsgCode", Kv.create().set("ccode", "86").set("phone", input.phone).set("expire", 300));
    String msgid = data.getStr("msgid");
    BusinessException.require(msgid != null && !msgid.isBlank(), 502, "LOOMY_SMS_FAILED", "Loomy did not return a message ID");
    LoomySmsResult result = new LoomySmsResult();
    result.msgid = msgid;
    return result;
  }

  public Kv login(LoomySmsInput input) throws Exception {
    LoomyParameterValidator.validate(input, true);
    Kv data = account("/login/phone/checkCode", Kv.create().set("ccode", "86").set("phone", input.phone)
        .set("mcode", input.code).set("msgid", input.msgid).set("expire", 1209600));
    String session = data.getStr("session");
    String user = data.getStr("userid");
    BusinessException.require(session != null && !session.isBlank() && user != null && !user.isBlank(),
        502, "LOOMY_LOGIN_FAILED", "Loomy did not return a complete session");
    return Kv.create().set("type", "loomy").set("access_token", session).set("userid", user).set("account_id", user)
        .set("phone", input.phone).set("label", input.phone).set("refreshable", false)
        .set("expired", Instant.now().plusSeconds(1209600).toString()).set("expiry_estimated", true);
  }

  public LoomyLoginResult loginAndSave(LoomySmsInput input) throws Exception {
    Kv credential = login(input);
    try { credential.set("models", models(credential)); } catch (Exception ignored) {
      // A catalog outage must not discard a successfully issued session.
    }
    LoomyLoginResult result = new LoomyLoginResult();
    result.name = Aop.get(CredentialService.class).save(null, credential);
    return result;
  }

  private Kv account(String path, Kv parameters) throws Exception {
    Kv base = Kv.create().set("appid", setting("app-id", "GM3LOOMY")).set("modelid", "Web").set("version", "1.0.0")
        .set("devid", "web").set("ua", "Loomy|Desktop|Electron|macOS").set("traceid", UUID.randomUUID().toString().replace("-", ""));
    String body = JsonUtils.toJsonWithLongAsNumber(Kv.create().set("base", base).set("param", parameters));
    String date = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC).format(Instant.now());
    Kv headers = signingHeaders(path, body, date, UUID.randomUUID().toString());
    Request.Builder request = new Request.Builder().url(setting("account-base-url", "https://account.xfinfr.com") + path)
        .post(RequestBody.create(body, MediaType.get("application/json")));
    headers.forEach((name, value) -> request.header(name.toString(), value.toString()));
    return ProxyJson.object(envelope(execute(request.build())));
  }

  public Kv signingHeaders(String path, String body, String date, String nonce) throws Exception {
    String md5 = body.isEmpty() ? "" : Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8)));
    // Account endpoints contain only ASCII path segments and no query string.
    String canonical = String.join("\n", "POST", path, "", md5, "application/json", date, nonce, "", "");
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(setting("access-key-secret", "zsak6eadrbawz683wf5r3m2snrwj868r").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    String signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    Kv headers = Kv.create().set("Authorization", "account " + setting("access-key-id", "2thryby66wxi53sk") + ":" + signature)
        .set("Date", date).set("Nonce", nonce).set("Content-Type", "application/json");
    if (!md5.isEmpty()) { headers.set("Content-MD5", md5); }
    return headers;
  }

  private Kv execute(Request request) throws Exception {
    ManagementHttpService transport = Aop.get(ManagementHttpService.class);
    try (Response response = UniChatClient.execute(transport.client(transport.configuredProxy(), 60), request)) {
      BusinessException.require(response.isSuccessful() && response.body() != null, 502, "LOOMY_HTTP_FAILED", "Loomy request failed (HTTP " + response.code() + ")");
      return ProxyJson.parse(response.body().string());
    }
  }

  private Object envelope(Kv response) {
    BusinessException.require("000000".equals(response.getStr("code")), 502, "LOOMY_REQUEST_FAILED", "Loomy rejected the request (code " + response.getStr("code") + ")");
    return response.get("data");
  }

  public List<Kv> models(Kv credential) throws Exception {
    Kv response = execute(new Request.Builder().url(apiBaseUrl() + "/models").header("Accept", "application/json")
        .header("token", credential.getStr("access_token")).build());
    Object data = envelope(response);
    List<?> entries = data instanceof List<?> list ? list : ProxyJson.list(ProxyJson.object(data).get("data"));
    List<Kv> models = new ArrayList<>();
    for (Object entry : entries) {
      Kv model = ProxyJson.object(entry);
      if ("chat".equals(model.getStr("type")) && model.getStr("id") != null) { models.add(Kv.create().set(model)); }
    }
    return models;
  }

  public Kv headers(Kv credential) {
    String token = credential.getStr("access_token");
    return Kv.create().set("Authorization", "Bearer " + token).set("token", token).set("Accept", "text/event-stream");
  }

  public Kv refresh(Kv credential) {
    throw new BusinessException(401, "LOOMY_RELOGIN_REQUIRED", "Loomy sessions cannot be refreshed; sign in again with an SMS code");
  }
}
