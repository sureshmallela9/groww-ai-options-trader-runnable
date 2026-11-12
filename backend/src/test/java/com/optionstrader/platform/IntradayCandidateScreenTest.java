package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.groww.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;

class IntradayCandidateScreenTest {
    final ObjectMapper json=new ObjectMapper();final Clock clock=Clock.fixed(Instant.parse("2026-11-12T05:00:00Z"),ZoneOffset.UTC);
    ObjectNode evidence(String side,String state){return json.createObjectNode().put("source","GROWW").put("product","MIS").put("exchange","NSE").put("side",side).put("status",state).put("referencePrice",100).put("requiredMargin",20).put("checkedAt",clock.instant().toString()).put("validUntil",clock.instant().plusSeconds(60).toString()).put("reason","Synthetic preflight");}
    ObjectNode candidates(int count){var value=json.createObjectNode();var rows=value.putArray("candidates");for(int i=0;i<count;i++)rows.addObject().put("symbol","STOCK"+i).put("technicalQualified",true).put("candidateStatus","PENDING_INTRADAY_CHECK").put("latestPrice",100).put("side","BUY").put("score",100-i).put("selectionSource",i==4?"NEWS":"PRICE_FALLBACK");return value;}
    @Test void onlyThreeReadOnlyProbesNewsFirstAndFreshCacheAvoidsDuplicateCalls(){
        var gateway=mock(NormalizedMarketGateway.class);when(gateway.intradayCandidateCheck(anyString(),anyString(),any(),eq(clock))).thenAnswer(call->evidence(call.getArgument(1),"PASSED"));
        var screen=new IntradayCandidateScreen(gateway,json,clock);assertThat(screen.checkPending(candidates(6),()->true,3)).isEqualTo(3);
        var order=inOrder(gateway);order.verify(gateway).intradayCandidateCheck(eq("STOCK4"),eq("BUY"),any(),eq(clock));order.verify(gateway).intradayCandidateCheck(eq("STOCK0"),eq("BUY"),any(),eq(clock));order.verify(gateway).intradayCandidateCheck(eq("STOCK1"),eq("BUY"),any(),eq(clock));
        assertThat(screen.approved("STOCK4","BUY")).isTrue();assertThat(screen.approved("STOCK4","SELL")).isFalse();
        var request=json.createObjectNode();screen.attach(request);assertThat(request.path("requireIntraday").asBoolean()).isTrue();assertThat(request.path("intradayChecks").size()).isEqualTo(3);
        clearInvocations(gateway);assertThat(screen.checkPending(candidates(2),()->true,3)).isZero();verifyNoInteractions(gateway);
        assertThat(screen.checkPending(candidates(6),()->false,3)).isZero();verifyNoInteractions(gateway);
    }
    @Test void rejectedAndUnavailableEvidenceNeverApprovesOrRetriesWithinItsLifetime(){
        var gateway=mock(NormalizedMarketGateway.class);when(gateway.intradayCandidateCheck(anyString(),anyString(),any(),eq(clock))).thenReturn(evidence("BUY","REJECTED"));
        var screen=new IntradayCandidateScreen(gateway,json,clock);screen.checkPending(candidates(1),()->true,3);assertThat(screen.approved("STOCK0","BUY")).isFalse();clearInvocations(gateway);
        assertThat(screen.checkPending(candidates(1),()->true,3)).isZero();verifyNoInteractions(gateway);
    }
    @Test void actualGatewayChecksInstrumentPermissionsAndUsesOnlyMarginEndpoint(){
        var market=mock(GrowwMarketDataService.class);var broker=mock(GrowwApiClient.class);
        when(market.instrumentsCsv()).thenReturn("exchange,segment,trading_symbol,groww_symbol,series,buy_allowed,sell_allowed,is_reserved\nNSE,CASH,ITC,NSE-ITC,EQ,1,1,0\nNSE,CASH,LOCKED,NSE-LOCKED,EQ,1,0,0\n");
        when(broker.post(eq("/v1/margins/detail/orders?segment=CASH"),any())).thenReturn(json.createObjectNode().put("total_requirement",20).put("cash_mis_margin_required",20));
        var gateway=new NormalizedMarketGateway(market,broker,json);var check=gateway.intradayCandidateCheck("ITC","SELL",new BigDecimal("100"),clock);
        assertThat(check.path("status").asText()).isEqualTo("PASSED");assertThat(check.path("indicativeLeverage").decimalValue()).isEqualByComparingTo("5");
        verify(broker).post(eq("/v1/margins/detail/orders?segment=CASH"),argThat((com.fasterxml.jackson.databind.JsonNode body)->body.get(0).path("product").asText().equals("MIS")&&body.get(0).path("transaction_type").asText().equals("SELL")&&body.get(0).path("quantity").asInt()==1));
        clearInvocations(broker);assertThat(gateway.intradayCandidateCheck("LOCKED","BUY",BigDecimal.TEN,clock).path("status").asText()).isEqualTo("REJECTED");verifyNoInteractions(broker);
    }
    @Test void providerFailureAndInvalidMarginStayUnconfirmedAndDoNotLeakRawErrors(){
        var market=mock(GrowwMarketDataService.class);var broker=mock(GrowwApiClient.class);when(market.instrumentsCsv()).thenReturn("exchange,segment,trading_symbol,groww_symbol,series,buy_allowed,sell_allowed\nNSE,CASH,ITC,NSE-ITC,EQ,1,1\n");
        var gateway=new NormalizedMarketGateway(market,broker,json);when(broker.post(anyString(),any())).thenThrow(new IllegalStateException("synthetic-private-error"));
        var check=gateway.intradayCandidateCheck("ITC","BUY",BigDecimal.TEN,clock);assertThat(check.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(check.toString()).doesNotContain("private-error");
        doReturn(json.createObjectNode().put("total_requirement",10).put("cash_mis_margin_required",0)).when(broker).post(anyString(),any());
        assertThat(gateway.intradayCandidateCheck("ITC","BUY",BigDecimal.TEN,clock).path("status").asText()).isEqualTo("UNAVAILABLE");
    }
}
