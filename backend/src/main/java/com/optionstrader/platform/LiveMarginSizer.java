package com.optionstrader.platform;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;

/** Read-only margin estimates. No order placement, leverage entitlement or retry of an unknown order. */
public final class LiveMarginSizer {
    private final GrowwOrderAdapter broker;private final Clock clock;
    public LiveMarginSizer(GrowwOrderAdapter broker,Clock clock){this.broker=broker;this.clock=clock;}
    public record Approval(int quantity,BigDecimal required,BigDecimal funds,BigDecimal costReserve,BigDecimal budget,
                           BigDecimal maxLeverage,BigDecimal positionValue,Instant checkedAt){
        public void record(ObjectNode row){row.put("marginRequired",required).put("marginFundsAvailable",funds).put("marginCostReserve",costReserve)
            .put("marginBudget",budget).put("maxIntradayLeverage",maxLeverage).put("positionValue",positionValue).put("marginCheckedAt",checkedAt.toString())
            .put("brokerMarginVerified",true).put("brokerEffectiveLeverage",positionValue.divide(required,4,RoundingMode.DOWN));}
    }
    private static BigDecimal numeric(JsonNode row,String key){
        if(row==null||!row.path(key).isNumber())throw new IllegalStateException("Broker margin numeric field unavailable: "+key);
        return row.path(key).decimalValue();
    }
    private void valid(int proposed,BigDecimal price,BigDecimal budget,BigDecimal max,BigDecimal costs){
        if(proposed<1||price==null||price.signum()<=0||budget==null||budget.signum()<=0||max==null||max.compareTo(BigDecimal.ONE)<0||max.compareTo(new BigDecimal("5"))>0||costs==null||costs.signum()<0||costs.compareTo(new BigDecimal("100"))>0)
            throw new IllegalArgumentException("Valid whole-share quantity, price, cash allocation, 1–5× ceiling and cost reserve are required.");
    }
    private Approval estimate(String symbol,String side,int quantity,BigDecimal price,BigDecimal budget,BigDecimal max,BigDecimal bps,BigDecimal available){
        if(!symbol.matches("[A-Z0-9&.-]{1,32}")||!java.util.Set.of("BUY","SELL").contains(side))throw new IllegalArgumentException("Verified symbol and entry side required.");
        var request=new ObjectMapper().createObjectNode().put("trading_symbol",symbol).put("exchange","NSE").put("segment","CASH").put("product","MIS").put("order_type","LIMIT").put("transaction_type",side).put("quantity",quantity).put("price",price);
        var response=broker.margin(request);BigDecimal required=numeric(response,"total_requirement");
        if(required.signum()<=0)throw new IllegalStateException("Broker returned no positive MIS margin requirement.");
        BigDecimal notional=price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal reserve=notional.multiply(bps).divide(new BigDecimal("10000"),2,RoundingMode.UP);
        return new Approval(quantity,required,available,reserve,budget,max,notional,clock.instant());
    }
    private boolean affordable(Approval a){return a.positionValue().compareTo(a.budget().multiply(a.maxLeverage()))<=0&&a.required().add(a.costReserve()).compareTo(a.budget().min(a.funds()))<=0;}
    private BigDecimal funds(){BigDecimal available=numeric(broker.funds().path("equity_margin_details"),"mis_balance_available");if(available.signum()<=0)throw new IllegalStateException("No available Groww MIS funds.");return available;}
    public Approval select(String symbol,String side,int proposed,BigDecimal price,BigDecimal budget,BigDecimal max,BigDecimal costs,boolean resize){
        valid(proposed,price,budget,max,costs);BigDecimal funds=funds();
        int capped=budget.multiply(max).divide(price,0,RoundingMode.DOWN).min(BigDecimal.valueOf(proposed)).intValueExact();
        if(capped<1)throw new IllegalStateException("Cash allocation and exposure ceiling cannot support one share.");
        if(!resize&&capped<proposed)throw new IllegalStateException("Requested quantity exceeds configured real exposure ceiling.");
        Approval full=estimate(symbol,side,capped,price,budget,max,costs,funds);if(affordable(full))return full;
        if(!resize)throw new IllegalStateException("Broker MIS margin plus cost reserve exceeds cash allocation or available funds.");
        Approval best=estimate(symbol,side,1,price,budget,max,costs,funds);if(!affordable(best))throw new IllegalStateException("Broker MIS margin and cost reserve cannot support one share.");
        int low=2,high=capped-1;
        // Bounded, verified probes: never approve a quantity based on a proportional-margin assumption.
        for(int probe=0;probe<8&&low<=high;probe++){
            int middle=low+(high-low)/2;var candidate=estimate(symbol,side,middle,price,budget,max,costs,funds);
            if(affordable(candidate)){best=candidate;low=middle+1;}else high=middle-1;
        }
        return best;
    }
    public Approval verify(String symbol,String side,int quantity,BigDecimal price,BigDecimal budget,BigDecimal max,BigDecimal costs){return select(symbol,side,quantity,price,budget,max,costs,false);}
}
