package com.litongjava.llm.proxy.mode;

public class GoogleWsConnectParam {

  private String apiKey;
  private String apiClient;
  private String userAgent;

  public GoogleWsConnectParam() {
    super();
    // TODO Auto-generated constructor stub
  }

  public GoogleWsConnectParam(String apiKey, String apiClient, String userAgent) {
    super();
    this.apiKey = apiKey;
    this.apiClient = apiClient;
    this.userAgent = userAgent;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getApiClient() {
    return apiClient;
  }

  public void setApiClient(String apiClient) {
    this.apiClient = apiClient;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }
}
