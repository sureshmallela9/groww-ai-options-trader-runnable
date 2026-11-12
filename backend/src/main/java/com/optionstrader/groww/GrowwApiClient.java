package com.optionstrader.groww;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GrowwApiClient {
    private final GrowwReadBudget readBudget = new GrowwReadBudget();
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
        readBudget.acquire(path);
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


    /** No automatic retries: execution service persists reference IDs before sending writes. */
    public static final class BrokerRejection extends IllegalStateException {
        public BrokerRejection(String code){super("Groww rejected the request ("+code+"). No execution was accepted.");}
    }
    public JsonNode post(String path,Object body) {
        if(!java.util.Set.of("/v1/order/create","/v1/order/modify","/v1/order/cancel","/v1/margins/detail/orders","/v1/margins/detail/orders?segment=CASH").contains(path))
            throw new IllegalArgumentException("Unsupported broker write route.");
        readBudget.acquire(path);
        JsonNode response;
        try{response=client.post().uri(path).header("Authorization","Bearer "+auth.accessToken())
            .header("Accept","application/json").header("X-API-VERSION","1.0")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        }catch(org.springframework.web.client.RestClientResponseException failure){
            int status=failure.getStatusCode().value();if(status==401||status==403)throw new BrokerRejection("HTTP_"+status);throw failure;
        }
        if(response!=null&&"FAILURE".equals(response.path("status").asText())&&java.util.Set.of("GA001","GA005","GA006").contains(response.path("error").path("code").asText()))
            throw new BrokerRejection(response.path("error").path("code").asText());
        if(response==null||!"SUCCESS".equals(response.path("status").asText()))throw new IllegalStateException("Broker request was not acknowledged; reconcile the original reference before retrying.");
        return response.path("payload");
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
