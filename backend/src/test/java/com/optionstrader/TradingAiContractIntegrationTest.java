package com.optionstrader;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.tradingai.AnalysisRequestMapper;
import com.optionstrader.tradingai.TradingAiClient;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/** Runs only against the isolated synthetic API started by the verification tool. */
@EnabledIfEnvironmentVariable(named="TRADING_AI_CONTRACT_URL", matches="http://127\\.0\\.0\\.1:[0-9]+")
class TradingAiContractIntegrationTest {
    @Test
    void roundTripsJavaMappingPythonAnalysisAndSavedProviderContext() {
        ObjectMapper json = new ObjectMapper();
        String url = System.getenv("TRADING_AI_CONTRACT_URL");
        var client = new TradingAiClient(RestClient.builder().baseUrl(url).build(), url, true);
        var service = new AnalysisService(new AnalysisRequestMapper(), client, true, Duration.ofSeconds(10), 2);
        assertThat(service.status()).containsEntry("ready", true).containsEntry("marketDataSupported", true);
        var payload = json.createObjectNode();
        payload.put("symbol", "NSE-WIPRO"); payload.put("interval", "5 seconds");
        payload.put("analysis_prompt", "Synthetic integration verification");
        var bars = payload.putArray("bars");
        var context = payload.putObject("market_data");
        context.put("provider", "GROWW"); context.put("endpoint", "/v1/historical/candles");
        var request = context.putObject("request");
        request.put("exchange", "NSE"); request.put("segment", "FNO");
        request.put("groww_symbol", "NSE-WIPRO"); request.put("candle_interval", "5second");
        var original = context.putObject("response");
        original.put("status", "SUCCESS");
        var raw = original.putObject("payload").putArray("candles");
        for (int i=0; i<50; i++) {
            String stamp = Instant.parse("2026-01-05T03:45:00Z").plusSeconds(i*5L).toString();
            var bar = bars.addObject();
            bar.put("timestamp",stamp); bar.put("open",100+i); bar.put("high",103+i);
            bar.put("low",99+i); bar.put("close",101+i); bar.put("volume",1000+i);
            raw.addArray().add(stamp).add(100+i).add(103+i).add(99+i).add(101+i).add(1000+i).add(5500+i);
        }
        var result = service.analyze(payload);
        assertThat(result).containsEntry("trend","bullish").containsEntry("interval","5s")
            .containsEntry("tradeAuthorized",false).doesNotContainKey("market_data");
        JsonNode analysis = json.valueToTree(result);
        assertThat(analysis.at("/timeframe/bars_received").asInt()).isEqualTo(50);
        assertThat(analysis.at("/market_data_summary/raw_candle_count").asInt()).isEqualTo(50);
        assertThat(analysis.at("/market_data_summary/provider").asText()).isEqualTo("GROWW");
        long id = analysis.path("report_id").asLong();
        assertThat(id).isPositive();
        JsonNode saved = json.valueToTree(service.report(id));
        assertThat(saved.at("/response/market_data")).isEqualTo(context);
        assertThat(saved.at("/response/market_data/response/payload/candles/0/6").asInt()).isEqualTo(5500);
        JsonNode reports = json.valueToTree(service.reports());
        assertThat(reports.path("total").asInt()).isPositive();
        assertThat(reports.at("/reports/0/response/market_data").isMissingNode()).isTrue();
    }
}
