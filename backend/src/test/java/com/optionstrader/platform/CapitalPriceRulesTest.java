package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapitalPriceRulesTest {
    @Test void exactCapitalAndShareBoundariesApplyIndependentlyOfFiveTimesLeverage(){
        var rules=IntradayRules.paperDefaults();rules.validate();
        for(var cap:List.of("2000","9999.99")){
            assertThat(rules.capitalPriceRejection(new BigDecimal(cap),new BigDecimal("199.99"))).isNull();
            assertThat(rules.capitalPriceRejection(new BigDecimal(cap),new BigDecimal("200"))).contains("strictly below INR 200");
        }
        for(var cap:List.of("10000","20000")){
            assertThat(rules.capitalPriceRejection(new BigDecimal(cap),new BigDecimal("499.99"))).isNull();
            assertThat(rules.capitalPriceRejection(new BigDecimal(cap),new BigDecimal("500"))).isNotNull();
        }
        assertThat(rules.capitalPriceRejection(new BigDecimal("20000.01"),new BigDecimal("3000"))).isNull();
        assertThat(rules.capitalPriceRejection(new BigDecimal("20000.01"),new BigDecimal("3000.01"))).isNotNull();
    }
    @Test void legacySavedRulesReceiveTheNewEnabledBandsAndRoundTrip()throws Exception{
        var json=new ObjectMapper();var rules=json.readValue("{\"fallbackEnabled\":true,\"fallbackMaxPrice\":300,\"paperLeverage\":5,\"realMaxLeverage\":3,\"priceBandsEnabled\":false,\"priceBands\":[]}",IntradayRules.class);
        rules.validate();assertThat(rules.capitalPriceFilterEnabled()).isTrue();
        assertThat(rules.capitalPriceRejection(new BigDecimal("2000"),new BigDecimal("1835.8"))).isNotNull();
        assertThat(json.readValue(json.writeValueAsString(rules),IntradayRules.class)).isEqualTo(rules);
    }
    @Test void customRangesValidatedEvenWhenStopAndTargetBandsAreOff(){
        var band=new IntradayRules.CapitalPriceBand(new BigDecimal("10000"),false,new BigDecimal("200"),false);
        var rules=new IntradayRules(false,new BigDecimal("500"),BigDecimal.ONE,false,List.of(),BigDecimal.ONE,true,List.of(band));
        assertThatThrownBy(rules::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("final open-ended");
        var disabled=new IntradayRules(false,new BigDecimal("500"),BigDecimal.ONE,false,List.of(),BigDecimal.ONE,false,IntradayRules.defaultCapitalPriceBands());
        disabled.validate();assertThat(disabled.capitalPriceRejection(new BigDecimal("2000"),new BigDecimal("1835.8"))).isNull();
    }
}
