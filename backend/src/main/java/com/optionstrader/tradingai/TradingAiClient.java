package com.optionstrader.tradingai;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class TradingAiClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String baseUrl;
    private final boolean enabled;

    public TradingAiClient(
            @Qualifier("tradingAiRestClient") RestClient restClient,
            @Value("${trading.ai.analysis-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${trading.ai.enabled:true}") boolean enabled) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
    }

    public Map<String, Object> analyze(Map<String, Object> payload) {
        requireEnabled();
        Map<String, Object> response = restClient.post()
            .uri("/analyze")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(MAP_TYPE);
        return response == null ? Map.of() : response;
    }

    public Object health() {
        requireEnabled();
        return restClient.get()
            .uri("/health")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(Object.class);
    }

    public Object reports() {
        requireEnabled();
        return restClient.get()
            .uri(uriBuilder -> uriBuilder.path("/reports").queryParam("kind", "analysis").build())
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(Object.class);
    }

    public Object report(long reportId) {
        requireEnabled();
        return restClient.get()
            .uri("/reports/{report_id}", reportId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(Object.class);
    }

    public Object internalGet(String path) {
        requireEnabled();
        if(!path.startsWith("/api/v1/"))throw new IllegalArgumentException("Versioned internal path required.");
        return restClient.get().uri(path).header("X-Correlation-ID",correlation()).accept(MediaType.APPLICATION_JSON).retrieve().body(Object.class);
    }

    public Object internalPost(String path,Object body) { return internalPost(path,body,null); }
    public Object internalPost(String path,Object body,String key) {
        requireEnabled();
        if(!path.startsWith("/api/v1/"))throw new IllegalArgumentException("Versioned internal path required.");
        var request=restClient.post().uri(path).header("X-Correlation-ID",correlation()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body);
        if(key!=null&&!key.isBlank())request.header("Idempotency-Key",key);
        return request.retrieve().body(Object.class);
    }

    private String correlation() {
        var context=RequestContextHolder.getRequestAttributes();
        if(context instanceof ServletRequestAttributes servlet){
            Object id=servlet.getRequest().getAttribute("correlationId");
            if(id!=null)return id.toString();
        }
        return UUID.randomUUID().toString();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean enabled() {
        return enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Trading AI integration is disabled.");
        }
    }
}
