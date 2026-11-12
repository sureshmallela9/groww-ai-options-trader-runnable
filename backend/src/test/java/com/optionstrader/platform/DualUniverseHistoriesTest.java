package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DualUniverseHistoriesTest {
    static class MovingClock extends Clock {
        volatile Instant value=Instant.parse("2026-11-09T05:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
    }
    final ObjectMapper json=new ObjectMapper();
    ObjectNode members(int count,String prefix){var value=json.createObjectNode();for(int i=0;i<count;i++)value.withArray("constituents").addObject().put("symbol",prefix+i);return value;}
    ObjectNode risk(){var risk=json.createObjectNode();risk.set("policy",json.valueToTree(PaperPlatformService.Policy.defaults()));risk.withObject("policy").put("capital",2000);risk.set("intraday",json.valueToTree(IntradayRules.paperDefaults()));return risk;}
    ObjectNode extended(ObjectNode primary){var value=primary.deepCopy();for(int i=0;i<450;i++)value.withArray("constituents").addObject().put("symbol","OTHER"+i);return value;}
    @Test void dedicatedLanesLoadDisjointGroupsAndOtherHistoriesAreBounded()throws Exception{
        var market=mock(NormalizedMarketGateway.class);var primary=members(50,"TEST");var extended=extended(primary);var clock=Clock.fixed(Instant.parse("2026-11-09T05:00:00Z"),ZoneOffset.UTC);
        var prices=new HashMap<String,BigDecimal>();for(int i=0;i<30;i++)prices.put("OTHER"+i,new BigDecimal("100"));prices.put("OTHER200",new BigDecimal("200"));
        when(market.lastPrices(anyList())).thenReturn(prices);var threads=new ConcurrentHashMap<String,Set<String>>();var both=new CountDownLatch(2);
        when(market.availableCandles(anyString(),eq(clock))).thenAnswer(call->{String symbol=call.getArgument(0);String thread=Thread.currentThread().getName();threads.computeIfAbsent(thread,k->ConcurrentHashMap.newKeySet()).add(symbol);
            if("NIFTY".equals(symbol)||"OTHER0".equals(symbol)){both.countDown();if(!both.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Lanes did not overlap.");}
            return Map.of("bars",List.of(Map.of("close",100)));});
        try(var lanes=new DualUniverseHistories(market,json,clock)){
            var batch=lanes.load(primary.path("constituents"),extended.path("constituents"),Set.of(),true,risk(),new ConcurrentHashMap<>(),new ConcurrentHashMap<>(),()->true);
            assertThat(batch.histories()).hasSize(63);assertThat(batch.coverage().path("otherHistories").asInt()).isEqualTo(12);
            assertThat(threads.get("automatic-nifty50-history")).hasSize(51).allMatch(s->s.equals("NIFTY")||s.startsWith("TEST"));
            assertThat(threads.get("automatic-other-stock-history")).hasSize(12).allMatch(s->s.startsWith("OTHER"));
            assertThat(batch.histories()).doesNotContainKey("OTHER200");assertThat(batch.contextSymbols()).hasSize(50);
        }
    }
    @Test void stopDiscardsResultsAndReadersNeverPublishIntoCoordinatorCaches(){
        var market=mock(NormalizedMarketGateway.class);var primary=members(50,"TEST");var clock=Clock.systemUTC();var cache=new ConcurrentHashMap<String,com.fasterxml.jackson.databind.JsonNode>();var buckets=new ConcurrentHashMap<String,Long>();
        when(market.lastPrices(anyList())).thenReturn(Map.of("OTHER0",new BigDecimal("100")));
        when(market.availableCandles(anyString(),eq(clock))).thenThrow(new IllegalArgumentException("No completed candles"));
        try(var lanes=new DualUniverseHistories(market,json,clock)){
            assertThatThrownBy(()->lanes.load(primary.path("constituents"),extended(primary).path("constituents"),Set.of(),true,risk(),cache,buckets,()->true)).isInstanceOf(DualUniverseHistories.HistoryFailure.class);
            assertThat(cache).isEmpty();assertThat(buckets).isEmpty();
        }
    }
    @Test void missingOrDuplicateMaintainedMembershipCannotStartTheWorkers(){
        var market=mock(NormalizedMarketGateway.class);var primary=members(50,"TEST");
        try(var lanes=new DualUniverseHistories(market,json,Clock.systemUTC())){
            assertThatThrownBy(()->lanes.load(primary.path("constituents"),primary.path("constituents"),Set.of(),true,risk(),Map.of(),Map.of(),()->true)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(market);
        }
    }
    @Test void newsStockOutsidePriceBandRetainsEvidenceButDoesNotExpandTheBatch(){
        var market=mock(NormalizedMarketGateway.class);var primary=members(50,"TEST");var clock=Clock.systemUTC();var prices=new HashMap<String,BigDecimal>();for(int i=0;i<30;i++)prices.put("OTHER"+i,new BigDecimal("100"));prices.put("OTHER449",new BigDecimal("2000"));
        when(market.lastPrices(anyList())).thenReturn(prices);when(market.availableCandles(anyString(),eq(clock))).thenReturn(Map.of("bars",List.of(Map.of("close",100))));
        try(var lanes=new DualUniverseHistories(market,json,clock)){
            var batch=lanes.load(primary.path("constituents"),extended(primary).path("constituents"),Set.of("OTHER449"),true,risk(),Map.of(),Map.of(),()->true);
            assertThat(batch.histories()).containsKey("OTHER449").hasSize(63);assertThat(batch.coverage().path("otherAffordableStocks").asInt()).isEqualTo(30);
        }
    }
    @Test void newsSlotsDoNotStarveTheRemainingAffordableStocksAcrossRotation(){
        var market=mock(NormalizedMarketGateway.class);var primary=members(50,"TEST");var clock=new MovingClock();var prices=new HashMap<String,BigDecimal>();
        for(int i=0;i<12;i++)prices.put("OTHER"+i,new BigDecimal("100"));
        var news=Set.of("OTHER447","OTHER448","OTHER449");news.forEach(s->prices.put(s,new BigDecimal("2000")));
        when(market.lastPrices(anyList())).thenReturn(prices);when(market.availableCandles(anyString(),eq(clock))).thenReturn(Map.of("bars",List.of(Map.of("close",100))));
        try(var lanes=new DualUniverseHistories(market,json,clock)){
            var first=lanes.load(primary.path("constituents"),extended(primary).path("constituents"),news,true,risk(),Map.of(),Map.of(),()->true);
            clock.value=clock.value.plusSeconds(60);
            var second=lanes.load(primary.path("constituents"),extended(primary).path("constituents"),news,true,risk(),Map.of(),Map.of(),()->true);
            var covered=new HashSet<String>();covered.addAll(first.histories().keySet());covered.addAll(second.histories().keySet());covered.removeAll(news);covered.removeIf(s->!s.startsWith("OTHER"));
            assertThat(covered).hasSize(12);assertThat(first.coverage().path("otherHistories").asInt()).isEqualTo(12);assertThat(second.coverage().path("otherHistories").asInt()).isEqualTo(12);
            assertThat(second.coverage().path("otherSymbols")).isNotEqualTo(first.coverage().path("otherSymbols"));
        }
    }
}
