package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.groww.GrowwApiClient;
import com.optionstrader.groww.GrowwMarketDataService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class NormalizedMarketGatewayTest {
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
