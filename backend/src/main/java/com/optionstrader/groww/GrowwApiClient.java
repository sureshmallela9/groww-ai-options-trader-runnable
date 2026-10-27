package com.optionstrader.groww;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GrowwApiClient {
    private final RestClient client;
    private final RestClient instrumentsClient;
    private final GrowwAuthService auth;

    public GrowwApiClient(
            @Qualifier("growwRestClient") RestClient client,
            @Qualifier("growwInstrumentsRestClient") RestClient instrumentsClient,
            GrowwAuthService auth) {
        this.client = client;
        this.instrumentsClient = instrumentsClient;
        this.auth = auth;
    }

    public JsonNode get(String path, Map<String, String> query) {
        return getEnvelope(path, query).path("payload");
    }

    public JsonNode getEnvelope(String path, Map<String, String> query) {
        JsonNode response = client.get()
            .uri(builder -> {
                var uri = builder.path(path);
                query.forEach(uri::queryParam);
                return uri.build();
            })
            .header("Authorization", "Bearer " + auth.accessToken())
            .header("Accept", "application/json")
            .header("X-API-VERSION", "1.0")
            .retrieve()
            .body(JsonNode.class);
        if (response == null || !"SUCCESS".equals(response.path("status").asText())) {
            throw new IllegalStateException("Groww returned an unsuccessful response.");
        }
        return response;
    }

    public String downloadInstrumentsCsv() {
        String response = instrumentsClient.get()
            .uri("/instruments/instrument.csv")
            .accept(org.springframework.http.MediaType.parseMediaType("text/csv"))
            .retrieve()
            .body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Groww did not return the instruments CSV.");
        }
        return response;
    }
}
