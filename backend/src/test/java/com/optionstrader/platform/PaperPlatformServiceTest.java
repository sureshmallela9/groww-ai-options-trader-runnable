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
}
