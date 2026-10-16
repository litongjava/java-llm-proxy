package nexus.io.llm.proxy.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.jfinal.kit.Kv;
import nexus.io.tio.utils.json.JsonUtils;
import okhttp3.HttpUrl;

/** RFC 9449 DPoP and Huawei SDK-HMAC-SHA256 implemented with JCA. */
public class CodeArtsCryptoService {
  public String random(int size) {
    byte[] bytes = new byte[size];
    new SecureRandom().nextBytes(bytes);
    return base64(bytes);
  }
  public String challenge(String verifier) throws Exception { return base64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
  public Kv generateJwk() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair = generator.generateKeyPair();
    ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
    ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();
    return Kv.create().set("kty", "EC").set("crv", "P-256").set("x", scalar(publicKey.getW().getAffineX()))
        .set("y", scalar(publicKey.getW().getAffineY())).set("d", scalar(privateKey.getS()));
  }
  private String scalar(BigInteger value) {
    byte[] bytes = value.toByteArray();
    byte[] padded = new byte[32];
    int size = Math.min(bytes.length, 32);
    System.arraycopy(bytes, bytes.length - size, padded, 32 - size, size);
    return base64(padded);
  }
  public String dpop(Kv privateJwk, String method, String url) throws Exception {
    Kv publicJwk = Kv.create().set("kty", "EC").set("crv", "P-256").set("x", privateJwk.get("x")).set("y", privateJwk.get("y"));
    Kv header = Kv.create().set("alg", "ES256").set("typ", "dpop+jwt").set("jwk", publicJwk);
    Kv payload = Kv.create().set("htm", method).set("htu", url).set("iat", Instant.now().getEpochSecond()).set("jti", random(32));
    String signingInput = base64(JsonUtils.toJson(header).getBytes(StandardCharsets.UTF_8)) + "." + base64(JsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8));
    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
    BigInteger d = new BigInteger(1, Base64.getUrlDecoder().decode(privateJwk.getStr("d")));
    Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
    signer.initSign(KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, spec)));
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + base64(signer.sign());
  }
  public Kv sign(Kv credential, String method, String url, byte[] body, Kv extra) throws Exception {
    return sign(credential, method, url, body, extra, Instant.now());
  }
  public Kv sign(Kv credential, String method, String url, byte[] body, Kv extra, Instant now) throws Exception {
    HttpUrl target = HttpUrl.get(url);
    String path = target.encodedPath();
    if (!path.endsWith("/")) { path += "/"; }
    String query = target.encodedQuery() == null ? "" : target.encodedQuery();
    String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
    String payloadHash = sha256(body);
    TreeMap<String, String> headers = new TreeMap<>();
    String authority = target.uri().getRawAuthority();
    headers.put("host", authority);
    headers.put("x-sdk-date", stamp);
    headers.put("x-sdk-content-sha256", payloadHash);
    headers.put("x-security-token", credential.getStr("security_token"));
    if (!"GET".equals(method)) { headers.put("content-type", "application/json"); }
    extra.forEach((key, value) -> headers.put(key.toString().toLowerCase(Locale.ROOT), value.toString()));
    String signedHeaders = String.join(";", headers.keySet());
    StringBuilder lines = new StringBuilder();
    headers.forEach((key, value) -> lines.append(key).append(':').append(value).append('\n'));
    String canonical = method + "\n" + path + "\n" + query + "\n" + lines + "\n" + signedHeaders + "\n" + payloadHash;
    String toSign = "SDK-HMAC-SHA256\n" + stamp + "\n" + sha256(canonical.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(credential.getStr("secret_access_key").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature = HexFormat.of().formatHex(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
    return Kv.create().set(headers).set("Authorization", "SDK-HMAC-SHA256 Access=" + credential.getStr("access_key_id")
        + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature);
  }
  public String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
