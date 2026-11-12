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
    final ObjectMapper json=new ObjectMapper();final MovingClock clock=new MovingClock();
    static class MovingClock extends Clock {
        Instant value=Instant.parse("2026-10-28T05:50:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return value;}void advance(long seconds){value=value.plusSeconds(seconds);}
    }
    PaperPlatformService paper;TradingService dashboard;NormalizedMarketGateway gateway;SessionCalendar calendar;
    TradingAiClient ai;AutoTradingService auto;ObjectNode selection;JdbcTemplate testDb;DataSourceTransactionManager testManager;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V2__paper_platform.sql")).execute(ds);
        paper=mock(PaperPlatformService.class);dashboard=mock(TradingService.class);gateway=mock(NormalizedMarketGateway.class);
        calendar=mock(SessionCalendar.class);ai=mock(TradingAiClient.class);
        when(dashboard.settings()).thenReturn(Map.of("stopAfterProfitableTrades",3));
        when(calendar.status(clock)).thenReturn(Map.of("verified",true,"entryAllowed",true));
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",PaperPlatformService.Policy.defaults(),"intraday",IntradayRules.defaults(),"openPositions",List.of()));
        when(paper.portfolio()).thenReturn(Map.of("sessionDate","2026-10-28","profitableTrades",0,"netPnl",0,"positions",List.of()));
        when(paper.intents()).thenReturn(List.of());when(paper.mode()).thenReturn("PAPER");
        var universe=json.createObjectNode().put("retrievedAt",clock.instant().toString());
        for(int i=0;i<50;i++)universe.withArray("constituents").addObject().put("symbol","TEST"+i);
        when(ai.internalGet("/api/v1/universe")).thenReturn(universe);
        var extended=universe.deepCopy();for(int i=0;i<450;i++)extended.withArray("constituents").addObject().put("symbol","OTHER"+i);
        when(ai.internalGet("/api/v1/universe/extended")).thenReturn(extended);
        var prices=new HashMap<String,java.math.BigDecimal>();for(int i=0;i<450;i++)prices.put("OTHER"+i,new java.math.BigDecimal("1000"));
        when(gateway.lastPrices(anyList())).thenReturn(prices);

        when(ai.internalPost(eq("/api/v1/automation/news-plan"),any())).thenReturn(Map.of("status","READY","planId","NEWSPLAN-aaaaaaaaaaaaaaaaaaaaaaaa","candidates",List.of(Map.of("symbol","TEST0"))));
        when(gateway.availableCandles(anyString(),eq(clock))).thenReturn(Map.of("bars",List.of(Map.of("timestamp","2026-10-28T05:45:00Z","close",100)),"receivedAt",clock.instant().toString()));
        when(gateway.quoteResponse(anyString(),eq(calendar),eq(clock))).thenReturn(Map.of("quote",Map.of("lastPrice",100)));
        when(gateway.intradayCandidateCheck(anyString(),anyString(),any(),eq(clock))).thenAnswer(call->json.createObjectNode().put("source","GROWW").put("exchange","NSE").put("product","MIS").put("side",(String)call.getArgument(1)).put("status","PASSED").put("checkedAt",clock.instant().toString()).put("validUntil",clock.instant().plusSeconds(60).toString()).put("referencePrice",100).put("requiredMargin",20).put("reason","Synthetic MIS preflight"));
        selection=json.createObjectNode().put("status","CANDIDATE_SELECTED");
        selection.putObject("selected").put("symbol","TEST0").put("side","BUY").putObject("market");
        selection.putArray("candidates").addObject().put("symbol","TEST0").put("side","BUY").put("latestPrice",100).put("score",90).put("technicalQualified",true).put("candidateStatus","PENDING_INTRADAY_CHECK").put("selectionSource","NEWS");
        when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenReturn(selection);
        when(ai.internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString())).thenReturn(Map.of("action","BUY","symbol","TEST0","decisionId","DEC-1234567890"));
        when(paper.submitAutomatic(any(),anyString())).thenReturn(json.createObjectNode().put("intentId","one").put("state","POSITION_OPEN"));
        testDb=new JdbcTemplate(ds);testManager=new DataSourceTransactionManager(ds);
        auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,false);
    }
    @AfterEach void stop(){auto.shutdown();}
    @Test void rejectedMisCannotReachFinalDecisionEvenIfAiReturnsSelected(){
        when(gateway.intradayCandidateCheck(anyString(),anyString(),any(),eq(clock))).thenReturn(json.createObjectNode().put("source","GROWW").put("exchange","NSE").put("product","MIS").put("side","BUY").put("status","REJECTED").put("checkedAt",clock.instant().toString()).put("validUntil",clock.instant().plusSeconds(60).toString()).put("referencePrice",100).put("reason","Synthetic broker restriction"));
        start();auto.cycle();
        verify(ai,never()).internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString());verify(paper,never()).submitAutomatic(any(),anyString());
        assertThat(auto.status().path("state").asText()).isEqualTo("NO_TRADE");assertThat(auto.status().path("selection").path("reasons").toString()).contains("MIS preflight");
    }
    @Test void buyPreflightCannotBeUsedForAnAiSelectedShort(){
        selection.withObject("selected").put("side","SELL");start();auto.cycle();
        verify(ai,never()).internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString());verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void successfulNeutralNewsUsesExplicitPriceFallbackAndPreservesSelectionBinding(){
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",PaperPlatformService.Policy.defaults(),"intraday",IntradayRules.paperDefaults(),"openPositions",List.of()));
        var neutral=json.createObjectNode().put("status","NO_DIRECTIONAL_NEWS").put("planId","NEWSPLAN-aaaaaaaaaaaaaaaaaaaaaaaa")
            .put("createdAt",clock.instant().toString()).put("lastCollectionAt",clock.instant().toString()).put("validUntil",clock.instant().plusSeconds(300).toString()).put("configuredFeedCount",1);
        neutral.putArray("candidates");neutral.putArray("providerErrors");
        when(ai.internalPost(eq("/api/v1/automation/news-plan"),any())).thenReturn(neutral);
        selection.put("selectionId","SEL-aaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("selectionMode","PRICE_FALLBACK");
        selection.putArray("shortlist").addObject().put("symbol","TEST0").put("side","BUY").put("selectionSource","PRICE_FALLBACK");
        start();auto.cycle();
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai,times(2)).internalPost(eq("/api/v1/automation/select"),capture.capture());
        var request=json.valueToTree(capture.getValue());assertThat(request.path("selectionMode").asText()).isEqualTo("NEWS_THEN_PRICE");
        assertThat(request.at("/policy/paperLeverage").asInt()).isEqualTo(5);assertThat(request.at("/policy/maxPositionValue").asInt()).isEqualTo(50000);
        assertThat(request.at("/policy/capitalPriceFilterEnabled").asBoolean()).isTrue();
        assertThat(request.at("/policy/capitalPriceBands/0/maxSharePrice").asInt()).isEqualTo(200);
        assertThat(request.at("/policy/capital").asInt()).isEqualTo(10000);
        var finalCapture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai).internalPost(eq("/api/v1/decisions/evaluate"),finalCapture.capture(),anyString());
        assertThat(json.valueToTree(finalCapture.getValue()).path("selectionId").asText()).isEqualTo("SEL-aaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(json.valueToTree(finalCapture.getValue()).at("/policy/capitalPriceBands")).isEqualTo(request.at("/policy/capitalPriceBands"));
        assertThat(auto.shortlist().at("/stocks/0/selectionSource").asText()).isEqualTo("PRICE_FALLBACK");
        verify(paper).submitAutomatic(any(),anyString());
    }
    @Test void failedNewsOrADirectionalShortlistNeverTriggersPriceFallback(){
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",PaperPlatformService.Policy.defaults(),"intraday",IntradayRules.paperDefaults(),"openPositions",List.of()));
        var failed=json.createObjectNode().put("status","NEWS_UNAVAILABLE").put("configuredFeedCount",1).put("planId","NEWSPLAN-aaaaaaaaaaaaaaaaaaaaaaaa")
            .put("createdAt",clock.instant().toString()).put("lastCollectionAt",clock.instant().toString()).put("validUntil",clock.instant().plusSeconds(300).toString());
        failed.putArray("candidates");failed.putArray("providerErrors").addObject().put("error","Timeout");
        when(ai.internalPost(eq("/api/v1/automation/news-plan"),any())).thenReturn(failed);start();auto.cycle();
        assertThat(auto.status().path("state").asText()).isEqualTo("NEWS_WAIT");
        verify(ai,never()).internalPost(eq("/api/v1/automation/select"),any());verify(paper,never()).submitAutomatic(any(),anyString());
        auto.stop();when(ai.internalPost(eq("/api/v1/automation/news-plan"),any())).thenReturn(Map.of("status","READY","planId","NEWSPLAN-aaaaaaaaaaaaaaaaaaaaaaaa","candidates",List.of(Map.of("symbol","TEST0","side","BUY"))));
        start();auto.cycle();
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai).internalPost(eq("/api/v1/automation/select"),capture.capture());
        assertThat(json.valueToTree(capture.getValue()).path("selectionMode").asText()).isEqualTo("NEWS");
    }
    @Test void automationUsesPercentageBudgetAbove250InBothModes(){
        for(String mode:List.of("PAPER","REAL")){
            auto.stop();
            when(paper.mode()).thenReturn(mode);when(paper.liveEnabled()).thenReturn("REAL".equals(mode));
            var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
            policy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",2).put("dailyLossAmount",250);
            when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",policy,"intraday",IntradayRules.defaults(),"openPositions",List.of()));
            when(paper.portfolio()).thenReturn(Map.of("sessionDate","2026-10-28","profitableTrades",0,"netPnl",-300,"positions",List.of()));
            auto.start(json.createObjectNode().put("mode",mode).put("confirmed",true));auto.cycle();
            assertThat(auto.status().path("state").asText()).isEqualTo("POSITION_OPEN");
            clock.advance(5);
        }
        verify(paper,times(2)).submitAutomatic(any(),anyString());
    }
    @Test void automationBlocksAtThePercentageThresholdInBothModes(){
        for(String mode:List.of("PAPER","REAL")){
            auto.stop();when(paper.mode()).thenReturn(mode);when(paper.liveEnabled()).thenReturn("REAL".equals(mode));
            var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
            policy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",2);
            when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",policy,"intraday",IntradayRules.defaults(),"openPositions",List.of()));
            when(paper.portfolio()).thenReturn(Map.of("sessionDate","2026-10-28","profitableTrades",0,"netPnl",-2000,"positions",List.of()));
            auto.start(json.createObjectNode().put("mode",mode).put("confirmed",true));auto.cycle();
            assertThat(auto.status().path("state").asText()).isEqualTo("DAILY_LIMIT");
        }
        verify(paper,never()).submitAutomatic(any(),anyString());
        verify(ai,never()).internalPost(eq("/api/v1/automation/select"),any());
    }
    @Test void automationUsesModeSpecificLeveragedDailyBudget(){
        for(String mode:List.of("PAPER","REAL")){
            auto.stop();when(paper.mode()).thenReturn(mode);when(paper.liveEnabled()).thenReturn("REAL".equals(mode));
            var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
            policy.put("capital",1800).put("maxPositionValue",1800).put("dailyLossPercent",2);
            var rules=new IntradayRules(false,new java.math.BigDecimal("500"),new java.math.BigDecimal("5"),false,List.of(),new java.math.BigDecimal("3"));
            when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",policy,"intraday",rules,"openPositions",List.of()));
            when(paper.portfolio()).thenReturn(Map.of("sessionDate","2026-10-28","profitableTrades",0,"netPnl",-100,"positions",List.of()));
            auto.start(json.createObjectNode().put("mode",mode).put("confirmed",true));auto.cycle();
            assertThat(auto.status().path("state").asText()).isEqualTo("POSITION_OPEN");
            clock.advance(5);auto.stop();
            int threshold="REAL".equals(mode)?108:180;
            when(paper.portfolio()).thenReturn(Map.of("sessionDate","2026-10-28","profitableTrades",0,"netPnl",-threshold,"positions",List.of()));
            auto.start(json.createObjectNode().put("mode",mode).put("confirmed",true));auto.cycle();
            assertThat(auto.status().path("state").asText()).isEqualTo("DAILY_LIMIT");
            clock.advance(5);
        }
        verify(paper,times(2)).submitAutomatic(any(),anyString());
    }
    @Test void automationUsesAuthoritativeServerLossBudget(){
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());policy.put("capital",1800).put("maxPositionValue",1800).put("dailyLossPercent",2);
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",policy,"intraday",IntradayRules.paperDefaults(),"openPositions",List.of(),"dailyLossLimit",50));
        when(paper.portfolio()).thenReturn(Map.of("sessionDate","2026-10-28","profitableTrades",0,"netPnl",-50,"positions",List.of()));
        start();auto.cycle();assertThat(auto.status().path("state").asText()).isEqualTo("DAILY_LIMIT");
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void brokerProtectionFailureStopsNewEntriesUntilAnExplicitNewStart(){
        start();
        when(paper.intents()).thenReturn(List.of(json.createObjectNode().put("protectionFailureAt",clock.instant().toString()).put("state","EXIT_PENDING")));
        auto.cycle();assertThat(auto.status().path("state").asText()).isEqualTo("BROKER_PROTECTION_FAILURE");
        assertThat(auto.status().path("enabled").asBoolean()).isFalse();verify(paper,never()).submitAutomatic(any(),anyString());
        clock.advance(10);auto.start(json.createObjectNode().put("mode","PAPER").put("confirmed",true));auto.cycle();
        verify(paper).submitAutomatic(any(),anyString());
    }
    void start(){auto.start(json.createObjectNode().put("mode","PAPER").put("confirmed",true));}
    @Test void oneCandidateIsSubmittedOncePerFiveSecondCycleAndStopPreventsMoreEntries(){
        start();auto.cycle();auto.cycle();
        verify(paper,times(1)).submitAutomatic(argThat(intent->intent.confirmed()&&"PAPER".equals(intent.mode())),startsWith("AUTO-"));
        auto.stop();auto.cycle();verify(paper,times(1)).submitAutomatic(any(),anyString());
        assertThat(auto.status().path("enabled").asBoolean()).isFalse();
    }
    @Test void fullHistoryIsSharedAgainAfterFiveSecondsWhileContextIsCached(){
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<750;i++)rows.add(Map.of("timestamp",clock.instant().minusSeconds((751-i)*300L).toString(),"open",100,"high",101,"low",99,"close",100,"volume",1000));
        when(gateway.availableCandles(anyString(),eq(clock))).thenAnswer(call->Map.of("symbol",call.getArgument(0),"bars",rows,"receivedAt",clock.instant().toString(),"historyWindowDays",14));
        when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenReturn(Map.of("status","NO_TRADE","candidates",List.of(Map.of("symbol","TEST0","candleCount",750,"analysis",Map.of("trend","bullish"),"candidateStatus","NO_TRADE"))));
        start();auto.cycle();clock.advance(6);auto.cycle();
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai,times(2)).internalPost(eq("/api/v1/automation/select"),capture.capture());
        for(var sent:capture.getAllValues()){
            var body=json.valueToTree(sent);assertThat(body.path("niftyBars").size()).isEqualTo(750);
            assertThat(body.path("snapshots").get(0).path("bars").size()).isEqualTo(750);
        }
        verify(gateway,times(1)).availableCandles(eq("NIFTY"),eq(clock));
        verify(gateway,times(1)).availableCandles(eq("TEST1"),eq(clock));
        verify(gateway,times(2)).availableCandles(eq("TEST0"),eq(clock));
        var view=auto.shortlist();assertThat(view.path("stocks").size()).isEqualTo(1);
        assertThat(view.path("stocks").get(0).path("bars").size()).isEqualTo(750);
        assertThat(view.path("stocks").get(0).path("aiCandleCount").asInt()).isEqualTo(750);
        clearInvocations(gateway,ai);auto.shortlist();verify(gateway).marketDepth(eq("TEST0"),eq(clock),eq(calendar),eq(false));verifyNoMoreInteractions(gateway);verifyNoInteractions(ai);
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void heldPositionSharesFullCandlesWithoutSelectingOrOpeningAnotherStock(){
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",PaperPlatformService.Policy.defaults(),"openPositions",List.of(Map.of("symbol","TEST0","state","POSITION_OPEN","positionSide","LONG"))));
        when(ai.internalPost(eq("/api/v1/analysis/stock"),any())).thenReturn(Map.of("analysis",Map.of("trend","bullish")));
        start();auto.cycle();clock.advance(6);auto.cycle();
        verify(ai,times(2)).internalPost(eq("/api/v1/analysis/stock"),any());
        verify(ai,never()).internalPost(eq("/api/v1/automation/select"),any());
        verify(paper,never()).submitAutomatic(any(),anyString());
        assertThat(auto.shortlist().path("stocks").get(0).path("entryCheckStatus").asText()).isEqualTo("POSITION_HELD");
    }
    @Test void fullCandlesReachFinalAiButAreNotDuplicatedInTraderStatusOrAudit(){
        var market=(ObjectNode)selection.at("/selected/market");
        var rows=market.putArray("niftyBars");for(int i=0;i<750;i++)rows.addObject().put("timestamp",clock.instant().minusSeconds(i*300L).toString()).put("close",100);
        market.set("sectorBars",rows.deepCopy());
        start();auto.cycle();
        assertThat(auto.status().at("/selection/selected/market/niftyBars").isMissingNode()).isTrue();
        assertThat(testDb.queryForObject("SELECT MAX(LENGTH(PAYLOAD_JSON)) FROM PLATFORM_AUDIT",Integer.class)).isLessThan(8000);
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai).internalPost(eq("/api/v1/decisions/evaluate"),capture.capture(),anyString());
        assertThat(json.valueToTree(capture.getValue()).at("/market/niftyBars").size()).isEqualTo(750);
    }
    @Test void realModeAndMissingConfirmationAreRejected(){
        assertThatThrownBy(()->auto.start(json.createObjectNode().put("mode","LIVE").put("confirmed",true)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->auto.start(json.createObjectNode().put("mode","PAPER"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ai,gateway);
    }
    @Test void unverifiedSessionAndOpenPositionBlockSelection(){
        start();when(calendar.status(clock)).thenReturn(Map.of("entryAllowed",false));auto.cycle();
        verifyNoInteractions(ai);verify(gateway).availableCandles(eq("NIFTY"),eq(clock));
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

    @Test void blockedActivityNeverClaimsToBeAnalyzing(){
        auto.start(json.createObjectNode().put("mode","PAPER").put("confirmed",true).put("scheduledEventRisk",true));
        auto.cycle();
        var status=auto.status();
        assertThat(status.at("/activity/status").asText()).isEqualTo("BLOCKED");
        assertThat(status.at("/activity/phase").asText()).isEqualTo("EVENT_RISK");
        assertThat(status.at("/activity/processing").asBoolean()).isFalse();
        assertThat(status.at("/activity/lastCheckedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(status.at("/activity/recentStages")).hasSize(2);
        verifyNoInteractions(ai,gateway);verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void completedNoTradeScanReportsItsResultAndNextCandleWithoutReprocessing(){
        start();when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenReturn(Map.of("selected",json.nullNode(),"reasons",List.of("No setup")));
        auto.cycle();auto.cycle();
        var activity=auto.status().path("activity");
        assertThat(activity.path("status").asText()).isEqualTo("WAITING");
        assertThat(activity.path("processing").asBoolean()).isFalse();
        assertThat(activity.path("lastSelectionAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(activity.path("lastScanSummary").asText()).contains("No qualifying stock", "No setup");
        assertThat(activity.path("nextScanEligibleAt").asText()).isEqualTo("2026-10-28T05:50:05Z");
        assertThat(activity.has("lastDecisionAt")).isFalse();
        verify(ai,times(1)).internalPost(eq("/api/v1/automation/select"),any());
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void schedulerExposesRealHistoryProgressAndAiWorkThenStopsProcessing() throws Exception {
        auto.shutdown();auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,true);
        var historyStarted=new java.util.concurrent.CountDownLatch(1);var continueHistory=new java.util.concurrent.CountDownLatch(1);
        var analysisStarted=new java.util.concurrent.CountDownLatch(1);var continueAnalysis=new java.util.concurrent.CountDownLatch(1);
        when(gateway.availableCandles(eq("TEST10"),eq(clock))).thenAnswer(call->{
            historyStarted.countDown();if(!continueHistory.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test timeout");
            return Map.of("bars",List.of(Map.of("close",100)));
        });
        when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenAnswer(call->{
            analysisStarted.countDown();if(!continueAnalysis.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test timeout");
            return Map.of("selected",json.nullNode(),"reasons",List.of("No setup"));
        });
        try{
            start();assertThat(historyStarted.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var activity=auto.status().path("activity");
            assertThat(activity.path("processing").asBoolean()).isTrue();
            assertThat(activity.path("phase").asText()).isEqualTo("VERIFYING_DATA");
            assertThat(activity.path("completedHistories").asInt()).isEqualTo(11);
            assertThat(activity.path("totalHistories").asInt()).isEqualTo(51);
            assertThat(activity.path("currentSymbol").asText()).isEqualTo("TEST10");
            continueHistory.countDown();assertThat(analysisStarted.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            activity=auto.status().path("activity");
            assertThat(activity.path("phase").asText()).isEqualTo("ANALYZING");
            assertThat(activity.path("completedHistories").asInt()).isEqualTo(51);
            assertThat(activity.path("processing").asBoolean()).isTrue();
            continueAnalysis.countDown();awaitIdle();
            assertThat(auto.status().at("/activity/status").asText()).isEqualTo("WAITING");
            assertThat(auto.status().at("/activity/processing").asBoolean()).isFalse();
            verify(paper,never()).submitAutomatic(any(),anyString());
        }finally{continueHistory.countDown();continueAnalysis.countDown();}
    }
    @Test void workerFailureShowsRetryAndNeverAWorkingIndicator() throws Exception {
        auto.shutdown();auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,true);
        when(ai.internalGet("/api/v1/universe")).thenThrow(new IllegalStateException("unavailable"));
        start();awaitIdle();var activity=auto.status().path("activity");
        assertThat(activity.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(activity.path("processing").asBoolean()).isFalse();
        assertThat(activity.path("retryAt").asText()).isEqualTo("2026-10-28T05:50:30Z");
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void persistedProcessingFromAnEarlierProcessIsDisplayedAsPaused(){
        testDb.update("INSERT INTO PLATFORM_STATE(STATE_ID,PAYLOAD_JSON) VALUES('AUTO',?)",
            "{\"enabled\":true,\"owner\":\"old-process\",\"state\":\"ANALYZING\"}");
        var status=auto.status();
        assertThat(status.path("enabled").asBoolean()).isFalse();
        assertThat(status.at("/activity/status").asText()).isEqualTo("PAUSED");
        assertThat(status.at("/activity/processing").asBoolean()).isFalse();
    }
    @Test void clearingManualPauseImmediatelyReportsWaitingForTheNextCheckThenAnalysisResumes() {
        auto.start(json.createObjectNode().put("mode","PAPER").put("confirmed",true).put("scheduledEventRisk",true));
        auto.cycle();
        assertThat(auto.status().at("/activity/blockSource").asText()).isEqualTo("MANUAL_SETTING");
        verifyNoInteractions(ai,gateway);
        var cleared=auto.eventRisk(false);
        assertThat(cleared.path("scheduledEventRisk").asBoolean()).isFalse();
        assertThat(cleared.path("state").asText()).isEqualTo("CHECKING_RULES");
        assertThat(cleared.at("/activity/processing").asBoolean()).isFalse();
        auto.cycle();verify(ai).internalPost(eq("/api/v1/automation/select"),any());
    }
    @Test void reapplyingAutoModeWithoutAnEventSettingPreservesTheExistingManualPause() {
        auto.start(json.createObjectNode().put("mode","PAPER").put("confirmed",true).put("scheduledEventRisk",true));
        auto.start(json.createObjectNode().put("mode","PAPER").put("confirmed",true));auto.cycle();
        assertThat(auto.status().path("scheduledEventRisk").asBoolean()).isTrue();verifyNoInteractions(ai,gateway);
    }
    @Test void failedNiftyValidationNamesTheSymbolAndCauseInTheStatus() throws Exception {
        auto.shutdown();auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,true);
        when(gateway.availableCandles(eq("NIFTY"),eq(clock))).thenThrow(new IllegalArgumentException("No completed, valid candles are available (Groww returned 753 raw rows)."));
        start();awaitIdle();var status=auto.status();
        assertThat(status.path("message").asText()).contains("NIFTY candle loading", "753 raw rows");
        assertThat(status.at("/activity/failure/symbol").asText()).isEqualTo("NIFTY");
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void threeNewsSymbolsAreSentExplicitlyAndOnlyTheSelectedDirectionReachesExecution(){
        when(ai.internalPost(eq("/api/v1/automation/news-plan"),any())).thenReturn(Map.of("status","READY","planId","NEWSPLAN-aaaaaaaaaaaaaaaaaaaaaaaa","candidates",List.of(Map.of("symbol","TEST0","side","SELL"),Map.of("symbol","TEST1"),Map.of("symbol","TEST2"),Map.of("symbol","TEST3"))));
        selection.withObject("selected").put("side","SELL");
        ((ObjectNode)selection.path("candidates").get(0)).put("side","SELL");
        when(ai.internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString())).thenReturn(Map.of("action","SELL","symbol","TEST0","decisionId","DEC-1234567890"));
        start();auto.cycle();
        verify(ai).internalPost(eq("/api/v1/automation/select"),argThat(body->{var request=json.valueToTree(body);return request.path("candidateSymbols").size()==3&&request.path("snapshots").size()==50;}));
        verify(ai).internalPost(eq("/api/v1/decisions/evaluate"),argThat(body->"SELL".equals(json.valueToTree(body).path("side").asText())),anyString());
        verify(paper).submitAutomatic(any(),anyString());
    }
    @Test void finalAiCannotSwitchAwayFromTheSelectedStock(){
        when(ai.internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString())).thenReturn(Map.of("action","BUY","symbol","OTHER","decisionId","DEC-1234567890"));
        start();auto.cycle();assertThat(auto.status().path("state").asText()).isEqualTo("REJECTED");
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void otherStockFallbackCarriesItsBoundSelectionAndSourceToFinalAi(){
        when(paper.status()).thenReturn(Map.of("killSwitch",false,"policy",PaperPlatformService.Policy.defaults(),"intraday",IntradayRules.paperDefaults(),"openPositions",List.of()));
        var plan=json.createObjectNode().put("status","READY").put("planId","NEWSPLAN-aaaaaaaaaaaaaaaaaaaaaaaa").put("lastCollectionAt",clock.instant().toString()).put("validUntil",clock.instant().plusSeconds(300).toString()).put("configuredFeedCount",1);
        plan.putArray("providerErrors");plan.putArray("candidates").addObject().put("symbol","TEST0").put("side","BUY");
        when(ai.internalPost(eq("/api/v1/automation/news-plan"),any())).thenReturn(plan);
        when(gateway.lastPrices(anyList())).thenReturn(Map.of("OTHER0",new java.math.BigDecimal("100")));
        selection.put("selectionId","SEL-aaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("selectionMode","NEWS_THEN_PRICE");
        selection.withObject("selected").put("symbol","OTHER0").put("side","SELL").put("universeScope","OTHER_STOCKS").put("selectionSource","PRICE_FALLBACK");
        ((ObjectNode)selection.path("candidates").get(0)).put("symbol","OTHER0").put("side","SELL").put("selectionSource","PRICE_FALLBACK");
        selection.putArray("shortlist").addObject().put("symbol","OTHER0").put("side","SELL").put("selectionSource","PRICE_FALLBACK");
        when(ai.internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString())).thenReturn(Map.of("action","SELL","symbol","OTHER0","decisionId","DEC-1234567890"));
        start();auto.cycle();
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai,times(2)).internalPost(eq("/api/v1/automation/select"),capture.capture());
        var request=json.valueToTree(capture.getValue());assertThat(request.path("snapshots")).hasSize(50);assertThat(request.path("candidateSnapshots")).hasSize(1);assertThat(request.at("/candidateSnapshots/0/symbol").asText()).isEqualTo("OTHER0");
        var finalCapture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai).internalPost(eq("/api/v1/decisions/evaluate"),finalCapture.capture(),anyString());
        var body=json.valueToTree(finalCapture.getValue());assertThat(body.path("selectionMode").asText()).isEqualTo("PRICE_FALLBACK");assertThat(body.path("selectionId").asText()).isEqualTo("SEL-aaaaaaaaaaaaaaaaaaaaaaaaaaaa");assertThat(body.path("symbol").asText()).isEqualTo("OTHER0");assertThat(body.path("side").asText()).isEqualTo("SELL");
        verify(paper,times(1)).submitAutomatic(any(),anyString());
    }
    @Test void twoUniverseLanesOverlapButOnlyOneSelectionCycleCanProceed()throws Exception{
        auto.shutdown();auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,true);
        when(gateway.lastPrices(anyList())).thenReturn(Map.of("OTHER0",new java.math.BigDecimal("100")));
        var bothStarted=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        var threads=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        when(gateway.availableCandles(anyString(),eq(clock))).thenAnswer(call->{
            String symbol=call.getArgument(0);if("NIFTY".equals(symbol))return Map.of("bars",List.of(Map.of("close",100)));
            threads.add(Thread.currentThread().getName());int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);
            try{bothStarted.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test timeout");
                return Map.of("bars",List.of(Map.of("close",100)));}finally{active.decrementAndGet();}
        });
        try{
            start();assertThat(bothStarted.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(auto.status().at("/activity/historyWorkers").asInt()).isEqualTo(2);
            assertThat(auto.status().at("/activity/activeHistoryWorkers").asInt()).isEqualTo(2);
            assertThat(auto.status().at("/flow/maxConcurrentSelectionCycles").asInt()).isEqualTo(1);
            auto.tick();auto.tick();auto.tick();verify(ai,times(1)).internalGet("/api/v1/universe");
            verify(ai,never()).internalPost(eq("/api/v1/automation/select"),any());
            release.countDown();awaitIdle();
            assertThat(peak.get()).isEqualTo(2);assertThat(threads).containsExactlyInAnyOrder("automatic-nifty50-history","automatic-other-stock-history");
            var capture=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai,times(1)).internalPost(eq("/api/v1/automation/select"),capture.capture());
            var snapshots=json.valueToTree(capture.getValue()).path("snapshots");assertThat(snapshots).hasSize(50);
            for(int i=0;i<50;i++)assertThat(snapshots.get(i).path("symbol").asText()).isEqualTo("TEST"+i);
            verify(paper,times(1)).submitAutomatic(any(),anyString());
        }finally{release.countDown();}
    }
    @Test void aFailedParallelHistoryNamesItsActualSymbolAndPreventsIncompleteAiRequests()throws Exception{
        auto.shutdown();auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,true);
        when(gateway.availableCandles(eq("TEST1"),eq(clock))).thenThrow(new IllegalArgumentException("No completed, valid candles are available (Groww returned 0 raw rows)."));
        start();awaitIdle();var status=auto.status();
        assertThat(status.path("state").asText()).isEqualTo("DATA_UNAVAILABLE");
        assertThat(status.path("message").asText()).contains("TEST1 candle loading","0 raw rows");
        assertThat(status.at("/activity/lastCheckedAt").asText()).isEqualTo(clock.instant().toString());
        verify(ai,never()).internalPost(eq("/api/v1/automation/select"),any());
        verify(ai,never()).internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString());
        verify(paper,never()).submitAutomatic(any(),anyString());
    }
    @Test void stoppedRunCannotPublishParallelHistoriesOrSubmitAnEntryAfterRestart()throws Exception{
        auto.shutdown();auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,true);
        when(gateway.lastPrices(anyList())).thenReturn(Map.of("OTHER0",new java.math.BigDecimal("100")));
        var bothStarted=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        when(gateway.availableCandles(anyString(),eq(clock))).thenAnswer(call->{
            String symbol=call.getArgument(0);if(!"NIFTY".equals(symbol)){bothStarted.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
            return Map.of("bars",List.of(Map.of("close",100)));
        });
        try{
            start();assertThat(bothStarted.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String oldRun=auto.status().path("runId").asText();auto.stop();start();
            assertThat(auto.status().path("runId").asText()).isNotEqualTo(oldRun);
            release.countDown();awaitIdle();
            verify(ai,never()).internalPost(eq("/api/v1/automation/select"),any());verify(paper,never()).submitAutomatic(any(),anyString());
            clock.advance(5);auto.tick();awaitIdle();verify(ai,times(1)).internalPost(eq("/api/v1/automation/select"),any());verify(paper,times(1)).submitAutomatic(any(),anyString());
        }finally{release.countDown();}
    }
    @Test void oldAiResponseCannotAuthorizeAnEntryInANewAutomationRun()throws Exception{
        auto.shutdown();auto=new AutoTradingService(paper,dashboard,gateway,calendar,ai,json,testDb,testManager,clock,true);
        var reachedAi=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(ai.internalPost(eq("/api/v1/automation/select"),any())).thenAnswer(call->{reachedAi.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test timeout");return selection;});
        try{
            start();assertThat(reachedAi.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();auto.stop();start();release.countDown();awaitIdle();
            verify(ai,never()).internalPost(eq("/api/v1/decisions/evaluate"),any(),anyString());verify(paper,never()).submitAutomatic(any(),anyString());
            assertThat(auto.status().has("lastSelectionAt")).isFalse();
        }finally{release.countDown();}
    }
    @Test void historyWorkersAreClosedWithTheCoordinator(){
        auto.shutdown();
        var lanes=(DualUniverseHistories)org.springframework.test.util.ReflectionTestUtils.getField(auto,"universeHistories");
        assertThat(lanes.closed()).isTrue();
    }
    private void awaitIdle() throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(auto.status().path("busy").asBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(auto.status().path("busy").asBoolean()).isFalse();
    }

    @Test void unavailableNewsStopsBeforeAnyStockHistoryIsFetched(){
        start();when(ai.internalPost(eq("/api/v1/automation/news-plan"),any())).thenReturn(Map.of("status","NEWS_UNAVAILABLE","reasons",List.of("No available news today or in the last seven days.")));
        auto.cycle();auto.cycle();
        assertThat(auto.status().path("state").asText()).isEqualTo("NEWS_WAIT");
        assertThat(auto.status().at("/activity/status").asText()).isEqualTo("BLOCKED");
        verifyNoInteractions(gateway);verify(paper,never()).submitAutomatic(any(),anyString());
        verify(ai,times(1)).internalPost(eq("/api/v1/automation/news-plan"),any());
    }
}
