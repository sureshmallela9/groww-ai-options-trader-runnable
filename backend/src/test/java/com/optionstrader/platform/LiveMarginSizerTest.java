package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.time.*;
import org.junit.jupiter.api.*;
class LiveMarginSizerTest {
    final ObjectMapper json=new ObjectMapper();GrowwOrderAdapter broker;LiveMarginSizer sizer;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-06T05:00:00Z"),ZoneOffset.UTC);
    BigDecimal n(String v){return new BigDecimal(v);}
    @BeforeEach void setup(){broker=mock(GrowwOrderAdapter.class);sizer=new LiveMarginSizer(broker,clock);funds("10000");margin("5");}
    void funds(String amount){when(broker.funds()).thenReturn(json.createObjectNode().set("equity_margin_details",json.createObjectNode().put("mis_balance_available",n(amount))));}
    void margin(String multiple){doAnswer(call->{var order=(JsonNode)call.getArgument(0);return json.createObjectNode().put("total_requirement",order.path("price").decimalValue().multiply(order.path("quantity").decimalValue()).divide(n(multiple),2,RoundingMode.UP));}).when(broker).margin(any());}
    LiveMarginSizer.Approval select(String ceiling,String costs){return sizer.select("TEST","BUY",500,n("100"),n("10000"),n(ceiling),n(costs),true);}
    @Test void fiveTimesIsOnlyAllowedWhenBrokerMarginAndCashBothSupportIt(){var a=select("5","0");assertThat(a.quantity()).isEqualTo(500);assertThat(a.required()).isEqualByComparingTo("10000");assertThat(a.positionValue()).isEqualByComparingTo("50000");verify(broker,never()).create(any());}
    @Test void threeTimesBrokerMarginShrinksTheFiveTimesProposalToAnActuallyQuotedQuantity(){margin("3");var a=select("5","0");assertThat(a.quantity()).isBetween(290,300);assertThat(a.required()).isLessThanOrEqualTo(n("10000"));verify(broker,atMost(10)).margin(any());verify(broker).margin(argThat(q->q.path("quantity").asInt()==a.quantity()));verify(broker,never()).create(any());}
    @Test void uiThreeTimesCapWinsEvenWhenBrokerOffersFive(){assertThat(select("3","0").quantity()).isEqualTo(300);assertThat(select("1","0").quantity()).isEqualTo(100);}
    @Test void availableFundsAndCostsConstrainQuantityAndNeverIncreaseTheAiProposal(){funds("2000");var a=select("5","20");assertThat(a.quantity()).isLessThan(100);assertThat(a.required().add(a.costReserve())).isLessThanOrEqualTo(n("2000"));assertThat(sizer.select("TEST","SELL",3,n("100"),n("10000"),n("5"),n("0"),true).quantity()).isEqualTo(3);}
    @Test void exactManualQuantitiesAreRejectedRatherThanSilentlyChanged(){margin("3");assertThatThrownBy(()->sizer.select("TEST","SELL",500,n("100"),n("10000"),n("5"),n("0"),false)).hasMessageContaining("margin");}
    @Test void missingZeroAndNegativeBrokerMarginFailClosedWithoutOrders(){for(String amount:new String[]{"0","-1"}){doReturn(json.createObjectNode().put("total_requirement",n(amount))).when(broker).margin(any());assertThatThrownBy(()->select("5","0")).hasMessageContaining("positive");}doReturn(json.createObjectNode()).when(broker).margin(any());assertThatThrownBy(()->select("5","0")).hasMessageContaining("unavailable");verify(broker,never()).create(any());}
    @Test void unleveragedStockAndInvalidConfigurationRemainBounded(){margin("1");assertThat(select("5","0").quantity()).isBetween(90,100);assertThatThrownBy(()->select("6","0")).isInstanceOf(IllegalArgumentException.class);funds("0");assertThatThrownBy(()->select("5","0")).hasMessageContaining("funds");}
}
