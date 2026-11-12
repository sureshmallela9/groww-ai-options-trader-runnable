package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.groww.GrowwApiClient;
import com.optionstrader.groww.GrowwMarketDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class NormalizedMarketGateway {
    public record QuoteData(String symbol,BigDecimal last,BigDecimal bid,BigDecimal ask,Instant timestamp,
                            boolean verified,boolean buyAllowed,boolean equity,BigDecimal tickSize,
                            BigDecimal dayVolume,int bidQuantity,int askQuantity,boolean sellAllowed) {
        public QuoteData(String symbol,BigDecimal last,BigDecimal bid,BigDecimal ask,Instant timestamp,
                boolean verified,boolean buyAllowed,boolean equity,BigDecimal tickSize,BigDecimal dayVolume,int bidQuantity,int askQuantity){
            this(symbol,last,bid,ask,timestamp,verified,buyAllowed,equity,tickSize,dayVolume,bidQuantity,askQuantity,buyAllowed);
        }
    }
    private final GrowwMarketDataService market;
    private final GrowwApiClient broker;
    private final ObjectMapper json;
    private Map<String,Map<String,String>> instruments=Map.of();
    private Instant refreshed=Instant.EPOCH;
    private long nextRequestNanos;
    private final java.util.concurrent.ConcurrentMap<String,com.fasterxml.jackson.databind.node.ObjectNode> orderDepth=new java.util.concurrent.ConcurrentHashMap<>();
    public NormalizedMarketGateway(GrowwMarketDataService market,GrowwApiClient broker,ObjectMapper json){
        this.market=market;this.broker=broker;this.json=json;
    }
    public synchronized Map<String,String> resolve(String symbol){
        if(symbol==null||!symbol.matches("[A-Z0-9&.-]{1,32}"))throw new IllegalArgumentException("A normalized NSE symbol is required.");
        if(refreshed.plus(Duration.ofHours(6)).isBefore(Instant.now())){
            List<List<String>> rows=parseCsv(market.instrumentsCsv());
            if(rows.size()<2)throw new IllegalStateException("Instrument file is unavailable.");
            var names=rows.get(0);var next=new LinkedHashMap<String,Map<String,String>>();
            for(var row:rows.subList(1,rows.size())){
                var item=new LinkedHashMap<String,String>();
                for(int i=0;i<Math.min(names.size(),row.size());i++)item.put(names.get(i).replace("\ufeff","").trim(),row.get(i).trim());
                if("NSE".equals(item.get("exchange"))&&"CASH".equals(item.get("segment")))
                    next.put(item.get("trading_symbol"),item);
            }
            instruments=next;refreshed=Instant.now();
        }
        var found=instruments.get(symbol);
        if(found==null||found.getOrDefault("groww_symbol","").isBlank())throw new IllegalArgumentException("Symbol was not resolved in Groww's maintained instrument file.");
        return found;
    }
    public QuoteData quote(String symbol){
        var instrument=resolve(symbol);
        pace();
        JsonNode payload=market.quote("NSE","CASH",symbol);
        if(payload.has("payload"))payload=payload.path("payload");
        var last=positive(payload.get("last_price"));
        if(last==null)throw new IllegalStateException("Broker quote has no valid last price.");
        Instant timestamp=quoteTime(payload.path("last_trade_time"));
        orderDepth.put(symbol,MarketDepthSnapshot.from(json,symbol,payload,Instant.now()));
        var bid=bookLevel(payload,"bid_price","bid_quantity","buy");
        var ask=bookLevel(payload,"offer_price","offer_quantity","sell");
        return new QuoteData(symbol,last,bid.price(),ask.price(),
            timestamp,true,truth(instrument.get("buy_allowed"))&&!truth(instrument.get("is_reserved")),
            "EQ".equals(instrument.get("series")),decimal(instrument.get("tick_size"),null),
            positive(payload.get("volume")),bid.quantity(),ask.quantity(),
            truth(instrument.get("sell_allowed"))&&!truth(instrument.get("is_reserved")));
    }
    /** Read-only, one-share MIS preflight. It neither reserves funds nor places an order. */
    public com.fasterxml.jackson.databind.node.ObjectNode intradayCandidateCheck(String symbol,String side,BigDecimal reference,Clock clock){
        if(!java.util.Set.of("BUY","SELL").contains(side)||reference==null||reference.signum()<=0)
            throw new IllegalArgumentException("A valid stock direction and reference price are required.");
        var check=json.createObjectNode().put("source","GROWW").put("exchange","NSE").put("product","MIS").put("side",side)
            .put("checkedAt",clock.instant().toString()).put("validUntil",clock.instant().plusSeconds(60).toString()).put("referencePrice",reference);
        try{
            var instrument=resolve(symbol);
            if(!"EQ".equals(instrument.get("series"))||truth(instrument.get("is_reserved"))||
                !truth(instrument.get("buy_allowed"))||!truth(instrument.get("sell_allowed")))
                return check.put("status","REJECTED").put("reason","Groww instrument permissions do not support both entry and exit for this NSE equity.");
            var order=json.createObjectNode().put("trading_symbol",symbol).put("transaction_type",side).put("quantity",1).put("price",reference)
                .put("order_type","LIMIT").put("product","MIS").put("exchange","NSE");
            var margin=broker.post("/v1/margins/detail/orders?segment=CASH",json.createArrayNode().add(order));
            var required=positive(margin.get("total_requirement"));
            if(required==null||(margin.has("cash_mis_margin_required")&&positive(margin.get("cash_mis_margin_required"))==null))
                return check.put("status","UNAVAILABLE").put("reason","Groww returned no positive MIS margin estimate; intraday suitability is unconfirmed.");
            return check.put("status","PASSED").put("requiredMargin",required)
                .put("indicativeLeverage",reference.divide(required,4,java.math.RoundingMode.DOWN))
                .put("reason","Groww accepted a one-share MIS margin preflight. Exact quantity, fresh margin, funds and risk must be rechecked before entry.");
        }catch(com.optionstrader.groww.GrowwApiClient.BrokerRejection rejected){
            return check.put("status","REJECTED").put("reason","Groww rejected the MIS margin preflight; this stock cannot be selected for entry now.");
        }catch(RuntimeException unavailable){
            return check.put("status","UNAVAILABLE").put("validUntil",clock.instant().plusSeconds(15).toString())
                .put("reason","Groww intraday preflight is unavailable. No eligibility is assumed and no order was placed.");
        }
    }
    public Map<String,Object> quoteResponse(String symbol,SessionCalendar calendar,Clock clock){
        var quote=quote(symbol);var session=calendar.status(clock);var value=new LinkedHashMap<String,Object>();
        value.put("lastPrice",quote.last());value.put("bid",quote.bid());value.put("ask",quote.ask());
        value.put("timestamp",quote.timestamp()==null?null:quote.timestamp().toString());
        value.put("sessionOpen",session.get("sessionOpen"));value.put("calendarVerified",session.get("verified"));
        value.put("instrumentVerified",quote.verified());value.put("adjustmentStatus","UNKNOWN");
        value.put("shortSellingAllowed",quote.sellAllowed()&&quote.equity());
        value.put("volume",quote.dayVolume());
        return Map.of("symbol",symbol,"exchange","NSE","quote",value,"receivedAt",clock.instant().toString());
    }
    public Map<String,Object> candles(String symbol,int count,Clock clock){
        if(count<100||count>500)throw new IllegalArgumentException("lookbackCandles must be 100–500.");
        return candleHistory(symbol,count,clock);
    }
    /** All valid completed provider candles in the declared 14-day window, without tail truncation. */
    public Map<String,Object> availableCandles(String symbol,Clock clock){
        return candleHistory(symbol,0,clock);
    }
    private Map<String,Object> candleHistory(String symbol,int count,Clock clock){
        var instrument=resolve(symbol);
        var now=clock.instant().atZone(ZoneId.of("Asia/Kolkata"));
        var format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        var request=json.createObjectNode();
        request.put("exchange","NSE");request.put("segment","CASH");request.put("groww_symbol",instrument.get("groww_symbol"));
        request.put("candle_interval","5minute");request.put("start_time",now.minusDays(14).format(format));
        request.put("end_time",now.format(format));
        var parsed=market.parseHistoricalRequest(request);
        pace();
        var input=market.buildAnalysisInput(parsed,market.fetchHistoricalCandles(parsed));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)input.payload().get("bars");
        if(count>0&&rows.size()<count)throw new IllegalStateException("Provider returned fewer completed candles than requested; no history is fabricated.");
        @SuppressWarnings("unchecked") var provenance=(Map<String,Object>)input.payload().get("market_data");
        var result=new LinkedHashMap<String,Object>();
        result.putAll(Map.of("symbol",symbol,"exchange","NSE","interval","5m","bars",count==0?rows:rows.subList(rows.size()-count,rows.size()),
            "brokerInstrumentId",instrument.get("groww_symbol"),"completed",true,
            "adjustmentStatus","UNKNOWN","receivedAt",clock.instant().toString(),
            "volumeStatus",provenance.getOrDefault("volume_status","PROVIDED")));
        result.put("windowStart",request.path("start_time").asText());result.put("windowEnd",request.path("end_time").asText());
        result.put("historyWindowDays",14);result.put("availableCandleCount",rows.size());
        result.put("sentCandleCount",count==0?rows.size():count);result.put("historyTruncated",count>0&&rows.size()>count);
        return result;
    }
    public com.fasterxml.jackson.databind.node.ObjectNode marketDepth(String symbol,Clock clock,SessionCalendar calendar,boolean refresh){
        var cached=orderDepth.get(symbol);String failure=null;
        if(refresh&&(cached==null||Instant.parse(cached.path("receivedAt").asText()).plusSeconds(4).isBefore(clock.instant()))){
            try{quote(symbol);cached=orderDepth.get(symbol);}catch(Exception error){failure="Latest order-book request failed. Last available snapshot, if any, may be stale.";}
        }
        var result=cached==null?json.createObjectNode().put("available",false).put("symbol",symbol).put("message","No Groww order-book snapshot has been received."):cached.deepCopy();
        var session=calendar.status(clock);boolean stale=cached==null||!Boolean.TRUE.equals(session.get("verified"))||!Boolean.TRUE.equals(session.get("sessionOpen"));
        if(cached!=null){long age=Duration.between(Instant.parse(cached.path("receivedAt").asText()),clock.instant()).getSeconds();result.put("snapshotAgeSeconds",age);stale|=age>15||age < -2;}
        result.put("stale",stale||failure!=null).put("sessionOpen",Boolean.TRUE.equals(session.get("sessionOpen"))).put("checkedAt",clock.instant().toString());
        if(failure!=null)result.put("warning",failure);return result;
    }
    /** Screening only; HTTP receipt is not an executable quote or a broker margin approval. */
    public Map<String,BigDecimal> lastPrices(List<String> symbols){
        if(symbols.size()>510||new java.util.HashSet<>(symbols).size()!=symbols.size()||symbols.stream().anyMatch(s->s==null||!s.matches("[A-Z0-9&.-]{1,32}")))throw new IllegalArgumentException("A unique maintained stock list is required.");
        var result=new LinkedHashMap<String,BigDecimal>();
        for(int offset=0;offset<symbols.size();offset+=50){
            var batch=symbols.subList(offset,Math.min(offset+50,symbols.size()));pace();
            JsonNode values=market.ltp("CASH",batch.stream().map(s->"NSE_"+s).collect(java.util.stream.Collectors.joining(",")));
            if(values.has("payload"))values=values.path("payload");
            for(String symbol:batch){var price=positive(values.path("NSE_"+symbol));if(price!=null)result.put(symbol,price);}
        }
        return Map.copyOf(result);
    }
    public JsonNode funds(){return broker.get("/v1/margins/detail/user",Map.of());}
    public JsonNode positions(){return market.positions("CASH");}
    private synchronized void pace(){
        long wait=nextRequestNanos-System.nanoTime();
        if(wait>0)try{Thread.sleep(Duration.ofNanos(wait));}catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();throw new IllegalStateException("Market data request interrupted.");
        }
        nextRequestNanos=System.nanoTime()+250_000_000L;
    }
    static Instant quoteTime(JsonNode value){
        if(!value.isIntegralNumber()||!value.canConvertToLong())return null;
        long raw=value.asLong();
        // Live responses can use seconds even though the published schema says milliseconds.
        // Preserve the provider's time; freshness gates still reject stale/future observations.
        if(raw>=946684800L&&raw<=4102444800L)return Instant.ofEpochSecond(raw);
        if(raw>=946684800000L&&raw<=4102444800000L)return Instant.ofEpochMilli(raw);
        return null;
    }
    private record BookLevel(BigDecimal price,int quantity) {}
    private static BookLevel bookLevel(JsonNode payload,String priceField,String quantityField,String side){
        BigDecimal price=positive(payload.get(priceField));int quantity=payload.path(quantityField).asInt(0);
        if(price!=null&&quantity>0)return new BookLevel(price,quantity);
        BookLevel best=null;
        for(var row:payload.path("depth").path(side)){
            var candidate=positive(row.get("price"));int size=row.path("quantity").asInt(0);
            if(candidate==null||size<=0)continue;
            if(price!=null&&candidate.compareTo(price)==0)return new BookLevel(price,size);
            if(best==null||("buy".equals(side)?candidate.compareTo(best.price())>0:candidate.compareTo(best.price())<0))best=new BookLevel(candidate,size);
        }
        return price==null&&best!=null?best:new BookLevel(price,Math.max(0,quantity));
    }
    static boolean truth(String value){return "1".equals(value)||"true".equalsIgnoreCase(value);}
    static BigDecimal decimal(String value,BigDecimal fallback){try{return new BigDecimal(value);}catch(Exception bad){return fallback;}}
    static BigDecimal positive(JsonNode value){
        return value!=null&&value.isNumber()&&value.decimalValue().signum()>0?value.decimalValue():null;
    }
    static List<List<String>> parseCsv(String text){
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++){
            char ch=text.charAt(i);
            if(ch=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}
            else if(ch==','&&!quoted){row.add(cell.toString());cell.setLength(0);}
            else if((ch=='\r'||ch=='\n')&&!quoted){if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row.add(cell.toString());cell.setLength(0);if(row.size()>1)rows.add(row);row=new ArrayList<>();}
            else cell.append(ch);
        }
        if(quoted)throw new IllegalArgumentException("Malformed quoted instrument CSV.");
        if(!cell.isEmpty()||!row.isEmpty()){row.add(cell.toString());rows.add(row);}
        return rows;
    }
}
