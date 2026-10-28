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
                            BigDecimal dayVolume,int bidQuantity,int askQuantity) {}
    private final GrowwMarketDataService market;
    private final GrowwApiClient broker;
    private final ObjectMapper json;
    private Map<String,Map<String,String>> instruments=Map.of();
    private Instant refreshed=Instant.EPOCH;
    private long nextRequestNanos;
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
        Instant timestamp=null;
        if(payload.path("last_trade_time").canConvertToLong()&&payload.path("last_trade_time").asLong()>0)
            timestamp=Instant.ofEpochMilli(payload.path("last_trade_time").asLong());
        return new QuoteData(symbol,last,positive(payload.get("bid_price")),positive(payload.get("offer_price")),
            timestamp,true,truth(instrument.get("buy_allowed"))&&!truth(instrument.get("is_reserved")),
            "EQ".equals(instrument.get("series")),decimal(instrument.get("tick_size"),null),
            positive(payload.get("volume")),payload.path("bid_quantity").asInt(0),payload.path("offer_quantity").asInt(0));
    }
    public Map<String,Object> quoteResponse(String symbol,SessionCalendar calendar,Clock clock){
        var quote=quote(symbol);var session=calendar.status(clock);var value=new LinkedHashMap<String,Object>();
        value.put("lastPrice",quote.last());value.put("bid",quote.bid());value.put("ask",quote.ask());
        value.put("timestamp",quote.timestamp()==null?null:quote.timestamp().toString());
        value.put("sessionOpen",session.get("sessionOpen"));value.put("calendarVerified",session.get("verified"));
        value.put("instrumentVerified",quote.verified());value.put("adjustmentStatus","UNKNOWN");
        value.put("volume",quote.dayVolume());
        return Map.of("symbol",symbol,"exchange","NSE","quote",value,"receivedAt",clock.instant().toString());
    }
    public Map<String,Object> candles(String symbol,int count,Clock clock){
        if(count<100||count>500)throw new IllegalArgumentException("lookbackCandles must be 100–500.");
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
        if(rows.size()<count)throw new IllegalStateException("Provider returned fewer completed candles than requested; no history is fabricated.");
        return Map.of("symbol",symbol,"exchange","NSE","interval","5m","bars",rows.subList(rows.size()-count,rows.size()),
            "brokerInstrumentId",instrument.get("groww_symbol"),"completed",true,
            "adjustmentStatus","UNKNOWN","receivedAt",clock.instant().toString());
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
