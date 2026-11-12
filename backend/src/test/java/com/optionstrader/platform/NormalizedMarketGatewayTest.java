package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.groww.GrowwApiClient;
import com.optionstrader.groww.GrowwMarketDataService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class NormalizedMarketGatewayTest {
    @Test void broaderPriceScreenUsesBatchesOfFiftyAndNeverFabricatesMissingPrices(){
        var json=new ObjectMapper();var market=mock(GrowwMarketDataService.class);var symbols=new java.util.ArrayList<String>();for(int i=0;i<121;i++)symbols.add("TEST"+i);
        when(market.ltp(eq("CASH"),anyString())).thenAnswer(call->{var value=json.createObjectNode();for(String symbol:((String)call.getArgument(1)).split(","))if(!symbol.equals("NSE_TEST120"))value.put(symbol,199.99);return value;});
        var gateway=new NormalizedMarketGateway(market,mock(GrowwApiClient.class),json);var prices=gateway.lastPrices(symbols);
        var capture=org.mockito.ArgumentCaptor.forClass(String.class);verify(market,times(3)).ltp(eq("CASH"),capture.capture());
        assertThat(capture.getAllValues()).allMatch(s->s.split(",").length<=50);assertThat(prices).hasSize(120).doesNotContainKey("TEST120");
        assertThat(prices.get("TEST0")).isEqualByComparingTo("199.99");verify(market,never()).quote(anyString(),anyString(),anyString());
    }

    @Test void readOnlyShortlistDepthUsesCacheAndOldSnapshotsAreMarkedStale() throws Exception{
        var json=new ObjectMapper();var market=mock(GrowwMarketDataService.class);var calendar=mock(SessionCalendar.class);
        when(calendar.status(any())).thenReturn(java.util.Map.of("verified",true,"sessionOpen",true));
        when(market.instrumentsCsv()).thenReturn("exchange,segment,trading_symbol,groww_symbol\nNSE,CASH,TEST,NSE-TEST\n");
        when(market.quote("NSE","CASH","TEST")).thenReturn(json.readTree("{\"last_price\":100,\"total_buy_quantity\":60,\"total_sell_quantity\":40}"));
        var gateway=new NormalizedMarketGateway(market,mock(GrowwApiClient.class),json);var clock=java.time.Clock.systemUTC();
        gateway.quote("TEST");clearInvocations(market);
        var view=gateway.marketDepth("TEST",clock,calendar,false);assertThat(view.path("buyPercent").asInt()).isEqualTo(60);assertThat(view.path("stale").asBoolean()).isFalse();
        view.put("buyPercent",1);assertThat(gateway.marketDepth("TEST",clock,calendar,false).path("buyPercent").asInt()).isEqualTo(60);
        assertThat(gateway.marketDepth("TEST",java.time.Clock.offset(clock,java.time.Duration.ofSeconds(30)),calendar,false).path("stale").asBoolean()).isTrue();
        assertThat(gateway.marketDepth("OTHER",clock,calendar,false).path("available").asBoolean()).isFalse();verifyNoInteractions(market);
    }

    @Test void secondAndMillisecondTradeTimesAndActualDepthAreNormalized() throws Exception{
        var json=new ObjectMapper();var market=mock(GrowwMarketDataService.class);
        when(market.instrumentsCsv()).thenReturn("exchange,segment,trading_symbol,groww_symbol,series,buy_allowed,sell_allowed\nNSE,CASH,TEST,NSE-TEST,EQ,1,1\n");
        var payload=json.readTree("{\"last_price\":100,\"last_trade_time\":1793597492,\"depth\":{\"buy\":[{\"price\":99.98,\"quantity\":100},{\"price\":99.99,\"quantity\":20},{\"price\":101,\"quantity\":0}],\"sell\":[{\"price\":100.02,\"quantity\":30},{\"price\":100.01,\"quantity\":40}]}}");
        when(market.quote("NSE","CASH","TEST")).thenReturn(payload);
        var gateway=new NormalizedMarketGateway(market,mock(GrowwApiClient.class),json);var quote=gateway.quote("TEST");
        assertThat(quote.timestamp()).isEqualTo(Instant.ofEpochSecond(1793597492L));
        assertThat(quote.bid()).isEqualByComparingTo("99.99");assertThat(quote.bidQuantity()).isEqualTo(20);
        assertThat(quote.ask()).isEqualByComparingTo("100.01");assertThat(quote.askQuantity()).isEqualTo(40);
        assertThat(NormalizedMarketGateway.quoteTime(json.readTree("1793597492000"))).isEqualTo(quote.timestamp());
        assertThat(NormalizedMarketGateway.quoteTime(json.readTree("12345"))).isNull();
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove("depth");quote=gateway.quote("TEST");
        assertThat(quote.bid()).isNull();assertThat(quote.ask()).isNull();assertThat(quote.askQuantity()).isZero();
    }
    @Test void allAvailableCandlesAreReturnedWithoutTailTruncation(){
        var json=new ObjectMapper();var market=mock(GrowwMarketDataService.class);
        when(market.instrumentsCsv()).thenReturn("exchange,segment,trading_symbol,groww_symbol\nNSE,CASH,TEST,NSE-TEST\n");
        var request=new GrowwMarketDataService.HistoricalRequest("NSE","CASH","NSE-TEST","2026-10-14 11:20:00","2026-10-28 11:20:00","5minute",null,null);
        when(market.parseHistoricalRequest(any())).thenReturn(request);
        var envelope=json.createObjectNode();when(market.fetchHistoricalCandles(request)).thenReturn(envelope);
        var rows=new java.util.ArrayList<java.util.Map<String,Object>>();for(int i=0;i<750;i++)rows.add(java.util.Map.of("close",i+1));
        var input=mock(GrowwMarketDataService.AnalysisInput.class);
        when(input.payload()).thenReturn(java.util.Map.of("bars",rows,"market_data",java.util.Map.of()));
        when(market.buildAnalysisInput(request,envelope)).thenReturn(input);
        var gateway=new NormalizedMarketGateway(market,mock(GrowwApiClient.class),json);
        var result=gateway.availableCandles("TEST",java.time.Clock.fixed(Instant.parse("2026-10-28T05:50:00Z"),java.time.ZoneOffset.UTC));
        assertThat((java.util.List<?>)result.get("bars")).hasSize(750);
        assertThat(result.get("historyTruncated")).isEqualTo(false);assertThat(result.get("sentCandleCount")).isEqualTo(750);
    }
    @Test void quoteUsesBrokerTradeTimeAndVerifiedInstrumentFields() throws Exception{
        var json=new ObjectMapper();var market=mock(GrowwMarketDataService.class);
        when(market.instrumentsCsv()).thenReturn("""
            exchange,segment,trading_symbol,groww_symbol,series,buy_allowed,is_reserved,tick_size,name
            NSE,CASH,TEST,NSE-TEST,EQ,1,0,0.01,"Example, Limited"
            """);
        var payload=json.readTree("""
            {"last_price":100,"bid_price":99.99,"offer_price":100,"last_trade_time":1700000000000,
             "volume":100000,"bid_quantity":500,"offer_quantity":500}
            """);
        when(market.quote("NSE","CASH","TEST")).thenReturn(payload);
        var gateway=new NormalizedMarketGateway(market,mock(GrowwApiClient.class),json);
        var quote=gateway.quote("TEST");
        assertThat(quote.timestamp()).isEqualTo(Instant.ofEpochMilli(1700000000000L));
        assertThat(quote.equity()).isTrue();assertThat(quote.buyAllowed()).isTrue();
        assertThat(quote.tickSize()).isEqualByComparingTo("0.01");
        ((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove("last_trade_time");
        assertThat(gateway.quote("TEST").timestamp()).isNull();
        assertThatThrownBy(()->gateway.resolve("UNRESOLVED")).isInstanceOf(IllegalArgumentException.class);
    }
}
