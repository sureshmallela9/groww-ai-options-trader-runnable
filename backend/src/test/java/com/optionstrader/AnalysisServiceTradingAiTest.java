package com.optionstrader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.tradingai.AnalysisRequestMapper;
import com.optionstrader.tradingai.TradingAiClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AnalysisServiceTradingAiTest {
    private static final String BASE_URL = "http://127.0.0.1:8000";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer server;
    private AnalysisService service;

    @AfterEach
    void verifyExpectedRequests() {
        if (server != null) server.verify();
    }

    @Test
    void forwardsValidatedBarsAndPreservesStructuredAnalysisResponse() throws Exception {
        setupClient();
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"symbol\":\"RELIANCE\",\"interval\":\"5s\"}", false))
            .andRespond(withSuccess("""
                {
                  "report_id": 42,
                  "analysis_prompt": "Mocked prompt",
                  "symbol": "RELIANCE",
                  "interval": "5s",
                  "trend": "mixed",
                  "summary": "Illustrative mocked response",
                  "timeframe": {"bars_received": 50},
                  "indicators": {"latest_close": 101.25},
                  "evidence": ["mock evidence"],
                  "positive_reasons": [],
                  "negative_reasons": [],
                  "risk_notes": ["mock risk"],
                  "knowledge_query": "mock query",
                  "knowledge_context": [{"source":"fixture.md","title":"Fixture","excerpt":"mock excerpt"}],
                  "knowledge_status": "mock status",
                  "limitations": ["mock only"]
                }
                """, MediaType.APPLICATION_JSON));

        var response = service.analyze(request(50));
        assertThat(response).containsEntry("report_id", 42);
        assertThat(response).containsEntry("trend", "mixed");
        assertThat(response).containsEntry("tradeAuthorized", false);
        assertThat(response).containsEntry("provider", "Local Trading AI API");
        assertThat(response.get("totalLatencyMs")).isInstanceOf(Number.class);
        assertThat(response).containsKey("timeframe").containsKey("indicators")
            .containsKey("knowledge_context").containsKey("limitations");
    }

    @Test
    void mapsHttp422IntoReadableValidationFailure() {
        setupClient();
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"bad candle\"}"));

        var response = service.analyze(request(50));
        assertThat(response).containsEntry("status", "INVALID_CANDLES");
        assertThat(response).containsEntry("tradeAuthorized", false);
    }

    @Test
    void retries503OnceAfterRetryAfterAndReturnsSuccess() {
        setupClient();
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "0")
                .body("busy"));
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andRespond(withSuccess("{\"report_id\":99,\"trend\":\"mixed\"}", MediaType.APPLICATION_JSON));

        var response = service.analyze(request(50));
        assertThat(response).containsEntry("report_id", 99);
        assertThat(response).containsEntry("tradeAuthorized", false);
    }

    @Test
    void returnsBusyAndRetryAfterAfterOneBoundedRetry() {
        setupClient();
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "0").body("busy"));
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "4").body("busy"));

        var response = service.analyze(request(50));
        assertThat(response).containsEntry("status", "BUSY");
        assertThat(response).containsEntry("retryAfter", "4");
        assertThat(response).containsEntry("tradeAuthorized", false);
    }

    @Test
    void handlesOfflineFailuresAndReadTimeoutWithoutThrowing() {
        setupClient();
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andRespond(withException(new IOException("connection refused")));
        server.expect(requestTo(BASE_URL + "/analyze"))
            .andRespond(withException(new SocketTimeoutException("read timed out")));

        var offline = service.analyze(request(50));
        assertThat(offline).containsEntry("status", "UNAVAILABLE");
        var timeout = service.analyze(request(50));
        assertThat(timeout).containsEntry("status", "UNAVAILABLE")
            .containsEntry("tradeAuthorized", false);
    }

    @Test
    void missingCandleHistoryDoesNotCallTradingAi() {
        setupClient();
        var response = service.analyze(null);
        assertThat(response).containsEntry("status", "CANDLES_REQUIRED");
        assertThat(response).containsEntry("tradeAuthorized", false);
    }

    @Test
    void proxiesCurrentHealthAndSavedReportSchemas() {
        setupClient();
        server.expect(requestTo(BASE_URL + "/health"))
            .andRespond(withSuccess("""
                {
                  "status":"ok",
                  "knowledge":{"index_ready":true,"chunk_count":8},
                  "report_storage":{"backend":"sqlite","connected":true},
                  "analysis_workers":{"workers":4,"ready_workers":4,"in_flight_limit":8}
                }
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/reports?kind=analysis"))
            .andRespond(withSuccess("{\"reports\":[{\"id\":42,\"kind\":\"analysis\",\"symbol\":\"RELIANCE\"}],\"total\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/reports/42"))
            .andRespond(withSuccess("{\"id\":42,\"kind\":\"analysis\",\"response\":{\"report_id\":42,\"trend\":\"mixed\"},\"citations\":[]}", MediaType.APPLICATION_JSON));

        var status = service.status();
        assertThat(status).containsEntry("connected", true)
            .containsEntry("ready", true)
            .containsEntry("candlesAvailable", false)
            .containsEntry("analysisWorkers", java.util.Map.of("workers", 4, "ready_workers", 4, "in_flight_limit", 8));
        Object reports = service.reports();
        assertThat(reports).isInstanceOf(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) reports).get("total")).isEqualTo(1);
        Object report = service.report(42);
        assertThat(((java.util.Map<?, ?>) report).get("id")).isEqualTo(42);
    }

    @Test
    void mapsMissingSavedReportTo404() {
        setupClient();
        server.expect(requestTo(BASE_URL + "/reports/999"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"detail\":\"not found\"}"));

        assertThatThrownBy(() -> service.report(999))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining("was not found");
    }

    private void setupClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        TradingAiClient client = new TradingAiClient(builder.build(), BASE_URL, true);
        service = new AnalysisService(new AnalysisRequestMapper(), client, true, Duration.ofSeconds(2), 12);
    }

    private ObjectNode request(int barCount) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("symbol", "RELIANCE");
        request.put("interval", "5 seconds");
        ArrayNode bars = request.putArray("bars");
        for (int i = 0; i < barCount; i++) {
            ObjectNode bar = bars.addObject();
            bar.put("timestamp", "2026-10-26T09:15:" + String.format("%02d", i % 60) + "+05:30");
            bar.put("open", 100 + i);
            bar.put("high", 103 + i);
            bar.put("low", 99 + i);
            bar.put("close", 101 + i);
            bar.put("volume", 1000 + i);
        }
        return request;
    }
}
