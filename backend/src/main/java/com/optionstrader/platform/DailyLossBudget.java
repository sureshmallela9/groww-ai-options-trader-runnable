package com.optionstrader.platform;

import java.math.BigDecimal;

/** Daily loss percentages use the selected mode's configured exposure ceiling, not its cash balance. */
public record DailyLossBudget(BigDecimal cashAllocation,BigDecimal leverage,BigDecimal exposureBase,BigDecimal limit) {
    public BigDecimal perTradeRiskLimit(PaperPlatformService.Policy policy){return exposureBase.multiply(policy.riskPerTradePercent()).divide(new BigDecimal("100"));}
    public static DailyLossBudget forMode(PaperPlatformService.Policy policy,IntradayRules rules,String mode){
        BigDecimal leverage=switch(mode){case "PAPER"->rules.paperLeverage();case "REAL"->rules.realMaxLeverage();default->throw new IllegalArgumentException("Daily loss budget requires PAPER or REAL mode.");};
        BigDecimal cash=policy.capital().min(policy.maxPositionValue());
        BigDecimal base=cash.multiply(leverage);
        return new DailyLossBudget(cash,leverage,base,base.multiply(policy.dailyLossPercent()).divide(new BigDecimal("100")));
    }
}
