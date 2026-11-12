package com.optionstrader.platform;

import java.math.BigDecimal;
import java.util.List;

/** Separate paper assumptions and real exposure ceilings. Groww verifies actual MIS margin per order. */
public record IntradayRules(boolean fallbackEnabled, BigDecimal fallbackMaxPrice,
        BigDecimal paperLeverage, boolean priceBandsEnabled, List<PriceBand> priceBands, BigDecimal realMaxLeverage,
        Boolean capitalPriceFilterEnabled, List<CapitalPriceBand> capitalPriceBands) {
    public IntradayRules {
        if(realMaxLeverage==null)realMaxLeverage=BigDecimal.ONE;
        if(capitalPriceFilterEnabled==null)capitalPriceFilterEnabled=true;
        if(capitalPriceBands==null)capitalPriceBands=defaultCapitalPriceBands();
        else capitalPriceBands=List.copyOf(capitalPriceBands);
    }
    public IntradayRules(boolean fallbackEnabled,BigDecimal fallbackMaxPrice,BigDecimal paperLeverage,boolean priceBandsEnabled,List<PriceBand> priceBands,BigDecimal realMaxLeverage){this(fallbackEnabled,fallbackMaxPrice,paperLeverage,priceBandsEnabled,priceBands,realMaxLeverage,true,defaultCapitalPriceBands());}
    public record CapitalPriceBand(BigDecimal capitalLimit, boolean capitalLimitInclusive, BigDecimal maxSharePrice, boolean sharePriceInclusive) {}
    public static List<CapitalPriceBand> defaultCapitalPriceBands(){return List.of(
        new CapitalPriceBand(new BigDecimal("10000"),false,new BigDecimal("200"),false),
        new CapitalPriceBand(new BigDecimal("20000"),true,new BigDecimal("500"),false),
        new CapitalPriceBand(null,false,new BigDecimal("3000"),true));}
    public CapitalPriceBand capitalBand(BigDecimal capital){return capitalPriceBands.stream().filter(b->b.capitalLimit()==null||capital.compareTo(b.capitalLimit())<0||(b.capitalLimitInclusive()&&capital.compareTo(b.capitalLimit())==0)).findFirst().orElseThrow();}
    public String capitalPriceRejection(BigDecimal capital,BigDecimal price){
        if(!capitalPriceFilterEnabled)return null;
        var band=capitalBand(capital);int compare=price.compareTo(band.maxSharePrice());
        if(price.signum()>0&&(compare<0||(band.sharePriceInclusive()&&compare==0)))return null;
        return "Capital share-price limit: cash capital INR "+capital.toPlainString()+" permits shares "+(band.sharePriceInclusive()?"up to":"strictly below")+" INR "+band.maxSharePrice().toPlainString()+"; checked price INR "+price.toPlainString()+". Leverage does not raise this limit.";
    }
    private void validateCapitalBands(){
        if(capitalPriceBands.isEmpty()||capitalPriceBands.size()>10||capitalPriceBands.getLast().capitalLimit()!=null)throw new IllegalArgumentException("Capital price bands need 1–10 ranges and a final open-ended capital range.");
        BigDecimal previous=BigDecimal.ZERO,maximum=new BigDecimal("10000000");
        for(int i=0;i<capitalPriceBands.size();i++){
            var band=capitalPriceBands.get(i);
            if(band.maxSharePrice()==null||band.maxSharePrice().signum()<=0||band.maxSharePrice().compareTo(maximum)>0)throw new IllegalArgumentException("Maximum share prices must be positive and at most INR 10000000.");
            if(band.capitalLimit()==null){if(i!=capitalPriceBands.size()-1)throw new IllegalArgumentException("Only the last capital range may be open-ended.");}
            else{if(band.capitalLimit().compareTo(previous)<=0||band.capitalLimit().compareTo(maximum)>0)throw new IllegalArgumentException("Capital boundaries must increase and be at most INR 10000000.");previous=band.capitalLimit();}
        }
    }
    public IntradayRules(boolean fallbackEnabled,BigDecimal fallbackMaxPrice,BigDecimal paperLeverage,boolean priceBandsEnabled,List<PriceBand> priceBands){this(fallbackEnabled,fallbackMaxPrice,paperLeverage,priceBandsEnabled,priceBands,BigDecimal.ONE);}
    public record PriceBand(BigDecimal belowPrice, BigDecimal stopLossPercent, BigDecimal targetProfitPercent) {}
    public static IntradayRules defaults(){return new IntradayRules(false,new BigDecimal("500"),BigDecimal.ONE,false,List.of());}
    public static IntradayRules paperDefaults(){return new IntradayRules(true,new BigDecimal("500"),new BigDecimal("5"),true,List.of(
        new PriceBand(new BigDecimal("100"),new BigDecimal("0.8"),new BigDecimal("2.4")),
        new PriceBand(new BigDecimal("250"),new BigDecimal("0.6"),new BigDecimal("2.0")),
        new PriceBand(new BigDecimal("500"),new BigDecimal("0.5"),new BigDecimal("1.8")),
        new PriceBand(null,new BigDecimal("0.4"),new BigDecimal("1.6"))));}
    public void validate(){
        validateCapitalBands();
        if(realMaxLeverage.compareTo(BigDecimal.ONE)<0||realMaxLeverage.compareTo(new BigDecimal("5"))>0)throw new IllegalArgumentException("Real intraday exposure ceiling must be 1–5×; Groww margin is checked independently.");
        if(fallbackMaxPrice==null||fallbackMaxPrice.signum()<=0||fallbackMaxPrice.compareTo(new BigDecimal("500"))>0||
           paperLeverage==null||paperLeverage.compareTo(BigDecimal.ONE)<0||paperLeverage.compareTo(new BigDecimal("5"))>0||
           priceBands==null||priceBands.size()>10)throw new IllegalArgumentException("Fallback ceiling must be at most ₹500 and paper leverage must be 1–5×.");
        if(!priceBandsEnabled)return;
        if(priceBands.isEmpty()||priceBands.getLast().belowPrice()!=null)throw new IllegalArgumentException("A final open-ended price band is required.");
        BigDecimal previous=BigDecimal.ZERO;
        for(int i=0;i<priceBands.size();i++){
            var band=priceBands.get(i);
            if(band==null||band.stopLossPercent()==null||band.targetProfitPercent()==null||
               band.stopLossPercent().signum()<=0||band.stopLossPercent().compareTo(new BigDecimal("5"))>0||
               band.targetProfitPercent().compareTo(new BigDecimal("20"))>0||
               band.targetProfitPercent().compareTo(band.stopLossPercent().multiply(new BigDecimal("1.5")))<0)
                throw new IllegalArgumentException("Price-band stops must be 0–5%, targets at most 20%, and target/stop at least 1.5.");
            if(band.belowPrice()==null){if(i!=priceBands.size()-1)throw new IllegalArgumentException("Only the last price band may be open-ended.");}
            else{if(band.belowPrice().compareTo(previous)<=0||band.belowPrice().compareTo(new BigDecimal("10000000"))>0)throw new IllegalArgumentException("Price boundaries must increase.");previous=band.belowPrice();}
        }
    }
    public PriceBand band(BigDecimal price){
        if(!priceBandsEnabled)return null;
        return priceBands.stream().filter(b->b.belowPrice()==null||price.compareTo(b.belowPrice())<0).findFirst().orElse(null);
    }
}
