package com.optionstrader.platform;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
class DailyLossBudgetTest {
    final ObjectMapper json=new ObjectMapper();
    PaperPlatformService.Policy policy(int cash,int allocation,String percent)throws Exception{
        var value=json.<ObjectNode>valueToTree(PaperPlatformService.Policy.defaults());
        value.put("capital",cash).put("maxPositionValue",allocation).put("dailyLossPercent",new BigDecimal(percent));
        return json.treeToValue(value,PaperPlatformService.Policy.class);
    }
    IntradayRules rules(String paper,String real){return new IntradayRules(false,new BigDecimal("500"),new BigDecimal(paper),false,List.of(),new BigDecimal(real));}
    @Test void requested1800Times5At2PercentIs180()throws Exception{
        for(String mode:List.of("PAPER","REAL")){
            var result=DailyLossBudget.forMode(policy(1800,1800,"2"),rules("5","5"),mode);
            assertThat(result.cashAllocation()).isEqualByComparingTo("1800");assertThat(result.exposureBase()).isEqualByComparingTo("9000");
            assertThat(result.limit()).isEqualByComparingTo("180");
        }
    }
    @Test void oneThreeAndFiveTimesUseTheirOwnPercentageBases()throws Exception{
        for(String leverage:List.of("1","3","5")){
            var result=DailyLossBudget.forMode(policy(1800,1800,"2"),rules(leverage,leverage),"REAL");
            assertThat(result.limit()).isEqualByComparingTo(new BigDecimal("36").multiply(new BigDecimal(leverage)));
        }
    }
    @Test void realCeilingDoesNotUsePaperLeverage()throws Exception{
        assertThat(DailyLossBudget.forMode(policy(1800,1800,"2"),rules("5","3"),"REAL").limit()).isEqualByComparingTo("108");
        assertThat(DailyLossBudget.forMode(policy(1800,1800,"2"),rules("3","5"),"PAPER").limit()).isEqualByComparingTo("108");
    }
    @Test void tighterCashAllocationReducesDailyExposureBase()throws Exception{
        var result=DailyLossBudget.forMode(policy(1800,1000,"2"),rules("5","5"),"PAPER");
        assertThat(result.exposureBase()).isEqualByComparingTo("5000");assertThat(result.limit()).isEqualByComparingTo("100");
    }
    @Test void fractionPercentAndLeverageRemainExact()throws Exception{
        assertThat(DailyLossBudget.forMode(policy(1800,1800,"0.25"),rules("2.5","1"),"PAPER").limit()).isEqualByComparingTo("11.25");
    }
    @Test void selectedExposureAlsoSetsThePerTradeRiskBudget()throws Exception{
        var policy=policy(1800,1800,"2");
        assertThat(DailyLossBudget.forMode(policy,rules("5","3"),"PAPER").perTradeRiskLimit(policy)).isEqualByComparingTo("45");
        assertThat(DailyLossBudget.forMode(policy,rules("5","3"),"REAL").perTradeRiskLimit(policy)).isEqualByComparingTo("27");
        assertThat(DailyLossBudget.forMode(policy,rules("1","1"),"REAL").perTradeRiskLimit(policy)).isEqualByComparingTo("9");
    }
    @Test void unknownModeNeverFallsBackToALargerBudget()throws Exception{
        var policy=policy(1800,1800,"2");
        assertThatThrownBy(()->DailyLossBudget.forMode(policy,rules("5","5"),"UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }
}
