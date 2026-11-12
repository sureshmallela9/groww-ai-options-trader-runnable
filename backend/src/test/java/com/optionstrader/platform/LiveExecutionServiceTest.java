package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class LiveExecutionServiceTest {
    ObjectMapper json=new ObjectMapper();JdbcTemplate db;DataSourceTransactionManager manager;
    GrowwOrderAdapter broker;PublicIpProbe ip;ExecutionModeService modes;NormalizedMarketGateway gateway;SessionCalendar calendar;LiveExecutionService live;
    Clock clock=Clock.fixed(Instant.parse("2026-11-06T05:00:00Z"),ZoneOffset.UTC);
    Map<String,ObjectNode> details;Map<String,ArrayNode> trades;boolean timeout;int position;String id="live-test";
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V2__paper_platform.sql")).execute(source);db=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        broker=mock(GrowwOrderAdapter.class);ip=mock(PublicIpProbe.class);gateway=mock(NormalizedMarketGateway.class);calendar=mock(SessionCalendar.class);
        when(ip.current()).thenReturn("203.0.113.10");when(broker.funds()).thenReturn(json.createObjectNode().set("equity_margin_details",json.createObjectNode().put("mis_balance_available",10000)));
        when(broker.margin(any())).thenReturn(json.createObjectNode().put("total_requirement",300));when(broker.orders()).thenReturn(json.createArrayNode());
        // ArrayNode construction remains explicit: no broker network or account credentials are used.
        when(broker.positions()).thenAnswer(x->{var list=json.createArrayNode();list.addObject().put("trading_symbol","TEST").put("exchange","NSE").put("product","MIS").put("quantity",position);return json.createObjectNode().set("positions",list);});
        when(calendar.status(any())).thenReturn(Map.of("entryAllowed",true,"verified",true,"sessionOpen",true));
        when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("100"),new BigDecimal("99.95"),new BigDecimal("100"),clock.instant(),true,true,true,new BigDecimal("0.05"),new BigDecimal("1000000"),100,100));
        details=new LinkedHashMap<>();trades=new HashMap<>();
        when(broker.create(any())).thenAnswer(call->{var request=(ObjectNode)call.getArgument(0);String orderId="ORDER"+(details.size()+1);var detail=request.deepCopy();detail.put("groww_order_id",orderId).put("filled_quantity",0).put("order_status","LIMIT".equals(request.path("order_type").asText())?"OPEN":"TRIGGER_PENDING");details.put(orderId,detail);trades.put(orderId,json.createArrayNode());if(timeout){timeout=false;throw new IllegalStateException("Simulated response timeout");}return json.createObjectNode().put("groww_order_id",orderId);});
        when(broker.detail(anyString())).thenAnswer(call->details.get(call.getArgument(0)).deepCopy());when(broker.trades(anyString())).thenAnswer(call->trades.get(call.getArgument(0)).deepCopy());
        when(broker.byReference(anyString())).thenAnswer(call->details.values().stream().filter(d->d.path("order_reference_id").asText().equals(call.getArgument(0))).findFirst().orElseThrow().deepCopy());
        modes=new ExecutionModeService(db,json,manager,broker,ip,clock,true);
        db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='RISK'","{\"executionMode\":\"REAL\",\"realPublicIp\":\"203.0.113.10\"}");
        live=new LiveExecutionService(db,json,manager,broker,modes,gateway,calendar,clock);live.configureRiskValidator((r,q)->{});live.configureAutomation(()->true);
        var row=json.createObjectNode().put("intentId",id).put("decisionId","DEC-live-12345678").put("mode","REAL").put("symbol","TEST").put("createdAt",clock.instant().toString()).put("sessionDate","2026-11-06")
            .put("positionSide","LONG").put("entryAction","BUY").put("exitAction","SELL").put("approvedCapital",10000).put("costBps",20);
        row.set("decision",json.createObjectNode().put("stopLoss",99).put("targetPrice",103).put("validUntil",clock.instant().plusSeconds(300).toString()));
        live.prepare(row,gateway.quote("TEST"),3);
        db.update("INSERT INTO PAPER_INTENTS(INTENT_ID,DECISION_ID,REQUEST_KEY,REQUEST_HASH,STATE,SYMBOL,SESSION_DATE,PAYLOAD_JSON,UPDATED_AT) VALUES(?,?,?,?,?,?,?,?,?)",id,"DEC-live-12345678","live-key-1234","hash",row.path("state").asText(),"TEST","2026-11-06",row.toString(),clock.instant().toString());
    }
    ObjectNode row(){try{return (ObjectNode)json.readTree(db.queryForObject("SELECT PAYLOAD_JSON FROM PAPER_INTENTS WHERE INTENT_ID=?",String.class,id));}catch(Exception e){throw new RuntimeException(e);}}
    void change(java.util.function.Consumer<ObjectNode> work){var r=row();work.accept(r);db.update("UPDATE PAPER_INTENTS SET PAYLOAD_JSON=?,STATE=? WHERE INTENT_ID=?",r.toString(),r.path("state").asText(),id);}
    void fill(String order,int quantity,String price,String status){
        var d=details.get(order);d.put("filled_quantity",quantity).put("order_status",status);var list=json.createArrayNode();if(quantity>0)list.addObject().put("groww_order_id",order).put("groww_trade_id",order+"T1").put("trading_symbol","TEST").put("transaction_type",d.path("transaction_type").asText()).put("trade_status","COMPLETED").put("quantity",quantity).put("price",new BigDecimal(price)).put("trade_date_time",clock.instant().toString());trades.put(order,list);
    }
    @Test void acknowledgementIsNotAFillAndReconciliationCreatesProtection(){
        live.tick(id);assertThat(row().has("filledQuantity")).isFalse();assertThat(row().path("state").asText()).isEqualTo("ENTRY_PENDING");
        fill("ORDER1",3,"99.98","EXECUTED");position=3;live.tick(id);
        assertThat(row().path("entryPrice").decimalValue()).isEqualByComparingTo("99.98");assertThat(row().path("remainingQuantity").asInt()).isEqualTo(3);
        assertThat(details.get("ORDER2").path("order_type").asText()).isEqualTo("SL");assertThat(details.get("ORDER2").path("transaction_type").asText()).isEqualTo("SELL");
        fill("ORDER2",3,"98.90","EXECUTED");position=0;live.tick(id);
        assertThat(row().path("state").asText()).isEqualTo("POSITION_CLOSED");assertThat(row().path("exitPrice").decimalValue()).isEqualByComparingTo("98.9");assertThat(row().path("executionFills").size()).isEqualTo(2);
        live.tick(id);verify(broker,times(2)).create(any());
    }
    @Test void observedGrowwExecutedTradePayloadPassesAllIdentityAndQuantityChecks()throws Exception{
        var fixture=json.readTree(new ClassPathResource("fixtures/groww-pine-executed.json").getInputStream());
        var command=json.createObjectNode().put("id",fixture.path("detail").path("groww_order_id").asText()).put("reference",fixture.path("request").path("order_reference_id").asText());
        command.set("request",fixture.path("request"));
        LiveExecutionService.validateDetail(command,fixture.path("detail"),fixture.path("trades"));
        assertThat(LiveExecutionService.tradeTime(fixture.path("trades").get(0))).isEqualTo(Instant.parse("2026-11-11T03:50:26Z"));
        var bad=fixture.path("trades").deepCopy();((ObjectNode)bad.get(0)).put("trade_status","PENDING");
        assertThatThrownBy(()->LiveExecutionService.validateDetail(command,fixture.path("detail"),bad)).hasMessageContaining("mismatch");
        ((ObjectNode)bad.get(0)).put("trade_status","EXECUTED").put("groww_order_id","UNRELATED");
        assertThatThrownBy(()->LiveExecutionService.validateDetail(command,fixture.path("detail"),bad)).hasMessageContaining("mismatch");
    }
    @Test void executedTradeStatusReconcilesTheFillAndQueuesOnlyOneProtectiveStop(){
        live.tick(id);fill("ORDER1",3,"99.98","EXECUTED");position=3;
        ((ObjectNode)trades.get("ORDER1").get(0)).put("trade_status","EXECUTED").put("trade_date_time","2026-11-06T10:30:00");
        live.tick(id);live.tick(id);
        assertThat(row().path("filledQuantity").asInt()).isEqualTo(3);
        assertThat(row().path("entryPrice").decimalValue()).isEqualByComparingTo("99.98");
        assertThat(details.get("ORDER2").path("order_type").asText()).isEqualTo("SL");
        verify(broker,times(2)).create(any());assertThat(row().has("incident")).isFalse();
    }
    ObjectNode manualExit(int quantity,String side){
        var order=json.createObjectNode().put("groww_order_id","MANUAL1").put("trading_symbol","TEST").put("exchange","NSE").put("segment","CASH").put("product","MIS")
            .put("transaction_type",side).put("quantity",quantity).put("filled_quantity",quantity).put("order_status","EXECUTED").put("order_type","MARKET").put("price",0).put("trigger_price",0);
        var fills=json.createArrayNode();fills.addObject().put("groww_order_id","MANUAL1").put("groww_trade_id","MANUAL_TRADE1").put("trading_symbol","TEST")
            .put("transaction_type",side).put("quantity",quantity).put("trade_status","EXECUTED").put("price",101).put("trade_date_time",clock.instant().toString());
        trades.put("MANUAL1",fills);when(broker.orders()).thenReturn(json.createArrayNode().add(details.get("ORDER1")).add(order));return order;
    }
    @Test void completedManualExitIsAdoptedOnlyFromBrokerTradesAndNoExtraSellIsSent(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=0;manualExit(3,"SELL");
        live.tick(id);
        assertThat(row().path("state").asText()).isEqualTo("POSITION_CLOSED");
        assertThat(row().path("remainingQuantity").asInt()).isZero();assertThat(row().path("exitPrice").decimalValue()).isEqualByComparingTo("101");
        assertThat(row().path("exitReason").asText()).isEqualTo("EXTERNAL_BROKER_EXIT");
        assertThat(row().path("executionFills").size()).isEqualTo(2);assertThat(row().has("incident")).isFalse();
        live.tick(id);verify(broker,times(1)).create(any());verify(broker,never()).cancel(anyString());verify(broker,never()).modify(any());
    }
    @Test void externalClosureCannotBeInferredFromFlatPositionWithoutExactTradeEvidence(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=0;manualExit(2,"SELL");
        live.tick(id);assertThat(row().has("exitTime")).isFalse();
        assertThat(row().path("incident").asText()).isEqualTo("BROKER_RECONCILIATION_REQUIRED");verify(broker,times(1)).create(any());
    }
    @Test void pendingOrOppositeExternalActivityIsNotAdoptedAsACompletedExit(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=0;var external=manualExit(3,"BUY");
        live.tick(id);assertThat(row().has("exitTime")).isFalse();
        external.put("transaction_type","SELL").put("order_status","OPEN");
        live.tick(id);assertThat(row().has("exitTime")).isFalse();verify(broker,times(1)).create(any());
    }
    @Test void manualClosureWaitsForOurPendingProtectiveOrderToBeCancelled(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=3;live.tick(id);
        position=0;manualExit(3,"SELL");live.tick(id);verify(broker).cancel("ORDER2");assertThat(row().has("exitTime")).isFalse();
        fill("ORDER2",0,"0","CANCELLED");live.tick(id);
        assertThat(row().path("state").asText()).isEqualTo("POSITION_CLOSED");verify(broker,times(2)).create(any());
    }
    @Test void duplicateExternalOrderRowsAndTradesNeverCreateDuplicateExitFills(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=0;var external=manualExit(3,"SELL");
        when(broker.orders()).thenReturn(json.createArrayNode().add(details.get("ORDER1")).add(external).add(external));
        live.tick(id);assertThat(row().has("exitTime")).isFalse();verify(broker,times(1)).create(any());
    }
    @Test void maximumWholeShareProposalIsReducedByExposureRiskBeforeMarginButNeverFilledLocally()throws Exception{
        var paper=sharedEngine();var ai=(com.optionstrader.tradingai.TradingAiClient)org.springframework.test.util.ReflectionTestUtils.getField(paper,"ai");
        ((ObjectNode)ai.internalGet("/api/v1/decisions/DEC-integrated-12345678")).put("requestedQuantity",100);
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());policy.put("capital",1800).put("maxPositionValue",1800);paper.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        paper.updateIntraday(new IntradayRules(false,new BigDecimal("500"),new BigDecimal("5"),false,List.of(),new BigDecimal("5")));
        when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("100"),new BigDecimal("99.95"),new BigDecimal("100"),clock.instant(),true,true,true,new BigDecimal("0.05"),new BigDecimal("1000000"),1000,1000));
        when(broker.margin(any())).thenAnswer(call->json.createObjectNode().put("total_requirement",((JsonNode)call.getArgument(0)).path("quantity").asInt()*20));
        var result=paper.submitAutomatic(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",null,true),"max-risk-aware-quantity");
        assertThat(result.path("quantity").asInt()).isEqualTo(36);assertThat(result.path("riskPerTradeLimit").decimalValue()).isEqualByComparingTo("45");
        assertThat(result.path("quantityAdjustedForRiskOrDepth").asBoolean()).isTrue();assertThat(result.path("quantityAdjustedForBrokerMargin").asBoolean()).isFalse();
        assertThat(result.path("state").asText()).isEqualTo("ENTRY_PENDING");assertThat(result.has("entryPrice")).isFalse();verify(broker,never()).create(any());
    }
    @Test void protectiveStopsHaveValidNonzeroLimitPricesAndTickBuffers(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=3;live.tick(id);
        assertThat(details.get("ORDER2").path("order_type").asText()).isEqualTo("SL");
        assertThat(details.get("ORDER2").path("trigger_price").decimalValue()).isEqualByComparingTo("99");
        assertThat(details.get("ORDER2").path("price").decimalValue()).isEqualByComparingTo("98.95");
        assertThat(LiveExecutionService.protectionLimit(new BigDecimal("101"),new BigDecimal(".05"),"BUY")).isEqualByComparingTo("101.05");
        assertThatThrownBy(()->LiveExecutionService.protectionLimit(new BigDecimal(".05"),new BigDecimal(".05"),"SELL")).hasMessageContaining("positive");
    }
    @Test void aTriggeredStopLimitConvertsOnlyItsRemainingQuantityToMarket(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=3;live.tick(id);live.tick(id);
        details.get("ORDER2").put("order_status","OPEN");live.tick(id);
        verify(broker).modify(argThat(q->"ORDER2".equals(q.path("groww_order_id").asText())&&"MARKET".equals(q.path("order_type").asText())&&q.path("price").asInt(-1)==0&&q.path("trigger_price").asInt(-1)==0));
        assertThat(row().path("exitReason").asText()).isEqualTo("STOP_TRIGGERED");verify(broker,times(2)).create(any());
    }
    @Test void uncertainCreateIsRecoveredByReferenceAcrossRestartWithoutResending(){
        timeout=true;live.tick(id);assertThat(row().path("brokerCommands").get(0).path("phase").asText()).isEqualTo("UNKNOWN");
        var restarted=new LiveExecutionService(db,json,manager,broker,modes,gateway,calendar,clock);restarted.tick(id);
        assertThat(row().path("brokerCommands").get(0).path("phase").asText()).isEqualTo("ACKED");verify(broker,times(1)).create(any());
    }
    @Test void partialEntryIsCancelledAndOnlyConfirmedQuantityGetsProtection(){
        live.tick(id);fill("ORDER1",1,"100","OPEN");position=1;live.tick(id);
        verify(broker).cancel("ORDER1");assertThat(row().path("remainingQuantity").asInt()).isEqualTo(1);
        assertThat(details.get("ORDER2").path("quantity").asInt()).isEqualTo(1);
        fill("ORDER1",1,"100","CANCELLED");live.tick(id);assertThat(row().path("filledQuantity").asInt()).isEqualTo(1);
    }
    @Test void exitRequestModifiesExistingStopInsteadOfSendingARacingSecondExit(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=3;live.tick(id);live.tick(id);
        change(r->{r.put("liveExitRequested",true);r.put("exitReason","TIME_EXIT_1459");});live.tick(id);
        verify(broker).modify(argThat(body->"ORDER2".equals(body.path("groww_order_id").asText())&&"MARKET".equals(body.path("order_type").asText())));
        verify(broker,times(2)).create(any());assertThat(row().path("state").asText()).isEqualTo("EXIT_PENDING");assertThat(row().has("exitTime")).isFalse();
    }
    @Test void mismatchedBrokerIdentityBlocksExecutionAndNeverInventsAClosure(){
        live.tick(id);details.get("ORDER1").put("trading_symbol","OTHER");live.tick(id);
        assertThat(row().path("incident").asText()).isEqualTo("BROKER_RECONCILIATION_REQUIRED");assertThat(row().has("entryTime")).isFalse();verify(broker,times(1)).create(any());
    }
    @Test void changedPublicIpOrMarginRejectsBeforeAnyOrderIsSent(){
        when(ip.current()).thenReturn("203.0.113.11");live.tick(id);assertThat(row().path("state").asText()).isEqualTo("REJECTED");verify(broker,never()).create(any());
    }
    @Test void modeCannotChangeWhileAnOrderIsPending(){assertThatThrownBy(()->modes.select(json.createObjectNode().put("mode","PAPER"))).hasMessageContaining("active or unresolved");assertThat(modes.mode()).isEqualTo("REAL");}
    @Test void protectionRejectQueuesEmergencyExitForTheRemainingActualPosition(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=3;live.tick(id);fill("ORDER2",0,"0","REJECTED");live.tick(id);
        assertThat(details.get("ORDER3").path("order_type").asText()).isEqualTo("MARKET");assertThat(row().path("exitReason").asText()).isEqualTo("PROTECTION_REJECTED");
    }
    PaperPlatformService sharedEngine(){
        db.update("DELETE FROM PAPER_INTENTS WHERE INTENT_ID=?",id);
        var ai=mock(com.optionstrader.tradingai.TradingAiClient.class);
        var decision=json.createObjectNode().put("decisionId","DEC-integrated-12345678").put("symbol","TEST").put("exchange","NSE").put("mode","REAL")
            .put("intentType","ENTRY").put("instrumentType","EQUITY").put("action","BUY").put("strategy","NSE_CASH_MOMENTUM_V1").put("strategyVersion",1)
            .put("createdAt",clock.instant().minusSeconds(1).toString()).put("validUntil",clock.instant().plusSeconds(120).toString())
            .put("referencePrice",100).put("stopLoss",99).put("targetPrice",103).put("requestedQuantity",3);
        when(ai.internalGet("/api/v1/decisions/DEC-integrated-12345678")).thenReturn(decision);
        var paper=new PaperPlatformService(db,json,gateway,ai,calendar,clock,manager,true);
        org.springframework.test.util.ReflectionTestUtils.setField(paper,"executionModes",modes);
        org.springframework.test.util.ReflectionTestUtils.setField(paper,"live",live);
        paper.configureAutomation(Map::of,()->false);return paper;
    }
    PaperPlatformService leveragedEngine() throws Exception {
        var paper=sharedEngine();var ai=(com.optionstrader.tradingai.TradingAiClient)org.springframework.test.util.ReflectionTestUtils.getField(paper,"ai");
        var decision=(ObjectNode)ai.internalGet("/api/v1/decisions/DEC-integrated-12345678");decision.put("requestedQuantity",500).put("stopLoss",99.9).put("targetPrice",100.3);
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());policy.put("roundTripCostBps",0);paper.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        paper.updateIntraday(new IntradayRules(false,new BigDecimal("500"),new BigDecimal("5"),false,List.of(),new BigDecimal("5")));
        when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("100"),new BigDecimal("99.95"),new BigDecimal("100"),clock.instant(),true,true,true,new BigDecimal("0.05"),new BigDecimal("1000000"),1000,1000));
        when(broker.margin(any())).thenAnswer(call->{var q=(JsonNode)call.getArgument(0);return json.createObjectNode().put("total_requirement",q.path("quantity").asInt()*20);});
        return paper;
    }
    @Test void capitalPriceCheckRejectsRealEntryBeforeMarginOrOrderSubmission()throws Exception{
        var paper=sharedEngine();paper.configureAutomation(()->Map.of("capitalLimit",2000),()->true);
        when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("200"),new BigDecimal("199.95"),new BigDecimal("200"),clock.instant(),true,true,true,new BigDecimal(".05"),new BigDecimal("1000000"),100,100));
        assertThatThrownBy(()->paper.submitAutomatic(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",null,true),"capital-live-reject")).hasMessageContaining("Capital share-price limit");
        verify(broker,never()).margin(any());verify(broker,never()).create(any());
    }
    @Test void changedCapitalPriceRulesRejectReservedEntryBeforeDispatch()throws Exception{
        var paper=sharedEngine();var result=paper.submit(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",3,true),"capital-dispatch-key");id=result.path("intentId").asText();
        paper.updateIntraday(new IntradayRules(false,new BigDecimal("500"),BigDecimal.ONE,false,List.of(),BigDecimal.ONE,true,List.of(new IntradayRules.CapitalPriceBand(null,false,new BigDecimal("99"),false))));
        live.tick(id);assertThat(row().path("state").asText()).isEqualTo("REJECTED");verify(broker,never()).create(any());
    }
    @Test void freshPriceOutsideCapitalBandRejectsBeforeRealDispatch(){
        var paper=sharedEngine();paper.configureAutomation(()->Map.of("capitalLimit",2000),()->true);
        var result=paper.submit(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",3,true),"capital-fresh-price");id=result.path("intentId").asText();
        when(gateway.quote("TEST")).thenReturn(new NormalizedMarketGateway.QuoteData("TEST",new BigDecimal("200"),new BigDecimal("199.95"),new BigDecimal("200"),clock.instant(),true,true,true,new BigDecimal(".05"),new BigDecimal("1000000"),100,100));
        live.tick(id);assertThat(row().path("state").asText()).isEqualTo("REJECTED");assertThat(row().toString()).contains("Capital share-price limit");verify(broker,never()).create(any());
    }
    @Test void brokerVerifiedFiveTimesEntryStillRequiresIndependentStopRiskAndHasNoSyntheticFill()throws Exception{
        var paper=leveragedEngine();var result=paper.submitAutomatic(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",null,true),"five-times-entry-key");
        assertThat(result.path("state").asText()).isEqualTo("ENTRY_PENDING");assertThat(result.path("quantity").asInt()).isEqualTo(500);assertThat(result.path("brokerMarginVerified").asBoolean()).isTrue();assertThat(result.path("positionValue").asInt()).isEqualTo(50000);assertThat(result.has("entryPrice")).isFalse();verify(broker,never()).create(any());
    }
    @Test void riskAndLeverageCapsAreRecheckedImmediatelyBeforeDispatch()throws Exception{
        var paper=leveragedEngine();var result=paper.submitAutomatic(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",null,true),"ceiling-change-key");id=result.path("intentId").asText();
        paper.updateIntraday(new IntradayRules(false,new BigDecimal("500"),new BigDecimal("5"),false,List.of(),new BigDecimal("3")));
        live.configureAutomation(()->true);live.tick(id);assertThat(row().path("state").asText()).isEqualTo("REJECTED");verify(broker,never()).create(any());
    }
    @Test void higherBrokerMarginAtDispatchPreventsSubmission()throws Exception{
        var paper=leveragedEngine();var result=paper.submitAutomatic(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",null,true),"margin-change-key");id=result.path("intentId").asText();
        doReturn(json.createObjectNode().put("total_requirement",16000)).when(broker).margin(any());live.configureAutomation(()->true);live.tick(id);assertThat(row().path("state").asText()).isEqualTo("REJECTED");verify(broker,never()).create(any());
    }
    @Test void sharedRiskEngineReservesRealIntentButNeverSimulatesAFill(){
        var paper=sharedEngine();var result=paper.submit(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",3,true),"integrated-live-key");
        assertThat(result.path("state").asText()).isEqualTo("ENTRY_PENDING");assertThat(result.has("entryPrice")).isFalse();verify(broker,never()).create(any());
        id=result.path("intentId").asText();live.tick(id);verify(broker).create(any());
    }
    @Test void tightenedRiskPolicyIsRecheckedAtDispatchAndCannotBeBypassedByAnEarlierReservation() throws Exception {
        var paper=sharedEngine();var result=paper.submit(new PaperPlatformService.Intent("DEC-integrated-12345678","REAL",3,true),"tightened-live-key");id=result.path("intentId").asText();
        var policy=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());policy.put("riskPerTradePercent",0.01);paper.updatePolicy(json.treeToValue(policy,PaperPlatformService.Policy.class));
        live.tick(id);assertThat(row().path("state").asText()).isEqualTo("REJECTED");verify(broker,never()).create(any());
    }
    @Test void uiModeSelectionStoresDestinationOnlyAndDoesNotSendOrders(){
        db.update("DELETE FROM PAPER_INTENTS WHERE INTENT_ID=?",id);
        modes.select(json.createObjectNode().put("mode","PAPER"));
        assertThat(modes.mode()).isEqualTo("PAPER");
        assertThatThrownBy(()->modes.select(json.createObjectNode().put("mode","REAL"))).hasMessageContaining("Confirm");
        var selected=modes.select(json.createObjectNode().put("mode","REAL").put("confirmed",true).put("ipRegistered",true).put("publicIp","203.0.113.10"));
        assertThat(selected).containsEntry("mode","REAL").containsEntry("requiresExplicitStart",true);verify(broker,never()).create(any());
    }
    @Test void realShortCreatesABuyCoverProtectionAndUsesActualCoverPrices(){
        change(r->{r.put("positionSide","SHORT").put("entryAction","SELL").put("exitAction","BUY").put("stopLoss",101).put("targetPrice",97).put("entryLimitPrice",99.95);
            ((ObjectNode)r.path("brokerCommands").get(0).path("request")).put("transaction_type","SELL").put("price",99.95);});
        live.tick(id);fill("ORDER1",3,"99.95","EXECUTED");position=-3;live.tick(id);
        assertThat(details.get("ORDER2").path("transaction_type").asText()).isEqualTo("BUY");
        fill("ORDER2",3,"97","EXECUTED");position=0;live.tick(id);assertThat(row().path("state").asText()).isEqualTo("POSITION_CLOSED");assertThat(row().path("realizedPnl").decimalValue()).isPositive();
    }
    @Test void insufficientBrokerMarginCannotSendAnEntry(){
        when(broker.margin(any())).thenReturn(json.createObjectNode().put("total_requirement",20000));live.tick(id);
        assertThat(row().path("state").asText()).isEqualTo("REJECTED");verify(broker,never()).create(any());
    }
    @Test void externallyChangedPositionCancelsProtectionWithoutInventingAnExit(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=3;live.tick(id);position=0;live.tick(id);
        verify(broker).cancel("ORDER2");assertThat(row().has("exitTime")).isFalse();assertThat(row().path("incident").asText()).isEqualTo("BROKER_RECONCILIATION_REQUIRED");verify(broker,times(2)).create(any());
    }
    @Test void deadlineExitDoesNotDependOnAiOrQuoteAvailability(){
        live.tick(id);fill("ORDER1",3,"100","EXECUTED");position=3;live.tick(id);
        when(gateway.quote(anyString())).thenThrow(new IllegalStateException("Quote unavailable"));
        var late=new LiveExecutionService(db,json,manager,broker,modes,gateway,calendar,Clock.fixed(Instant.parse("2026-11-06T09:29:00Z"),ZoneOffset.UTC));late.tick(id);
        verify(broker).modify(argThat(body->"MARKET".equals(body.path("order_type").asText())));assertThat(row().path("exitReason").asText()).isEqualTo("TIME_EXIT_1459");assertThat(row().path("state").asText()).isEqualTo("EXIT_PENDING");assertThat(row().has("exitTime")).isFalse();
    }
    @Test void modeSwitchRejectsAutomationThatRestartedDuringPreflight(){
        db.update("DELETE FROM PAPER_INTENTS WHERE INTENT_ID=?",id);db.update("INSERT INTO PLATFORM_STATE(STATE_ID,PAYLOAD_JSON) VALUES('AUTO','{\"enabled\":true}')");
        assertThatThrownBy(()->modes.select(json.createObjectNode().put("mode","PAPER"))).hasMessageContaining("restarted");assertThat(modes.mode()).isEqualTo("REAL");verify(broker,never()).create(any());
    }
}
