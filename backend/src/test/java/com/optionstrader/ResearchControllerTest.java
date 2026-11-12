package com.optionstrader;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.groww.*;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResearchControllerTest {
    final ObjectMapper json=new ObjectMapper();
    final TradingService trading=mock(TradingService.class);
    final AnalysisService analysis=mock(AnalysisService.class);
    final BacktestingService legacy=mock(BacktestingService.class);
    final GrowwMarketDataService market=spy(new GrowwMarketDataService(mock(GrowwApiClient.class),new GrowwApiProperties()));
    final ApiController controller=new ApiController(trading,analysis,legacy,mock(GrowwAuthService.class),market,json);
    com.fasterxml.jackson.databind.node.ObjectNode request(String segment){
        var body=json.createObjectNode();body.put("exchange","NSE");body.put("segment",segment);body.put("groww_symbol","NSE-WIPRO");
        body.put("start_time","2026-01-05 09:15:00");body.put("end_time","2026-01-05 15:30:00");body.put("candle_interval","5minute");
        body.putObject("analysis_options").put("mode","historical");body.putObject("replay_options").put("holding_bars",2);return body;
    }
    JsonNode candles(){
        var result=json.createObjectNode();result.put("status","SUCCESS");var rows=result.putObject("payload").putArray("candles");
        var start=LocalDateTime.parse("2026-01-05T09:15:00");for(int i=0;i<70;i++)rows.addArray().add(start.plusMinutes(i*5L).toString()).add(100+i).add(103+i).add(99+i).add(101+i).add(1000+i);
        return result;
    }
    @Test void forwardsFetchedCandlesAndResearchSettingsWithoutLegacyPaperExecution(){
        doReturn(candles()).when(market).fetchHistoricalCandles(any());
        when(analysis.replay(any())).thenReturn(Map.of("report_type","replay","tradeAuthorized",false));
        assertThat(controller.historicalReplay(request("CASH"))).containsEntry("report_type","replay");
        var captured=ArgumentCaptor.forClass(JsonNode.class);verify(analysis).replay(captured.capture());
        assertThat(captured.getValue().path("bars").size()).isEqualTo(70);
        assertThat(captured.getValue().at("/analysis_options/mode").asText()).isEqualTo("historical");
        assertThat(captured.getValue().at("/replay_options/holding_bars").asInt()).isEqualTo(2);
        verifyNoInteractions(trading,legacy);
    }
    @Test void unsupportedDerivativesReplayFailsBeforeAnyFetchOrExecution(){
        assertThatThrownBy(()->controller.historicalReplay(request("FNO"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FNO");
        verify(market,never()).fetchHistoricalCandles(any());verifyNoInteractions(analysis,trading,legacy);
    }
    @Test void legacySyntheticEntriesCannotBypassTheSinglePositionEngine(){
        assertThatThrownBy(controller::execute).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(trading,legacy,analysis);
    }
}
