package nexus.io.llm.proxy.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import nexus.io.llm.proxy.model.ApiAccessRemarkLocator;
import nexus.io.llm.proxy.model.ApiAccessRemarkQuery;
import nexus.io.llm.proxy.model.ApiAccessRemarkUpdate;
import nexus.io.model.exception.BusinessException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiAccessRemarkServiceTest {
  private static final String SECTION = "codex-api-key";

  @Test
  public void savesRemarksAndFallsBackWhenTheConfigurationChanges() throws Exception {
    Path directory = Files.createTempDirectory("api-access-remarks");
    ApiAccessRemarkService service = new ApiAccessRemarkService(directory);
    ApiAccessRemarkLocator first = locator("sk-first", "https://api.example.com", "");

    service.save(update(List.of(), List.of(first), List.of(first), "Primary account"));

    assertEquals(List.of("Primary account"), service.resolve(List.of(query("sk-first", ""))));
    assertEquals(List.of("Primary account"), service.resolve(List.of(query("sk-first", "{\"models\":[\"two\"]}"))));
    assertEquals(List.of(""), service.resolve(List.of(query("sk-other", ""))));
  }

  @Test
  public void keepsEachGroupNoteAndReplacesOnlyEditedRecords() throws Exception {
    Path directory = Files.createTempDirectory("api-access-remarks");
    ApiAccessRemarkService service = new ApiAccessRemarkService(directory);
    ApiAccessRemarkLocator first = locator("sk-first", "https://api.example.com", "");
    ApiAccessRemarkLocator second = locator("sk-second", "https://api.example.com", "");

    service.save(update(List.of(), List.of(first), List.of(first, second), "First"));
    service.save(update(List.of(), List.of(second), List.of(first, second), "Second"));
    assertEquals(List.of("First", "Second"), service.resolve(List.of(query("sk-first", ""), query("sk-second", ""))));

    ApiAccessRemarkLocator edited = locator("sk-first", "https://api.example.com", "{\"models\":[\"three\"]}");
    service.save(update(List.of(first), List.of(edited), List.of(edited, second), "First edited"));
    assertEquals(List.of("First edited", "Second"), service.resolve(List.of(
        query("sk-first", "{\"models\":[\"three\"]}"), query("sk-second", ""))));

    service.save(update(List.of(edited, second), List.of(), List.of(), ""));
    assertEquals(List.of("", ""), service.resolve(List.of(
        query("sk-first", "{\"models\":[\"three\"]}"), query("sk-second", ""))));
  }

  @Test
  public void resolvesUnnamedRecordsForNameSensitiveSections() throws Exception {
    Path directory = Files.createTempDirectory("api-access-remarks");
    ApiAccessRemarkService service = new ApiAccessRemarkService(directory);
    ApiAccessRemarkLocator unnamed = locator("sk-first", "https://api.example.com", "");
    unnamed.setProviderName("");
    service.save(update(List.of(), List.of(unnamed), List.of(unnamed), "Legacy group"));

    ApiAccessRemarkQuery named = query("sk-first", "");
    named.setProviderName("Renamed group");
    assertEquals(List.of("Legacy group"), service.resolve(List.of(named)));
  }

  @Test
  public void storesOnlyHashesAndSurvivesANewServiceInstance() throws Exception {
    Path directory = Files.createTempDirectory("api-access-remarks");
    ApiAccessRemarkLocator first = locator("sk-secret-value", "https://api.example.com", "");
    new ApiAccessRemarkService(directory).save(update(List.of(), List.of(first), List.of(first), "Visible note"));

    String content = Files.readString(directory.resolve("webui-api-access-remarks.json"), StandardCharsets.UTF_8);
    assertFalse(content.contains("sk-secret-value"));
    assertTrue(content.contains("Visible note"));
    assertEquals(List.of("Visible note"),
        new ApiAccessRemarkService(directory).resolve(List.of(query("sk-secret-value", ""))));
  }

  @Test(expected = BusinessException.class)
  public void rejectsInvalidSections() throws Exception {
    Path directory = Files.createTempDirectory("api-access-remarks");
    new ApiAccessRemarkService(directory).resolve(List.of(new ApiAccessRemarkQuery()));
  }

  @Test(expected = BusinessException.class)
  public void rejectsLongNotes() throws Exception {
    Path directory = Files.createTempDirectory("api-access-remarks");
    ApiAccessRemarkLocator first = locator("sk-first", "https://api.example.com", "");
    new ApiAccessRemarkService(directory).save(update(List.of(), List.of(first), List.of(first), "x".repeat(81)));
  }

  private static ApiAccessRemarkQuery query(String apiKey, String configIdentity) {
    ApiAccessRemarkQuery query = new ApiAccessRemarkQuery();
    query.setProviderSection(SECTION);
    query.setProviderName("");
    query.setBaseUrl("https://api.example.com");
    query.setApiKeys(List.of(apiKey));
    query.setConfigIdentity(configIdentity);
    return query;
  }

  private static ApiAccessRemarkLocator locator(String apiKey, String baseUrl, String configIdentity) {
    ApiAccessRemarkLocator locator = new ApiAccessRemarkLocator();
    locator.setProviderName("");
    locator.setBaseUrl(baseUrl);
    locator.setApiKeys(List.of(apiKey));
    locator.setConfigIdentity(configIdentity);
    return locator;
  }

  private static ApiAccessRemarkUpdate update(
      List<ApiAccessRemarkLocator> previous,
      List<ApiAccessRemarkLocator> records,
      List<ApiAccessRemarkLocator> all,
      String remark) {
    ApiAccessRemarkUpdate update = new ApiAccessRemarkUpdate();
    update.setProviderSection(SECTION);
    update.setPreviousRecords(previous);
    update.setRecords(records);
    update.setAllRecords(all);
    update.setRemark(remark);
    return update;
  }
}
