package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.model.VertexImportInput;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.JsonUtils;
import nexus.io.llm.proxy.validation.ManagementParameterValidator;

public class VertexCredentialService {
  public Kv importCredential(VertexImportInput input) throws Exception {
    ManagementParameterValidator.vertex(input);
    Kv credential = Kv.create().set("type", "vertex").set("service_account", input.credential)
        .set("email", input.credential.get("client_email")).set("project_id", input.credential.get("project_id"))
        .set("location", input.location == null ? "us-central1" : input.location);
    String name = Aop.get(CredentialService.class).save(input.name, credential);
    return Kv.create().set("status", "ok").set("name", name);
  }

  public Kv refresh(Kv credential) throws Exception {
    Kv account = ProxyJson.object(credential.get("service_account"));
    String pem = account.getStr("private_key");
    BusinessException.require(pem != null, 400, "NO_PRIVATE_KEY", "Service account has no private key");
    byte[] key = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
    PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
    long now = Instant.now().getEpochSecond();
    String tokenUrl = EnvUtils.get("proxy.oauth.vertex.token-url", ProxyJson.text(account, "token_uri", "https://oauth2.googleapis.com/token"));
    Kv claims = Kv.create().set("iss", account.get("client_email")).set("scope", "https://www.googleapis.com/auth/cloud-platform")
        .set("aud", tokenUrl).set("iat", BigInteger.valueOf(now)).set("exp", BigInteger.valueOf(now + 3600));
    String unsigned = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + encode(JsonUtils.toJson(claims));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(privateKey);
    signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
    String jwt = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    Kv result = Aop.get(OAuthService.class).post(tokenUrl, Kv.create().set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
        .set("assertion", jwt), false, null);
    BusinessException.require(result.getStr("access_token") != null, 502, "TOKEN_ERROR", "Service account token exchange failed");
    credential.set("access_token", result.get("access_token"))
        .set("expired", Instant.now().plusSeconds(ProxyJson.number(result, "expires_in", 3600)).toString())
        .set("last_refresh", Instant.now().toString());
    return credential;
  }

  private String encode(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
