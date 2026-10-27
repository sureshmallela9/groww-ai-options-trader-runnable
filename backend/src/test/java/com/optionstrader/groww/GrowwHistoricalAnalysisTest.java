package com.optionstrader.groww;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GrowwHistoricalAnalysisTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GrowwMarketDataService service =
        new GrowwMarketDataService(mock(GrowwApiClient.class), new GrowwApiProperties());

    @Test
    void preservesGrowwResponseAndCreatesSortedIstBarsForAi() throws Exception {
        JsonNode growwResponse = responseWithCandles(50);
        var request = new GrowwMarketDataService.HistoricalRequest(
            "NSE", "CASH", "NSE-WIPRO", "2025-09-24 09:15:00",
            "2025-09-24 15:30:00", "5minute", "Trend and risk", null);

        var input = service.buildAnalysisInput(request, growwResponse);

        assertThat(input.sourceCandleCount()).isEqualTo(50);
        assertThat(input.analysisCandleCount()).isEqualTo(50);
        assertThat(input.aiInterval()).isEqualTo("5m");
        assertThat(input.payload()).containsEntry("symbol", "NSE-WIPRO")
            .containsEntry("interval", "5m")
            .containsEntry("analysis_prompt", "Trend and risk");

        @SuppressWarnings("unchecked")
        var bars = (java.util.List<Map<String, Object>>) input.payload().get("bars");
        assertThat(bars).hasSize(50);
        assertThat(bars.get(0).get("timestamp")).isEqualTo("2025-09-24T09:15:00+05:30");
        assertThat(bars.get(0)).containsOnlyKeys("timestamp", "open", "high", "low", "close", "volume");

        @SuppressWarnings("unchecked")
        var marketData = (Map<String, Object>) input.payload().get("market_data");
        assertThat(marketData).containsEntry("provider", "GROWW")
            .containsEntry("endpoint", "/v1/historical/candles")
            .containsEntry("response", growwResponse);
        assertThat(mapper.valueToTree(input.payload()).path("market_data").path("response")
            .path("payload").path("candles").get(0).get(6).asInt()).isEqualTo(500);
    }

    @Test
    void validatesHistoricalRequestAndRejectsUnknownFields() throws Exception {
        JsonNode body = mapper.readTree("""
            {
              "exchange":"NSE",
              "segment":"FNO",
              "groww_symbol":"NSE-NIFTY-30Sep25-24650-CE",
              "start_time":"2025-09-24 09:15:00",
              "end_time":"2025-09-25 15:30:00",
              "candle_interval":"5minute"
            }
            """);
        var request = service.parseHistoricalRequest(body);
        assertThat(request.segment()).isEqualTo("FNO");
        assertThat(request.candleInterval()).isEqualTo("5minute");

        JsonNode invalid = mapper.readTree("""
            {
              "exchange":"NSE","segment":"CASH","groww_symbol":"NSE-WIPRO",
              "start_time":"2025-09-24 09:15:00","end_time":"2025-09-25 15:30:00",
              "candle_interval":"5minute","account_balance":100000
            }
            """);
        assertThatThrownBy(() -> service.parseHistoricalRequest(invalid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported historical request field");
    }

    @Test
    void rejectsHistoricalRangeBeyondGrowwsIntervalLimit() throws Exception {
        JsonNode body = mapper.readTree("""
            {
              "exchange":"NSE","segment":"CASH","groww_symbol":"NSE-WIPRO",
              "start_time":"2025-09-01 09:15:00","end_time":"2025-10-02 15:30:00",
              "candle_interval":"5minute"
            }
            """);

        assertThatThrownBy(() -> service.parseHistoricalRequest(body))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximum date range of 30 days");
    }
    @Test
    void rejectsLessThanFiftyCompleteCandles() throws Exception {
        JsonNode growwResponse = responseWithCandles(49);
        var request = new GrowwMarketDataService.HistoricalRequest(
            "NSE", "CASH", "NSE-WIPRO", "2025-09-24 09:15:00",
            "2025-09-24 15:30:00", "5minute", null, null);

        assertThatThrownBy(() -> service.buildAnalysisInput(request, growwResponse))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 50");
    }

    private JsonNode responseWithCandles(int count) {
        var root = mapper.createObjectNode();
        root.put("status", "SUCCESS");
        var payload = root.putObject("payload");
        ArrayNode candles = payload.putArray("candles");
        LocalDateTime start = LocalDateTime.parse("2025-09-24T09:15:00");
        for (int i = 0; i < count; i++) {
            ArrayNode candle = candles.addArray();
            candle.add(start.plusMinutes(i * 5L).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            candle.add(100 + i);
            candle.add(103 + i);
            candle.add(99 + i);
            candle.add(101 + i);
            candle.add(1000 + i);
            candle.add(500 + i);
        }
        payload.put("closing_price", 150.0);
        return root;
    }
}
