package nexus.io.llm.proxy.service;

import java.util.LinkedHashMap;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.llm.proxy.model.Provider;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelOwnershipTest {
  @Test public void retainsRealOwnersAndDistinctSourcesWithoutCredentialNames() {
    var models = new LinkedHashMap<String,Kv>();
    Provider codex = new Provider();codex.upstreamType="codex";codex.name="private-account@example.com";codex.credentialName="private.json";
    ManagementService.addModel(models,"alias","gpt-6-astra",codex,Kv.create().set("owned_by","openai"));
    Provider other = new Provider();other.upstreamType="openai";
    ManagementService.addModel(models,"alias","gpt-6-astra",other,Kv.create().set("owned_by","openai"));
    assertEquals("openai",models.get("alias").getStr("owned_by"));
    assertEquals(2,ProxyJson.list(models.get("alias").get("sources")).size());
    assertFalse(models.toString().contains("private"));
    ManagementService.addModel(models,"alias","other-model",other,Kv.create().set("owned_by","other-owner"));
    assertEquals("multiple",models.get("alias").getStr("owned_by"));
    assertEquals(List.of("openai","other-owner"),models.get("alias").get("owners"));
  }
  @Test public void openAiCompatibilityAloneDoesNotInventOwnership() {
    var models = new LinkedHashMap<String,Kv>();
    Provider custom = new Provider();custom.name="Internal gateway";custom.protocol="openai";
    ManagementService.addModel(models,"custom","custom",custom,null);
    assertEquals("unknown",models.get("custom").getStr("owned_by"));
  }
}
