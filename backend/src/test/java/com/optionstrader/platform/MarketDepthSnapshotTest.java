package com.optionstrader.platform;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MarketDepthSnapshotTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode book(String raw)throws Exception{return MarketDepthSnapshot.from(json,"TEST",json.readTree(raw),Instant.parse("2026-11-06T05:00:00Z"));}
    @Test void totalsDetermineOutstandingOrderShareNotTradeVolume()throws Exception{
        var out=book(""" 
          {"total_buy_quantity":200,"total_sell_quantity":100,"volume":999999,"last_trade_time":1793941200,
           "depth":{"buy":[{"price":100,"quantity":1}],"sell":[{"price":101,"quantity":99}]}}
          """);assertThat(out.path("buyPercent").decimalValue()).isEqualByComparingTo("66.67");assertThat(out.path("sellPercent").decimalValue()).isEqualByComparingTo("33.33");assertThat(out.path("basis").asText()).isEqualTo("TOTAL_ORDER_QUANTITY");assertThat(out.path("isTradeFlow").asBoolean()).isFalse();assertThat(out.path("lastTradeAt").asText()).isNotBlank();
    }
    @Test void visibleDepthFallbackNeverMixesOneMarketTotalWithTheOtherDepth()throws Exception{
        var out=book("""
          {"total_buy_quantity":1000000,"depth":{"buy":[{"price":100,"quantity":10},{"price":99,"quantity":20},{"price":0,"quantity":1000}],"sell":[{"price":101,"quantity":70},{"price":102,"quantity":-3}]}}
          """);assertThat(out.path("basis").asText()).isEqualTo("VISIBLE_DEPTH");assertThat(out.path("buyPercent").asInt()).isEqualTo(30);assertThat(out.path("sellQuantity").asInt()).isEqualTo(70);assertThat(out.path("buy").size()).isEqualTo(2);
    }
    @Test void absentAndZeroTotalsDoNotProduceFabricatedFiftyFifty()throws Exception{
        for(String raw:new String[]{"{}","{\"total_buy_quantity\":0,\"total_sell_quantity\":0}","{\"total_buy_quantity\":-1,\"total_sell_quantity\":10}","{\"depth\":{\"buy\":[{\"price\":100,\"quantity\":10}]}}"}){
            var out=book(raw);assertThat(out.path("available").asBoolean()).isFalse();assertThat(out.has("buyPercent")).isFalse();
        }
        var out=book("{\"total_buy_quantity\":0,\"total_sell_quantity\":5}");assertThat(out.path("available").asBoolean()).isTrue();assertThat(out.path("buyPercent").asInt()).isZero();assertThat(out.path("sellPercent").asInt()).isEqualTo(100);
    }
}
