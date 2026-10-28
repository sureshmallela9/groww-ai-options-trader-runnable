package com.optionstrader.groww;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
class GrowwReadBudgetTest {
    @Test void normalReadsReserveCapacityForRiskQuotesButCannotExceedTheSharedCap(){
        AtomicLong time=new AtomicLong();var budget=new GrowwReadBudget(time::get);
        for(int i=0;i<8;i++)budget.acquire("/v1/live-data/quote");
        assertThatThrownBy(()->budget.acquire("/v1/live-data/quote")).isInstanceOf(GrowwReadBudget.LimitExceeded.class);
        GrowwReadBudget.riskQuote(()->{budget.acquire("/v1/live-data/quote");budget.acquire("/v1/live-data/quote");return null;});
        assertThatThrownBy(()->GrowwReadBudget.riskQuote(()->{budget.acquire("/v1/live-data/quote");return null;}))
            .isInstanceOf(GrowwReadBudget.LimitExceeded.class);
        time.set(1_000_000_000L);budget.acquire("/v1/live-data/quote");
    }
    @Test void minuteBudgetAppliesAcrossQuoteAndOhlcEndpoints(){
        AtomicLong time=new AtomicLong();var budget=new GrowwReadBudget(time::get);
        for(int i=0;i<220;i++){
            time.set(i/8*1_000_000_000L);
            budget.acquire(i%2==0?"/v1/live-data/quote":"/v1/live-data/ohlc");
        }
        time.set(30_000_000_000L);
        assertThatThrownBy(()->budget.acquire("/v1/live-data/ltp")).isInstanceOf(GrowwReadBudget.LimitExceeded.class);
        GrowwReadBudget.riskQuote(()->{budget.acquire("/v1/live-data/quote");return null;});
    }
}
