package com.optionstrader.platform;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;

/** Descriptive outstanding-order balance, never a trade-flow percentage or an execution signal. */
public final class MarketDepthSnapshot {
    private MarketDepthSnapshot(){}
    private static BigDecimal quantity(JsonNode value){return value.isNumber()&&value.decimalValue().signum()>=0?value.decimalValue():null;}
    public static ObjectNode from(ObjectMapper json,String symbol,JsonNode quote,Instant received){
        var out=json.createObjectNode().put("symbol",symbol).put("receivedAt",received.toString()).put("provider","GROWW").put("isTradeFlow",false).put("isForecast",false);
        Instant traded=NormalizedMarketGateway.quoteTime(quote.path("last_trade_time"));if(traded!=null)out.put("lastTradeAt",traded.toString());
        for(String key:new String[]{"last_price","day_change","day_change_perc"})if(quote.path(key).isNumber())out.set(key,quote.get(key));
        var buy=out.putArray("buy");var sell=out.putArray("sell");
        for(String side:new String[]{"buy","sell"})for(var level:quote.path("depth").path(side)){
            BigDecimal amount=quantity(level.path("quantity"));if(amount==null||!level.path("price").isNumber()||level.path("price").decimalValue().signum()<=0)continue;
            ((ArrayNode)out.path(side)).addObject().put("price",level.path("price").decimalValue()).put("quantity",amount);
        }
        BigDecimal b=quantity(quote.path("total_buy_quantity")),s=quantity(quote.path("total_sell_quantity"));String basis="TOTAL_ORDER_QUANTITY";
        if(b==null||s==null){
            basis="VISIBLE_DEPTH";b=null;s=null;
            if(!buy.isEmpty()&&!sell.isEmpty()){
                b=BigDecimal.ZERO;s=BigDecimal.ZERO;for(var level:buy)b=b.add(level.path("quantity").decimalValue());for(var level:sell)s=s.add(level.path("quantity").decimalValue());
            }
        }
        out.put("basis",basis);
        if(b==null||s==null||b.add(s).signum()<=0)return out.put("available",false).put("message","Buy/sell order quantities are unavailable or have no positive total.");
        BigDecimal pct=b.multiply(new BigDecimal("100")).divide(b.add(s),2,RoundingMode.HALF_UP);
        return out.put("available",true).put("buyQuantity",b).put("sellQuantity",s).put("buyPercent",pct).put("sellPercent",new BigDecimal("100").subtract(pct))
            .put("message","TOTAL_ORDER_QUANTITY".equals(basis)?"Share of outstanding buy and sell order quantities.":"Share of supplied visible depth only; full market totals were unavailable.");
    }
}
