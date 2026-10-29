package com.optionstrader.groww;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.AnalysisService;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionAnalysisServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final GrowwApiClient broker=mock(GrowwApiClient.class);
    final GrowwMarketDataService market=new GrowwMarketDataService(broker,new GrowwApiProperties());
    final AnalysisService ai=mock(AnalysisService.class);
    final MutableClock clock=new MutableClock("2026-10-26T03:50:01Z");
    final SessionAnalysisService sessions=new SessionAnalysisService(market,ai,json,clock);

    ObjectNode settings() {
        return json.createObjectNode().put("exchange","NSE").put("segment","CASH")
            .put("groww_symbol","NSE-WIPRO").put("candle_interval","5minute");
    }
    ObjectNode candles(int count) {
        var root=json.createObjectNode().put("status","SUCCESS");
        var rows=root.putObject("payload").putArray("candles");
        for(int i=0;i<count;i++)rows.addArray().add("2026-10-26T09:"+(i==0?"15":"20")+":00")
            .add(100+i).add(103+i).add(99+i).add(101+i).add(1000+i);
        return root;
    }
    @Test void waitsBeforeTheFirstSessionCandleWithoutCallingBrokerOrAi() {
        clock.now=Instant.parse("2026-10-26T03:45:00Z");
        assertThat(sessions.poll(settings())).containsEntry("status","WAITING_FOR_SESSION")
            .containsEntry("tradeAuthorized",false);
        verifyNoInteractions(broker,ai);
    }
    @Test void emptyHistoryIsAWaitingStateAndDoesNotReachAi() {
        when(broker.getEnvelope(any(),any())).thenReturn(candles(0));
        var result=sessions.poll(settings());
        assertThat(result).containsEntry("status","WAITING_FOR_CANDLES");
        verifyNoInteractions(ai);
    }
    @Test void pollsAtFiveSecondsAndSavesOnlyNewOrCorrectedCompletedData() {
        var raw=candles(1);
        when(broker.getEnvelope(any(),any())).thenReturn(raw);
        when(ai.analyze(any())).thenReturn(Map.of("report_id",7,"data_sufficiency","partial"));
        var first=sessions.poll(settings());
        assertThat(first).containsEntry("reusedAnalysis",false).containsEntry("tradeAuthorized",false);
        sessions.poll(settings()); // same five-second budget across multiple browser polls
        verify(broker,times(1)).getEnvelope(any(),any());
        clock.now=clock.now.plusSeconds(5);
        assertThat(sessions.poll(settings())).containsEntry("reusedAnalysis",true);
        verify(ai,times(1)).analyze(any());
        ((com.fasterxml.jackson.databind.node.ArrayNode)raw.at("/payload/candles/0")).set(4,json.getNodeFactory().numberNode(102));
        clock.now=clock.now.plusSeconds(5);
        assertThat(sessions.poll(settings())).containsEntry("reusedAnalysis",false);
        verify(ai,times(2)).analyze(any());
        verify(broker,times(3)).getEnvelope(eq("/v1/historical/candles"),argThat(query->
            query.get("start_time").equals("2026-10-26 09:15:00") && query.get("groww_symbol").equals("NSE-WIPRO")));
    }
    @Test void ambiguousAiFailureIsNotAutomaticallyResubmittedForTheSameBars() {
        when(broker.getEnvelope(any(),any())).thenReturn(candles(1));
        when(ai.analyze(any())).thenReturn(Map.of("status","UNAVAILABLE","analysis","Timed out; check reports."));
        sessions.poll(settings());clock.now=clock.now.plusSeconds(5);
        assertThat(sessions.poll(settings())).containsEntry("status","UNAVAILABLE").containsEntry("reusedAnalysis",true);
        verify(ai,times(1)).analyze(any());
    }
    @Test void afterCloseTheRangeEndsAt1530IndiaTime() {
        clock.now=Instant.parse("2026-10-26T12:00:00Z");
        when(broker.getEnvelope(any(),any())).thenReturn(candles(0));
        sessions.poll(settings());
        verify(broker).getEnvelope(any(),argThat(query->query.get("end_time").equals("2026-10-26 15:30:00")));
    }
    static class MutableClock extends Clock {
        Instant now; MutableClock(String now){this.now=Instant.parse(now);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        public Instant instant(){return now;}
    }
}
