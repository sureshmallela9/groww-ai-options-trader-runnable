package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.TradingService;
import com.optionstrader.tradingai.TradingAiClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;

class AutoTradingServiceTest {
    final ObjectMapper json=new ObjectMapper();final Clock clock=Clock.fixed(Instant.parse("2026-10-28T05:50:00Z"),ZoneOffset.UTC);
    PaperPlatformService paper;TradingService dashboard;NormalizedMarketGateway gateway;SessionCalendar calendar;
    TradingAiClient ai;AutoTradingService auto;ObjectNode selection;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V2__paper_platform.sql")).execute(ds);
        paper=mock(PaperPlatformService.class);dashboard=mock(TradingService.class);gateway=mock(NormalizedMarketGateway.class);
        calendar=mock(SessionCalendar.class);ai=mock(TradingAiClient.class);
        when(dashboard.settings()).thenReturn(Map.of("stopAfterProfitableTrades",3));
        when(calendar.status(clock)).thenReturn(Map.of("verified",true,"entryAllowed",true));
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",PaperPlatformService.Policy.defaults(),"openPositions",List.of()));
        when(paper.portfolio()).thenReturn(Map.of("sessionDate","2026-10-28","profitableTrades",0,"netPnl",0,"positions",List.of()));
        when(paper.intents()).thenReturn(List.of());
        var universe=json.createObjectNode().put("retrievedAt",clock.instant().toString());
        for(int i=0;i<50;i++)universe.withArray("constituents").addObject().put("symbol","TEST"+i);
        when(ai.internalGet("/api/v1/universe")).thenReturn(universe);
        when(gateway.candles(anyString(),eq(100),eq(clock))).thenReturn(Map.of("bars",List.of(Map.of("timestamp","2026-10-28T05:45:00Z","close",100)),"receivedAt",clock.instant().toString()));
        when(gateway.quoteResponse(anyString(),eq(calendar),eq(clock))).thenReturn(Map.of("quote",Map.of("lastPrice",100)));
        selection=json.createObjectNode().put("status","CANDIDATE_SELECTED");
        selection.putObject("selected").put("symbol","TEST0").putObject("market");
        when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenReturn(selection);
        when(ai.internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString())).thenReturn(Map.of("action","BUY","decisionId","DEC-1234567890"));
        when(paper.submitAutomatic(any(),anyString())).thenReturn(json.createObjectNode().put("intentId","one").put("state","POSITION_OPEN"));
        auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,new JdbcTemplate(ds),new DataSourceTransactionManager(ds),clock,false);
    }
    @AfterEach void stop(){auto.shutdown();}
    void start(){auto.start(json.createObjectNode().put("mode","PAPER").put("confirmed",true));}
    @Test void oneCandidateIsSubmittedOncePerCandleAndStopPreventsMoreEntries(){
        start();auto.cycle();auto.cycle();
        verify(paper,times(1)).submitAutomatic(argThat(intent->intent.confirmed()&&"PAPER".equals(intent.mode())),startsWith("AUTO-"));
        auto.stop();auto.cycle();verify(paper,times(1)).submitAutomatic(any(),anyString());
        assertThat(auto.status().path("enabled").asBoolean()).isFalse();
    }
    @Test void realModeAndMissingConfirmationAreRejected(){
        assertThatThrownBy(()->auto.start(json.createObjectNode().put("mode","LIVE").put("confirmed",true)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->auto.start(json.createObjectNode().put("mode","PAPER"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ai,gateway);
    }
    @Test void unverifiedSessionAndOpenPositionBlockSelection(){
        start();when(calendar.status(clock)).thenReturn(Map.of("entryAllowed",false));auto.cycle();
        verifyNoInteractions(ai);verify(gateway).candles(eq("NIFTY"),eq(100),eq(clock));
        assertThat(auto.status().path("referenceSymbol").asText()).isEqualTo("NIFTY");
        verify(paper,never()).submitAutomatic(any(),anyString());
        when(calendar.status(clock)).thenReturn(Map.of("entryAllowed",true));
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",PaperPlatformService.Policy.defaults(),"openPositions",List.of(Map.of("state","EXIT_PENDING"))));
        auto.cycle();verifyNoInteractions(ai);verify(gateway,never()).quoteResponse(anyString(),any(),any());
    }
    @Test void noTradeAndStopDuringAnalysisNeverSubmitAnIntent(){
        start();when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenAnswer(call->{auto.stop();return selection;});
        auto.cycle();verify(paper,never()).submitAutomatic(any(),anyString());
        start();when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenReturn(Map.of("selected",json.nullNode(),"reasons",List.of("No setup")));
        auto.cycle();verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void failedFinalDecisionIsNotResubmittedAmbiguouslyWithinTheSameCandle(){
        start();when(ai.internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->auto.cycle()).isInstanceOf(IllegalStateException.class);
        auto.cycle();verify(ai,times(1)).internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString());
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void eventRiskActivatedDuringDecisionBlocksThePendingEntry(){
        start();
        when(ai.internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString())).thenAnswer(call->{
            auto.eventRisk(true);
            return Map.of("action","BUY","decisionId","DEC-1234567890");
        });
        auto.cycle();
        assertThat(auto.status().path("state").asText()).isEqualTo("EVENT_RISK");
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void aPendingExitDoesNotCreateASellMarker(){
        var row=json.createObjectNode().put("symbol","TEST0").put("filledQuantity",1).put("state","EXIT_PENDING")
            .put("entryTime",clock.instant().toString()).put("entryPrice",100);
        when(paper.intents()).thenReturn(List.of(row));
        assertThat(auto.chart("TEST0").path("markers")).hasSize(1);
        row.put("state","POSITION_CLOSED").put("exitTime",clock.instant().toString()).put("exitPrice",103);
        assertThat(auto.chart("TEST0").path("markers")).hasSize(2);
        assertThat(auto.chart("TEST0").at("/markers/1/side").asText()).isEqualTo("SELL");
    }
}
