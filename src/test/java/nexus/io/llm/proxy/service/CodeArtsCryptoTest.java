package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import com.jfinal.kit.Kv;
import org.junit.Test;

public class CodeArtsCryptoTest {
  @Test
  public void dpopUsesVerifiableEs256PublicJwkAndBoundMethodUrl() throws Exception {
    CodeArtsCryptoService crypto = new CodeArtsCryptoService();
    Kv key = crypto.generateJwk();
    String jwt = crypto.dpop(key, "POST", "https://example.test/token");
    String[] parts = jwt.split("\\.");
    Kv header = decode(parts[0]);
    Kv payload = decode(parts[1]);
    Kv publicJwk = ProxyJson.object(header.get("jwk"));
    assertFalse(publicJwk.containsKey("d"));
    assertEquals("dpop+jwt", header.getStr("typ"));
    assertEquals("POST", payload.getStr("htm"));
    assertEquals("https://example.test/token", payload.getStr("htu"));
    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    ECPoint point = new ECPoint(new BigInteger(1, Base64.getUrlDecoder().decode(publicJwk.getStr("x"))), new BigInteger(1, Base64.getUrlDecoder().decode(publicJwk.getStr("y"))));
    Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
    verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec.class))));
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    assertNotEquals(jwt, crypto.dpop(key, "POST", "https://example.test/token"));
    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", crypto.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
  }

  @Test
  public void huaweiSignatureBindsExactPayloadAndBenefitHeader() throws Exception {
    CodeArtsCryptoService crypto = new CodeArtsCryptoService();
    Kv credential = Kv.create().set("access_key_id", "ak").set("secret_access_key", "sk").set("security_token", "st");
    Instant now = Instant.parse("2026-10-16T00:00:00Z");
    Kv signed = crypto.sign(credential, "POST", "https://example.test:8443/api/v2/chat/completions", "{}".getBytes(StandardCharsets.UTF_8), Kv.create().set("maas_type", "benefit"), now);
    assertEquals("20261016T000000Z", signed.getStr("x-sdk-date"));
    assertEquals("example.test:8443", signed.getStr("host"));
    assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", signed.getStr("x-sdk-content-sha256"));
    assertTrue(signed.getStr("Authorization").contains("SignedHeaders=content-type;host;maas_type;x-sdk-content-sha256;x-sdk-date;x-security-token"));
    assertTrue(signed.getStr("Authorization").endsWith("Signature=ce788d158cfa13a9574381097be804ecc640c8cc41d28ca36568c88bf76eb4d7"));
    Kv changed = crypto.sign(credential, "POST", "https://example.test:8443/api/v2/chat/completions", "{ }".getBytes(StandardCharsets.UTF_8), Kv.create().set("maas_type", "benefit"), now);
    assertNotEquals(signed.getStr("Authorization"), changed.getStr("Authorization"));
    Kv unsignedBenefit = crypto.sign(credential, "POST", "https://example.test:8443/api/v2/chat/completions", "{}".getBytes(StandardCharsets.UTF_8), Kv.create(), now);
    assertNotEquals(signed.getStr("Authorization"), unsignedBenefit.getStr("Authorization"));
  }

  private Kv decode(String encoded) { return ProxyJson.parse(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)); }
}
