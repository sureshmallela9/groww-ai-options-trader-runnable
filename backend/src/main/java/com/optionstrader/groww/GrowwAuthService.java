package com.optionstrader.groww;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GrowwAuthService {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private final RestClient client;
    private final GrowwApiProperties properties;
    private volatile CachedToken cachedToken;

    public GrowwAuthService(@Qualifier("growwRestClient") RestClient client, GrowwApiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled() && properties.hasCredentials();
    }

    public synchronized String accessToken() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Groww integration is disabled.");
        }
        if (!properties.getAccessToken().isBlank()) {
            return properties.getAccessToken().trim();
        }
        if (properties.getApiKey().isBlank() || properties.getApiSecret().isBlank()) {
            throw new IllegalStateException("Groww API credentials are not configured.");
        }
        if (cachedToken != null && cachedToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cachedToken.value();
        }

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String checksum = checksum(properties.getApiSecret(), timestamp);
        JsonNode response = client.post()
            .uri("/v1/token/api/access")
            .header("Authorization", "Bearer " + properties.getApiKey().trim())
            .header("Accept", "application/json")
            .header("X-API-VERSION", "1.0")
            .body(Map.of("key_type", "approval", "checksum", checksum, "timestamp", timestamp))
            .retrieve()
            .body(JsonNode.class);
        if (response == null || response.path("token").asText().isBlank()) {
            throw new IllegalStateException("Groww authentication did not return an access token.");
        }
        String token = response.path("token").asText();
        Instant expiresAt = parseExpiry(response.path("expiry").asText(null));
        cachedToken = new CachedToken(token, expiresAt);
        return token;
    }

    public Map<String, Object> status() {
        boolean configured = properties.isEnabled() && properties.hasCredentials();
        return Map.of(
            "enabled", properties.isEnabled(),
            "credentialsConfigured", properties.hasCredentials(),
            "state", !properties.isEnabled() ? "DISABLED" : configured ? "CONFIGURED" : "WAITING_FOR_CREDENTIALS",
            "authentication", properties.getAccessToken().isBlank() ? "API_KEY_DAILY_APPROVAL" : "ACCESS_TOKEN"
        );
    }

    private String checksum(String secret, String timestamp) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((secret + timestamp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private Instant parseExpiry(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(value).atZone(INDIA).toInstant();
                } catch (DateTimeParseException ignoredAgain) {
                    // Use the documented daily token boundary if Groww omits an ISO expiry.
                }
            }
        }
        LocalDate today = LocalDate.now(INDIA);
        LocalDateTime boundary = today.atTime(6, 0);
        if (!boundary.atZone(INDIA).toInstant().isAfter(Instant.now())) {
            boundary = today.plusDays(1).atTime(6, 0);
        }
        return boundary.atZone(INDIA).toInstant();
    }

    private record CachedToken(String value, Instant expiresAt) { }
}