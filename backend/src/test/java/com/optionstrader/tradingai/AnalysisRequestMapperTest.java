package com.optionstrader.tradingai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnalysisRequestMapperTest {
    @Test
    void acceptsOneCompletedCandleButRejectsEmptyHistory() {
        assertThat((java.util.List<?>)mapper.map(request("5m",1)).get("bars")).hasSize(1);
        assertThatThrownBy(()->mapper.map(request("5m",0))).hasMessageContaining("1-5,000");
    }
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisRequestMapper mapper = new AnalysisRequestMapper();

    @ParameterizedTest
    @CsvSource({
        "1s,1s", "5 seconds,5s", "5m,5m", "5 mins,5m",
        "1 hour,1h", "10 hours,10h", "3d,3d", "3 days,3d"
    })
    void normalizesSecondMinuteHourAndDayIntervals(String input, String expected) {
        assertThat(mapper.map(request(input, 50)).get("interval")).isEqualTo(expected);
    }

    @Test
    void acceptsFewerThanFiftyBars() {
        assertThat((java.util.List<?>)mapper.map(request("5m",49)).get("bars")).hasSize(49);
    }

    @Test
    void rejectsIntervalsLongerThanTwentyFourCharacters() {
        assertThatThrownBy(() -> mapper.map(request("1" + " ".repeat(24), 50)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1-24");
    }

    @Test
    void mapsAllowedFieldsAndPreservesCandleValues() {
        var mapped = mapper.map(request("5 minutes", 50));

        assertThat(mapped).containsOnlyKeys("symbol", "interval", "bars");
        assertThat(mapped.get("interval")).isEqualTo("5m");
        assertThat((java.util.List<?>) mapped.get("bars")).hasSize(50);
        @SuppressWarnings("unchecked")
        var first = (java.util.Map<String, Object>) ((java.util.List<?>) mapped.get("bars")).get(0);
        assertThat(first.get("close")).isEqualTo(101);
        assertThat(first).containsOnlyKeys("timestamp", "open", "high", "low", "close", "volume");
    }

    @Test
    void preservesGrowwMarketDataWhileStillValidatingBars() throws Exception {
        ObjectNode request = request("5m", 50);
        request.set("market_data", objectMapper.readTree("""
            {"provider":"GROWW","response":{"status":"SUCCESS","payload":{"candles":[[1,2,3,1,2,10,50]]}}}
            """));

        var mapped = mapper.map(request);

        assertThat(mapped).containsOnlyKeys("symbol", "interval", "bars", "market_data");
        assertThat(objectMapper.valueToTree(mapped).path("market_data").path("response")
            .path("payload").path("candles").get(0).get(6).asInt()).isEqualTo(50);
    }
    @Test
    void rejectsExtraTopLevelAndCandleFields() {
        ObjectNode extraRequest = request("5m", 50);
        extraRequest.put("account_balance", 123456);
        assertThatThrownBy(() -> mapper.map(extraRequest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported field 'account_balance'");

        ObjectNode extraCandle = request("5m", 50);
        ((ObjectNode) extraCandle.withArray("bars").get(3)).put("oi", 999);
        assertThatThrownBy(() -> mapper.map(extraCandle))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported field 'oi'");
    }

    @Test
    void rejectsDuplicateInstantsIncludingTimezoneFreeUtcValues() {
        ObjectNode duplicate = request("5m", 50);
        ArrayNode bars = (ArrayNode) duplicate.get("bars");
        ((ObjectNode) bars.get(0)).put("timestamp", "2026-10-26T03:45:00");
        ((ObjectNode) bars.get(1)).put("timestamp", "2026-10-26T03:45:00Z");

        assertThatThrownBy(() -> mapper.map(duplicate))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicates another candle timestamp");
    }

    @Test
    void rejectsIncompleteOrInconsistentCandleInsteadOfRepairingIt() {
        ObjectNode request = request("5m", 50);
        ((ObjectNode) ((ArrayNode) request.get("bars")).get(4)).remove("volume");

        assertThatThrownBy(() -> mapper.map(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("volume");

        ObjectNode badOhlc = request("5m", 50);
        ((ObjectNode) ((ArrayNode) badOhlc.get("bars")).get(4)).put("high", 1);
        assertThatThrownBy(() -> mapper.map(badOhlc))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inconsistent OHLC");
    }

    private ObjectNode request(String interval, int count) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("symbol", "RELIANCE");
        request.put("interval", interval);
        ArrayNode bars = request.putArray("bars");
        for (int i = 0; i < count; i++) {
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
