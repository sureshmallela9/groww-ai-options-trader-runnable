package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.tradingai.TradingAiClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

class PaperPlatformServiceTest {
    @Test void freshQuoteUsesEffectiveDashboardCashCapitalAndRejectsAnOldExpensiveProposal(){
        service.configureAutomation(()->Map.of("capitalLimit",2000),()->true);
        decision.put("referencePrice",200).put("stopLoss",199).put("targetPrice",203);
        when(gateway.quote("TEST")).thenReturn(quote("200","199.95",now));
        var rejected=service.submit(request(1),"capital-price-old-ai");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("Capital share-price limit","strictly below INR 200");
        assertThat(rejected.has("entryTime")).isFalse();
    }

    @Test void paperLeverageIncreasesExposureWithConfiguredExposureRiskBudget() throws Exception {
        service.updateIntraday(IntradayRules.paperDefaults());
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",1000);policy.put("maxPositionValue",1000);policy.put("riskPerTradePercent",2);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        decision.put("paperLeverage",5).put("stopLoss",99.4).put("targetPrice",102.1).put("requestedQuantity",20);
        var opened=service.submit(request(20),"five-x-risk-test");
        assertThat(opened.path("state").asText()).isEqualTo("POSITION_OPEN");
        assertThat(opened.path("marginRequired").decimalValue()).isEqualByComparingTo("400");
        assertThat(opened.path("positionValue").decimalValue()).isEqualByComparingTo("2000");
        service.updateIntraday(IntradayRules.defaults());
        assertThat(service.intent(opened.path("intentId").asText()).path("paperLeverage").asInt()).isEqualTo(5);
        assertThat(service.intent(opened.path("intentId").asText()).path("stopLoss").decimalValue()).isEqualByComparingTo("99.4");
    }
    @Test void leverageDoesNotBypassRiskAndBandsAreEnforcedIndependently(){
        service.updateIntraday(IntradayRules.paperDefaults());
        decision.put("paperLeverage",5).put("requestedQuantity",300);
        var rejected=service.submit(request(300),"five-x-risk-block");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("Risk per trade");
    }
    @Test void aTooNarrowStopIsRejected(){
        service.updateIntraday(IntradayRules.paperDefaults());
        decision.put("stopLoss",99.99);
        var narrow=service.submit(request(1),"narrow-band-test");
        assertThat(narrow.path("riskRejections").toString()).contains("price-band minimum");
    }
    @Test void executablePriceAtTheFallbackCeilingIsRejected(){
        service.updateIntraday(IntradayRules.paperDefaults());
        decision.put("selectionMode","PRICE_FALLBACK").put("referencePrice",500).put("stopLoss",497).put("targetPrice",510);
        when(gateway.quote("TEST")).thenReturn(quote("500","499.95",now));
        var rejected=service.submit(request(1),"fallback-ceiling-test");
        assertThat(rejected.path("riskRejections").toString()).contains("strictly below");
    }
    @Test void invalidIntradayRulesNeverReplaceSavedRules(){
        service.updateIntraday(IntradayRules.paperDefaults());
        var invalid=new IntradayRules(true,new BigDecimal("501"),new BigDecimal("6"),false,List.of());
        assertThatThrownBy(()->service.updateIntraday(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(((IntradayRules)service.status().get("intraday")).paperLeverage()).isEqualByComparingTo("5");
    }
    @Test void automaticEntryKeepsOnePositionEvenWhenManualPolicyAllowsMore() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());policy.put("maxOpenPositions",3);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        service.submit(request(1),"first-excl-1234");
        var next=decision.deepCopy();next.put("decisionId","DEC-exclusive-other123");next.put("symbol","OTHER");
        when(ai.internalGet("/api/v1/decisions/DEC-exclusive-other123")).thenReturn(next);
        when(gateway.quote("OTHER")).thenReturn(new NormalizedMarketGateway.QuoteData("OTHER",new BigDecimal("100"),
            new BigDecimal("99.95"),new BigDecimal("100"),now,true,true,true,new BigDecimal("0.05"),new BigDecimal("100000"),1000,1000));
        var rejected=service.submitAutomatic(new PaperPlatformService.Intent("DEC-exclusive-other123","PAPER",1,true),"next-excl-1234");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("one open position");
    }
    @Test void dashboardLimitsOnlyTightenTheIndependentPolicy(){
        service.configureAutomation(()->Map.of("capitalLimit",500,"riskPerTradePercent",0.2,
            "dailyLossPercent",1,"maxEntryOrdersPerDay",2,"stopAfterProfitableTrades",1),()->true);
        var effective=(PaperPlatformService.Policy)service.status().get("policy");
        assertThat(effective.capital()).isEqualByComparingTo("500");
        assertThat(effective.riskPerTradePercent()).isEqualByComparingTo("0.2");
        assertThat(effective.dailyLossPercent()).isEqualByComparingTo("1");
        assertThat(effective.maxTradesPerDay()).isEqualTo(2);
        assertThat(service.status()).containsEntry("autoEntryEnabled",true);
    }
    @Test void portfolioTracksOpenAndRealizedPnlIncludingEstimatedCosts(){
        var first=service.submit(request(1),"portfolio-1234");
        assertThat((BigDecimal)service.portfolio().get("unrealizedPnl")).isEqualByComparingTo("-0.25");
        when(gateway.quote("TEST")).thenReturn(quote("102.2","102.1",now));
        var closed=service.requestExit(first.path("intentId").asText());
        assertThat(closed.path("state").asText()).isEqualTo("POSITION_CLOSED");
        assertThat((BigDecimal)service.portfolio().get("realizedPnl")).isEqualByComparingTo("1.90");
        assertThat((BigDecimal)service.portfolio().get("unrealizedPnl")).isZero();
    }
    @Test void configuredWinningTradeLimitPreventsTheNextEntry(){
        service.configureAutomation(()->Map.of("stopAfterProfitableTrades",1),()->true);
        var first=service.submit(request(1),"winning-1234");
        when(gateway.quote("TEST")).thenReturn(quote("103.2","103.1",now));
        service.refreshPosition(first.path("intentId").asText());
        String other="DEC-winning-next123";var next=decision.deepCopy();next.put("decisionId",other);
        when(ai.internalGet("/api/v1/decisions/"+other)).thenReturn(next);
        when(gateway.quote("TEST")).thenReturn(quote("100","99.95",now));
        var rejected=service.submit(new PaperPlatformService.Intent(other,"PAPER",1,true),"winning-next123");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("profitable-trade stop");
    }
    @Test void legacyCashCapIsIgnoredOnStoredReadAndRemovedFromPolicyResponses() throws Exception {
        var root=(ObjectNode)json.readTree(db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='RISK'",String.class));
        var legacy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        legacy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",2).put("dailyLossAmount",250);
        root.set("policy",legacy);
        db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='RISK'",root.toString());
        assertThat((BigDecimal)service.status().get("dailyLossLimit")).isEqualByComparingTo("2000");
        assertThat(json.valueToTree(service.status()).path("policy").has("dailyLossAmount")).isFalse();
        for(int retiredCap:List.of(1,250,251)){
            legacy.put("dailyLossAmount",retiredCap);
            var saved=service.updatePolicy(json.treeToValue(legacy,PaperPlatformService.Policy.class));
            assertThat(saved.path("policy").has("dailyLossAmount")).isFalse();
            assertThat((BigDecimal)service.status().get("dailyLossLimit")).isEqualByComparingTo("2000");
        }
        legacy.remove("dailyLossAmount");
        assertThat(DailyLossBudget.forMode(json.treeToValue(legacy,PaperPlatformService.Policy.class),IntradayRules.defaults(),"PAPER").limit()).isEqualByComparingTo("2000");
    }
    @Test void percentageBudgetAllowsPlannedRiskAboveTheRetired250Cap() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",2).put("dailyLossAmount",250);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        decision.put("requestedQuantity",220);
        var opened=service.submit(request(220),"percentage-above-old-cap");
        assertThat(opened.path("state").asText()).isEqualTo("POSITION_OPEN");
        assertThat((BigDecimal)service.status().get("dailyLossLimit")).isEqualByComparingTo("2000");
    }
    @Test void openLossAbove250DoesNotExitBeforeThePercentageLimitOrTradeStop() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",2).put("dailyLossAmount",250);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));decision.put("requestedQuantity",400);
        var opened=service.submit(request(400),"percentage-monitor-above250");
        assertThat(opened.path("state").asText()).isEqualTo("POSITION_OPEN");
        when(gateway.quote("TEST")).thenReturn(quote("99.6","99.5",now));
        var held=service.refreshPosition(opened.path("intentId").asText());
        assertThat(held.path("unrealizedPnl").decimalValue()).isLessThan(new BigDecimal("-250"));
        assertThat(held.path("state").asText()).isEqualTo("POSITION_OPEN");
        assertThat(service.status()).containsEntry("dailyLossLatched",false);
    }
    @Test void percentageLimitStillUsesTheStricterDashboardCapitalAndPercent() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",5);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        service.configureAutomation(()->Map.of("capitalLimit",1800,"dailyLossPercent",2),()->true);
        assertThat((BigDecimal)service.status().get("dailyLossLimit")).isEqualByComparingTo("36");
    }
    @Test void riskStatusShowsSeparatePaperAndRealExposureBasesWithoutChangingCashRisk() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",1800).put("maxPositionValue",1800).put("dailyLossPercent",2);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        service.updateIntraday(new IntradayRules(false,new BigDecimal("500"),new BigDecimal("5"),false,List.of(),new BigDecimal("3")));
        var modes=mock(ExecutionModeService.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"executionModes",modes);
        when(modes.mode()).thenReturn("PAPER");
        var paperStatus=service.status();
        assertThat(paperStatus).containsEntry("dailyLossBasis","CONFIGURED_INTRADAY_EXPOSURE");
        assertThat((BigDecimal)paperStatus.get("dailyLossExposureBase")).isEqualByComparingTo("9000");
        assertThat((BigDecimal)paperStatus.get("dailyLossLimit")).isEqualByComparingTo("180");
        when(modes.mode()).thenReturn("REAL");
        var realStatus=service.status();
        assertThat((BigDecimal)realStatus.get("dailyLossExposureBase")).isEqualByComparingTo("5400");
        assertThat((BigDecimal)realStatus.get("dailyLossLimit")).isEqualByComparingTo("108");
        assertThat(((PaperPlatformService.Policy)realStatus.get("policy")).capital()).isEqualByComparingTo("1800");
        assertThat(((PaperPlatformService.Policy)realStatus.get("policy")).riskPerTradePercent()).isEqualByComparingTo("0.5");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PAPER","REAL"})
    void openPositionUsesLeveragedDailyLossBudgetAndRealExitsWaitForBrokerFills(String mode) throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",100000).put("maxPositionValue",100000);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        decision.put("requestedQuantity",20).put("stopLoss",90).put("targetPrice",130);
        var opened=service.submit(request(20),"leveraged-daily-monitor");
        assertThat(opened.path("state").asText()).isEqualTo("POSITION_OPEN");
        opened.put("mode",mode);
        db.update("UPDATE PAPER_INTENTS SET PAYLOAD_JSON=? WHERE INTENT_ID=?",opened.toString(),opened.path("intentId").asText());
        var modes=mock(ExecutionModeService.class);when(modes.mode()).thenReturn(mode);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"executionModes",modes);
        policy.put("capital",1800).put("maxPositionValue",1800).put("dailyLossPercent",2);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        service.updateIntraday(new IntradayRules(false,new BigDecimal("500"),new BigDecimal("5"),false,List.of(),new BigDecimal("3")));
        when(gateway.quote("TEST")).thenReturn(quote("97.05","97",now));
        var held=service.refreshPosition(opened.path("intentId").asText());
        assertThat(held.path("unrealizedPnl").decimalValue()).isLessThan(new BigDecimal("-36"));
        assertThat(held.path("state").asText()).isEqualTo("POSITION_OPEN");
        assertThat(service.status()).containsEntry("dailyLossLatched",false);
        when(gateway.quote("TEST")).thenReturn(quote("90.55","90.5",now));
        var exit=service.refreshPosition(opened.path("intentId").asText());
        assertThat(exit.path("exitReason").asText()).isEqualTo("DAILY_LOSS_LIMIT");
        assertThat(service.status()).containsEntry("dailyLossLatched",true);
        if("REAL".equals(mode)){
            assertThat(exit.path("state").asText()).isEqualTo("EXIT_PENDING");
            assertThat(exit.has("exitTime")).isFalse();assertThat(exit.path("remainingQuantity").asInt()).isEqualTo(20);
        }else assertThat(exit.path("state").asText()).isEqualTo("POSITION_CLOSED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={10,17})
    void newEntryUsesRemainingLeveragedDailyBudgetAfterARealizedLoss(int firstQuantity)throws Exception{
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",1800).put("maxPositionValue",1800).put("riskPerTradePercent",2).put("dailyLossPercent",2);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        service.updateIntraday(new IntradayRules(false,new BigDecimal("500"),new BigDecimal("5"),false,List.of()));
        decision.put("paperLeverage",5).put("requestedQuantity",firstQuantity);
        var first=service.submit(request(firstQuantity),"leverage-budget-first");
        assertThat(first.path("state").asText()).isEqualTo("POSITION_OPEN");
        when(gateway.quote("TEST")).thenReturn(quote("90.05","90",now));
        var closed=service.requestExit(first.path("intentId").asText());
        assertThat(closed.path("state").asText()).isEqualTo("POSITION_CLOSED");
        assertThat(service.status()).containsEntry("dailyLossLatched",false);
        int quantity=firstQuantity==10?20:6;
        String nextId="DEC-leveraged-budget-next";
        var next=decision.deepCopy().put("decisionId",nextId).put("requestedQuantity",quantity);
        when(ai.internalGet("/api/v1/decisions/"+nextId)).thenReturn(next);
        when(gateway.quote("TEST")).thenReturn(quote("100","99.95",now));
        var result=service.submit(new PaperPlatformService.Intent(nextId,"PAPER",quantity,true),"leverage-budget-next");
        if(firstQuantity==10)assertThat(result.path("state").asText()).isEqualTo("POSITION_OPEN");
        else assertThat(result.path("riskRejections").toString()).contains("remaining daily loss budget");
    }
    @Test void percentageLimitRejectsPlannedRiskAboveRemainingDailyBudget() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",0.25);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        decision.put("requestedQuantity",220);
        var rejected=service.submit(request(220),"cash-cap-risk-1234");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("remaining daily loss budget");
        assertThat((BigDecimal)service.status().get("dailyLossLimit")).isEqualByComparingTo("250");
    }
    @Test void dailyPercentageLossLatchesThroughPendingExitAndRebound() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("capital",100000).put("maxPositionValue",100000).put("dailyLossPercent",0.25);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));decision.put("requestedQuantity",200);
        var first=service.submit(request(200),"cash-cap-open-1234");assertThat(first.path("state").asText()).isEqualTo("POSITION_OPEN");
        when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("98.6"),new BigDecimal("98.5"),new BigDecimal("98.6"),now,true,true,true,new BigDecimal("0.05"),new BigDecimal("100000"),0,1000));
        var pending=service.refreshPosition(first.path("intentId").asText());
        assertThat(pending.path("state").asText()).isEqualTo("EXIT_PENDING");assertThat(pending.has("exitTime")).isFalse();
        assertThat(service.status()).containsEntry("dailyLossLatched",true);
        when(gateway.quote("TEST")).thenReturn(quote("101","100.9",now));
        var closed=service.refreshPosition(first.path("intentId").asText());assertThat(closed.path("state").asText()).isEqualTo("POSITION_CLOSED");assertThat(closed.path("realizedPnl").decimalValue()).isPositive();
        var next=decision.deepCopy().put("decisionId","DEC-cashcap-next-1234");when(ai.internalGet("/api/v1/decisions/DEC-cashcap-next-1234")).thenReturn(next);
        when(gateway.quote("TEST")).thenReturn(quote("100","99.95",now));
        var rejected=service.submit(new PaperPlatformService.Intent("DEC-cashcap-next-1234","PAPER",1,true),"cash-cap-next-1234");
        assertThat(rejected.path("riskRejections").toString()).contains("Daily realized/unrealized loss");
    }
    final ObjectMapper json=new ObjectMapper();
    final Instant now=Instant.parse("2026-10-28T04:30:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    JdbcTemplate db;NormalizedMarketGateway gateway;TradingAiClient ai;
    SessionCalendar calendar;PaperPlatformService service;
    String id="DEC-1234567890abcdef";
    ObjectNode decision;
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V2__paper_platform.sql")).execute(source);
        db=new JdbcTemplate(source);gateway=mock(NormalizedMarketGateway.class);ai=mock(TradingAiClient.class);calendar=mock(SessionCalendar.class);
        when(calendar.status(clock)).thenReturn(Map.of("verified",true,"entryAllowed",true,"sessionOpen",true,"squareOffDue",false));
        when(gateway.quote("TEST")).thenReturn(quote("100","99.95",now));
        decision=json.createObjectNode();
        decision.put("decisionId",id);decision.put("symbol","TEST");decision.put("exchange","NSE");
        decision.put("mode","PAPER");decision.put("intentType","ENTRY");decision.put("instrumentType","EQUITY");
        decision.put("action","BUY");decision.put("strategy","NSE_CASH_MOMENTUM_V1");decision.put("strategyVersion",1);
        decision.put("createdAt",now.minusSeconds(1).toString());decision.put("validUntil",now.plusSeconds(120).toString());
        decision.put("referencePrice",100);decision.put("stopLoss",99);decision.put("targetPrice",103);decision.put("requestedQuantity",5);
        when(ai.internalGet("/api/v1/decisions/"+id)).thenReturn(decision);
        service=new PaperPlatformService(db,json,gateway,ai,calendar,clock,new DataSourceTransactionManager(source),true);
    }
    NormalizedMarketGateway.QuoteData quote(String ask,String bid,Instant timestamp){
        return new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal(ask),new BigDecimal(bid),new BigDecimal(ask),
            timestamp,true,true,true,new BigDecimal("0.05"),new BigDecimal("100000"),1000,1000);
    }
    PaperPlatformService.Intent request(int quantity){return new PaperPlatformService.Intent(id,"PAPER",quantity,true);}
    void shortDecision(){decision.put("action","SELL");decision.put("strategy","NSE_CASH_MOMENTUM_V2");decision.put("strategyVersion",2);decision.put("positionSide","SHORT");decision.put("referencePrice",99.95);decision.put("stopLoss",101);decision.put("targetPrice",97);}
    @Test void shortEntrySellsAtBidAndTargetCoversAtAskWithCorrectNetProfit(){
        shortDecision();var opened=service.submit(request(1),"short-target-test");
        assertThat(opened.path("state").asText()).isEqualTo("POSITION_OPEN");assertThat(opened.path("entryPrice").decimalValue()).isEqualByComparingTo("99.95");
        assertThat(opened.path("unrealizedPnl").decimalValue()).isEqualByComparingTo("-0.25");
        when(gateway.quote("TEST")).thenReturn(quote("97","96.95",now));var closed=service.refreshPosition(opened.path("intentId").asText());
        assertThat(closed.path("state").asText()).isEqualTo("POSITION_CLOSED");assertThat(closed.path("exitReason").asText()).isEqualTo("TARGET");
        assertThat(closed.path("exitAction").asText()).isEqualTo("BUY");assertThat(closed.path("exitPrice").decimalValue()).isEqualByComparingTo("97");
        assertThat(closed.path("realizedPnl").decimalValue()).isEqualByComparingTo("2.75");
    }
    @Test void shortStopUsesAskRatherThanLastPriceOrBid(){
        shortDecision();var opened=service.submit(request(1),"short-stop-test");
        when(gateway.quote("TEST")).thenReturn(quote("101","100.9",now));var closed=service.refreshPosition(opened.path("intentId").asText());
        assertThat(closed.path("exitReason").asText()).isEqualTo("STOP_LOSS");assertThat(closed.path("realizedPnl").decimalValue()).isNegative();
    }
    @Test void shortCoverWaitsForAskDepthAndDoesNotFabricateAFill(){
        shortDecision();var opened=service.submit(request(1),"short-depth-test");
        when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("97"),new BigDecimal("96.95"),new BigDecimal("97"),now,true,true,true,new BigDecimal("0.05"),new BigDecimal("100000"),1000,0));
        var pending=service.refreshPosition(opened.path("intentId").asText());assertThat(pending.path("state").asText()).isEqualTo("EXIT_PENDING");assertThat(pending.has("exitPrice")).isFalse();
    }
    @Test void shortEntryRequiresSellEligibilityAndPreservesTheSinglePositionLimit(){
        shortDecision();when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("100"),new BigDecimal("99.95"),new BigDecimal("100"),now,true,true,true,new BigDecimal("0.05"),new BigDecimal("100000"),1000,1000,false));
        var rejected=service.submit(request(1),"short-permission-test");assertThat(rejected.path("riskRejections").toString()).contains("eligibility");
    }
    @Test void periodicAiReviewsAreFiveSecondSnapshotsWithOnlyOnePendingPeriodicEvent(){
        var instant=new java.util.concurrent.atomic.AtomicReference<>(now);
        Clock moving=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return instant.get();}};
        when(calendar.status(moving)).thenReturn(Map.of("verified",true,"entryAllowed",true,"squareOffDue",false));
        service=new PaperPlatformService(db,json,gateway,ai,calendar,moving,new DataSourceTransactionManager(db.getDataSource()),true);
        var opened=service.submit(request(1),"periodic-ai-test");
        for(int seconds:List.of(6,12,18)){
            instant.set(now.plusSeconds(seconds));when(gateway.quote("TEST")).thenReturn(quote("100.2","100.1",instant.get()));service.monitor();
        }
        var periodic=db.query("SELECT PAYLOAD_JSON FROM PLATFORM_OUTBOX WHERE STATUS='PENDING'",(r,i)->r.getString(1)).stream().map(value->{try{return json.readTree(value);}catch(Exception e){throw new RuntimeException(e);}}).filter(e->"REASSESSMENT".equals(e.path("reason").asText())).toList();
        assertThat(periodic).hasSize(1);assertThat(periodic.getFirst().path("quoteTime").asText()).isEqualTo(now.plusSeconds(6).toString());
        assertThat(service.intent(opened.path("intentId").asText()).path("state").asText()).isEqualTo("POSITION_OPEN");
    }
    @Test void repeatedIntentDoesNotDuplicatePositionEvenWhenServicesGoOffline() throws Exception {
        var first=service.submit(request(1),"request-1234");
        assertThat(first.path("state").asText()).isEqualTo("POSITION_OPEN");
        when(ai.internalGet(anyString())).thenThrow(new IllegalStateException("offline"));
        when(gateway.quote(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThat(service.submit(request(1),"request-1234")).isEqualTo(json.readTree(first.toString()));
        assertThat(service.intents()).hasSize(1);
        assertThatThrownBy(()->service.submit(request(2),"request-1234")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void concurrentDuplicateSignalsProduceExactlyOnePosition() throws Exception {
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<java.util.concurrent.Future<ObjectNode>>();
            for(int i=0;i<8;i++)futures.add(threads.submit(()->service.submit(request(1),"same-key-123")));
            for(var future:futures)assertThat(future.get().path("state").asText()).isEqualTo("POSITION_OPEN");
        }
        assertThat(service.intents()).hasSize(1);
    }
    @Test void staleAndExpiredSignalsAreRejected(){
        when(gateway.quote("TEST")).thenReturn(quote("100","99.95",now.minusSeconds(60)));
        var stale=service.submit(request(1),"stale-1234");
        assertThat(stale.path("state").asText()).isEqualTo("REJECTED");
        assertThat(stale.path("riskRejections").toString()).contains("stale");
    }
    @Test void expiredSignalAndDisabledStrategyFailClosed() throws Exception {
        decision.put("createdAt",now.minusSeconds(400).toString());
        decision.put("validUntil",now.minusSeconds(100).toString());
        var policy=json.valueToTree(PaperPlatformService.Policy.defaults());
        ((ObjectNode)policy).put("strategyEnabled",false);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        var rejected=service.submit(request(1),"expired-1234");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("expired").contains("strategy is disabled");
    }
    @Test void realizedLossBlocksTheNextEntryAtTheDailyLimit() throws Exception{
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        policy.put("dailyLossPercent",new BigDecimal("0.03"));
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        var first=service.submit(request(1),"first-loss-1234");
        when(gateway.quote("TEST")).thenReturn(quote("96.1","96",now));
        service.refreshPosition(first.path("intentId").asText());
        var next=decision.deepCopy();String other="DEC-daily-loss-123456";next.put("decisionId",other);
        when(ai.internalGet("/api/v1/decisions/"+other)).thenReturn(next);
        when(gateway.quote("TEST")).thenReturn(quote("100","99.95",now));
        var rejected=service.submit(new PaperPlatformService.Intent(other,"PAPER",1,true),"daily-loss-1234");
        assertThat(rejected.path("riskRejections").toString()).contains("Daily realized/unrealized loss");
    }
    @Test void killSwitchClosesPaperPositionWithoutAiAvailability(){
        var first=service.submit(request(1),"paper-1234");
        when(ai.internalGet(anyString())).thenThrow(new IllegalStateException("offline"));
        service.kill(true);
        when(gateway.quote("TEST")).thenReturn(quote("98.1","98",now));
        var closed=service.refreshPosition(first.path("intentId").asText());
        assertThat(closed.path("state").asText()).isEqualTo("POSITION_CLOSED");
        assertThat(closed.path("exitPrice").decimalValue()).isEqualByComparingTo("98");
        assertThat(closed.path("remainingQuantity").asInt()).isZero();
        assertThat(closed.path("realizedPnl").decimalValue()).isNegative();
        assertThat(closed.path("exitReason").asText()).isEqualTo("KILL_SWITCH");
    }
    @Test void unavailableQuoteLeavesExitPendingWithVisibleIncident(){
        var first=service.submit(request(1),"paper-1234");
        service.kill(true);
        when(gateway.quote(anyString())).thenThrow(new IllegalStateException("offline"));
        service.monitor();
        var pending=service.intent(first.path("intentId").asText());
        assertThat(pending.path("state").asText()).isEqualTo("EXIT_PENDING");
        assertThat(pending.path("incident").asText()).isEqualTo("MARKET_DATA_UNAVAILABLE");
        assertThat(pending.has("exitPrice")).isFalse();
    }
    @Test void secondSymbolCannotBypassTheOnePositionLimit(){
        service.submit(request(1),"paper-1234");
        String other="DEC-abcdef1234567890";
        var next=decision.deepCopy();next.put("decisionId",other);next.put("symbol","OTHER");
        when(ai.internalGet("/api/v1/decisions/"+other)).thenReturn(next);
        when(gateway.quote("OTHER")).thenReturn(new NormalizedMarketGateway.QuoteData("OTHER",new BigDecimal("100"),
            new BigDecimal("99.95"),new BigDecimal("100"),now,true,true,true,new BigDecimal("0.05"),
            new BigDecimal("100000"),1000,1000));
        var rejected=service.submit(new PaperPlatformService.Intent(other,"PAPER",1,true),"other-1234");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("Maximum open positions");
    }
    @Test void deliveredPositionEventsAreDurableAndDeduplicated(){
        service.submit(request(1),"paper-1234");
        when(ai.internalPost(eq("/api/v1/positions/events"),any())).thenAnswer(invocation->{
            ObjectNode event=invocation.getArgument(1);
            return Map.of("eventId",event.path("eventId").asText(),"positionId",event.path("positionId").asText(),"action","HOLD");
        });
        service.dispatchEvents();service.dispatchEvents();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM PLATFORM_INBOX",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM PLATFORM_OUTBOX WHERE STATUS='DELIVERED'",Integer.class)).isEqualTo(1);
        verify(ai,times(1)).internalPost(eq("/api/v1/positions/events"),any());
    }

    @Test void oneActivePositionIsAlsoEnforcedForManualIntents() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());policy.put("maxOpenPositions",5);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        assertThat(((PaperPlatformService.Policy)service.status().get("policy")).maxOpenPositions()).isEqualTo(1);
        service.submit(request(1),"single-manual-first");
        var next=decision.deepCopy().put("decisionId","DEC-manual-next1234");
        when(ai.internalGet("/api/v1/decisions/DEC-manual-next1234")).thenReturn(next);
        var rejected=service.submit(new PaperPlatformService.Intent("DEC-manual-next1234","PAPER",1,true),"single-manual-next");
        assertThat(rejected.path("state").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("riskRejections").toString()).contains("Only one active order");
    }
    @Test void aiExitProposalDoesNotCloseTheHeldStockBeforeItsTargetOrStop(){
        var first=service.submit(request(1),"hold-target-first");
        when(ai.internalPost(eq("/api/v1/positions/events"),any())).thenAnswer(call->{
            var event=(com.fasterxml.jackson.databind.JsonNode)call.getArgument(1);
            return Map.of("eventId",event.path("eventId").asText(),"positionId",event.path("positionId").asText(),"action","EXIT","reason","A different AI signal");
        });
        service.dispatchEvents();
        assertThat(service.intent(first.path("intentId").asText()).path("state").asText()).isEqualTo("POSITION_OPEN");
        when(gateway.quote("TEST")).thenReturn(quote("101","100.9",now));service.monitor();
        assertThat(service.intent(first.path("intentId").asText()).path("state").asText()).isEqualTo("POSITION_OPEN");
        when(gateway.quote("TEST")).thenReturn(quote("103.2","103.1",now));service.monitor();
        assertThat(service.intent(first.path("intentId").asText()).path("exitReason").asText()).isEqualTo("TARGET");
    }
    @Test void deadlineRecordsPendingExitWithStaleDataAndClosesOnlyOnAFreshQuote(){
        var first=service.submit(request(1),"deadline-entry-first");
        var deadline=Clock.fixed(Instant.parse("2026-10-28T09:29:00Z"),ZoneOffset.UTC);
        when(calendar.status(deadline)).thenReturn(Map.of("verified",true,"entryAllowed",true,"sessionOpen",true,"squareOffDue",false));
        var late=new PaperPlatformService(db,json,gateway,ai,calendar,deadline,new DataSourceTransactionManager(db.getDataSource()),true);
        late.monitor();var pending=late.intent(first.path("intentId").asText());
        assertThat(pending.path("state").asText()).isEqualTo("EXIT_PENDING");
        assertThat(pending.path("forcedExitReason").asText()).isEqualTo("TIME_EXIT_1459");
        when(gateway.quote("TEST")).thenReturn(quote("100.2","100.1",deadline.instant()));late.monitor();
        assertThat(late.intent(first.path("intentId").asText()).path("state").asText()).isEqualTo("POSITION_CLOSED");
        assertThat(late.intent(first.path("intentId").asText()).path("exitReason").asText()).isEqualTo("TIME_EXIT_1459");
    }
    @Test void configuredDailyProfitTargetExitsAndLatchesTheSessionStop() throws Exception {
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults()).put("dailyProfitTarget",1);
        service.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        var first=service.submit(request(1),"daily-profit-first");
        when(gateway.quote("TEST")).thenReturn(quote("102.2","102.1",now));service.monitor();
        assertThat(service.intent(first.path("intentId").asText()).path("exitReason").asText()).isEqualTo("DAILY_PROFIT_TARGET");
        when(gateway.quote("TEST")).thenReturn(quote("100","99.95",now));
        var next=decision.deepCopy().put("decisionId","DEC-profit-next12345");when(ai.internalGet("/api/v1/decisions/DEC-profit-next12345")).thenReturn(next);
        var rejected=service.submit(new PaperPlatformService.Intent("DEC-profit-next12345","PAPER",1,true),"daily-profit-next");
        assertThat(rejected.path("riskRejections").toString()).contains("Daily profit target");
    }

    @Test void differentConcurrentManualDecisionsCannotOpenMoreThanOnePosition() throws Exception {
        when(ai.internalGet(startsWith("/api/v1/decisions/DEC-race-"))).thenAnswer(call->{
            String path=call.getArgument(0);return decision.deepCopy().put("decisionId",path.substring(path.lastIndexOf('/')+1));
        });
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<java.util.concurrent.Future<ObjectNode>>();
            for(int i=0;i<5;i++){final int index=i;futures.add(threads.submit(()->service.submit(new PaperPlatformService.Intent("DEC-race-signal000"+index,"PAPER",1,true),"distinct-race-key"+index)));}
            int opened=0;for(var future:futures)if("POSITION_OPEN".equals(future.get().path("state").asText()))opened++;
            assertThat(opened).isEqualTo(1);
            assertThat(service.intents().stream().filter(row->"POSITION_OPEN".equals(row.path("state").asText())).count()).isEqualTo(1);
        }
    }
}
