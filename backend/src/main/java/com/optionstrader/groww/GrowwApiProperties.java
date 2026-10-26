package com.optionstrader.groww;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groww.api")
public class GrowwApiProperties {
    private boolean enabled;
    private String baseUrl = "https://api.groww.in";
    private String apiKey = "";
    private String apiSecret = "";
    private String accessToken = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public boolean hasCredentials() {
        return !accessToken.isBlank() || (!apiKey.isBlank() && !apiSecret.isBlank());
    }
}