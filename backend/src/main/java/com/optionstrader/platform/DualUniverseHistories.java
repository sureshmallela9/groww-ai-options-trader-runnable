package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** One dedicated reader per universe; only the caller publishes caches and decisions. */
final class DualUniverseHistories implements AutoCloseable {
    static final int OTHER_BATCH_LIMIT=12;
    private static ThreadPoolExecutor lane(String name){
        var pool=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{var thread=new Thread(r,name);thread.setDaemon(true);return thread;});
        pool.prestartAllCoreThreads();return pool;
    }
    private final ThreadPoolExecutor nifty=lane("automatic-nifty50-history"),other=lane("automatic-other-stock-history");
    private final NormalizedMarketGateway gateway;private final ObjectMapper json;private final Clock clock;
    private volatile ObjectNode niftyProgress,otherProgress;
    private final java.util.concurrent.atomic.AtomicLong generation=new java.util.concurrent.atomic.AtomicLong();
    private Map<String,BigDecimal> prices=Map.of();private long pricesAt=Long.MIN_VALUE;
    private List<String> rotating=List.of();private int cursor;private long rotatedAt=Long.MIN_VALUE;private String rotationKey="";
    record Batch(Map<String,JsonNode> histories,Set<String> contextSymbols,ObjectNode coverage,long bucket){}
    static final class HistoryFailure extends RuntimeException {
        final String symbol;HistoryFailure(String symbol,RuntimeException cause){super(cause);this.symbol=symbol;}
    }
    DualUniverseHistories(NormalizedMarketGateway gateway,ObjectMapper json,Clock clock){this.gateway=gateway;this.json=json;this.clock=clock;}
    private void progress(boolean primary,String status,int completed,int total,String symbol,long token){
        if(token!=generation.get())return;
        var row=json.createObjectNode().put("scope",primary?"NIFTY50":"OTHER_STOCKS").put("status",status).put("completedHistories",completed).put("totalHistories",total).put("updatedAt",clock.instant().toString());
        if(symbol!=null)row.put("currentSymbol",symbol);
        if(primary)niftyProgress=row;else otherProgress=row;
    }
    ObjectNode status(){var value=json.createObjectNode();value.set("nifty50",niftyProgress==null?json.createObjectNode().put("status","IDLE"):niftyProgress.deepCopy());value.set("otherStocks",otherProgress==null?json.createObjectNode().put("status","IDLE"):otherProgress.deepCopy());return value;}
    int activeCount(){return nifty.getActiveCount()+other.getActiveCount();}
    int queuedCount(){return nifty.getQueue().size()+other.getQueue().size();}
    boolean closed(){return nifty.isShutdown()&&other.isShutdown();}
    private JsonNode history(String symbol,long bucket,boolean refresh,Map<String,JsonNode> cache,Map<String,Long> buckets){
        if(!refresh&&cache.containsKey(symbol)&&Objects.equals(buckets.get(symbol),bucket))return cache.get(symbol);
        try{return json.valueToTree(gateway.availableCandles(symbol,clock));}catch(RuntimeException error){throw new HistoryFailure(symbol,error);}
    }
    private Map<String,JsonNode> loadNifty(List<String> members,Set<String> news,boolean scanPrices,JsonNode risk,
            Map<String,JsonNode> cache,Map<String,Long> buckets,BooleanSupplier active,long bucket,long token){
        var result=new LinkedHashMap<String,JsonNode>();int completed=0;
        var symbols=new ArrayList<String>();symbols.add("NIFTY");symbols.addAll(members);
        for(String symbol:symbols){
            if(!active.getAsBoolean())throw new CancellationException("Automation run changed.");
            progress(true,"LOADING",completed,51,symbol,token);
            var cached=cache.get(symbol);var rows=cached==null?json.createArrayNode():cached.path("bars");
            boolean cheap=scanPrices&&!rows.isEmpty()&&rows.get(rows.size()-1).path("close").isNumber()&&rows.get(rows.size()-1).path("close").decimalValue().compareTo(risk.at("/intraday/fallbackMaxPrice").decimalValue())<0;
            result.put(symbol,history(symbol,bucket,news.contains(symbol)||cheap,cache,buckets));progress(true,"LOADING",++completed,51,symbol,token);
        }
        progress(true,"READY",51,51,null,token);return result;
    }
    private Map<String,JsonNode> loadOther(List<String> members,Set<String> news,JsonNode risk,
            Map<String,JsonNode> cache,Map<String,Long> buckets,BooleanSupplier active,ObjectNode coverage,long bucket,long token){
        progress(false,"PRICE_SCREENING",0,0,null,token);
        if(pricesAt==Long.MIN_VALUE||clock.millis()-pricesAt>=60000){
            try{prices=Map.copyOf(gateway.lastPrices(members));pricesAt=clock.millis();}
            catch(RuntimeException failure){throw new HistoryFailure("OTHER_STOCKS",failure);}
        }
        var rules=json.convertValue(risk.path("intraday"),IntradayRules.class);rules.validate();var capital=risk.at("/policy/capital").decimalValue();
        var affordable=members.stream().filter(s->{var p=prices.get(s);return p!=null&&p.signum()>0&&rules.capitalPriceRejection(capital,p)==null&&p.compareTo(rules.fallbackMaxPrice())<0;}).sorted().toList();
        var priority=new TreeSet<String>();news.stream().filter(members::contains).forEach(priority::add);
        var eligible=affordable.stream().filter(s->!priority.contains(s)).toList();int slots=OTHER_BATCH_LIMIT-priority.size();
        String key=capital.toPlainString()+rules.toString()+news.toString();
        if(rotatedAt==Long.MIN_VALUE||clock.millis()-rotatedAt>=60000||!key.equals(rotationKey)){
            var next=new ArrayList<String>();
            for(String symbol:eligible){if(next.size()>=slots)break;next.add(eligible.get((cursor+next.size())%eligible.size()));}
            cursor=eligible.isEmpty()?0:(cursor+next.size())%eligible.size();rotating=List.copyOf(next);rotatedAt=clock.millis();rotationKey=key;
        }
        var selected=new LinkedHashSet<String>();selected.addAll(priority);
        for(String symbol:rotating)if(selected.size()<OTHER_BATCH_LIMIT)selected.add(symbol);
        coverage.put("otherUniverseSize",members.size()).put("otherPricesAvailable",prices.size()).put("otherAffordableStocks",affordable.size())
            .put("otherBatchLimit",OTHER_BATCH_LIMIT).put("priceScreenReceivedAt",java.time.Instant.ofEpochMilli(pricesAt).toString())
            .put("coverageKind","BOUNDED_PRICE_SCREENED_BATCH");coverage.set("otherSymbols",json.valueToTree(selected));
        int completed=0;var result=new LinkedHashMap<String,JsonNode>();
        for(String symbol:selected){
            if(!active.getAsBoolean())throw new CancellationException("Automation run changed.");
            progress(false,"LOADING",completed,selected.size(),symbol,token);
            result.put(symbol,history(symbol,bucket,news.contains(symbol),cache,buckets));progress(false,"LOADING",++completed,selected.size(),symbol,token);
        }
        progress(false,"READY",completed,selected.size(),null,token);return result;
    }
    Batch load(JsonNode primary,JsonNode extended,Set<String> news,boolean scanPrices,JsonNode risk,
            Map<String,JsonNode> cache,Map<String,Long> buckets,BooleanSupplier active){
        if(news.size()>3)throw new IllegalArgumentException("At most three news candidates are supported.");
        var context=new LinkedHashSet<String>();primary.forEach(row->context.add(row.path("symbol").asText()));
        var members=new LinkedHashSet<String>();extended.forEach(row->members.add(row.path("symbol").asText()));
        if(context.size()!=50||!members.containsAll(context)||members.size()<500||members.size()>510)throw new IllegalStateException("Fresh NIFTY 50 and NIFTY 500 membership is required.");
        members.removeAll(context);var coverage=json.createObjectNode();long bucket=clock.instant().getEpochSecond()/300;
        long token=generation.incrementAndGet();progress(true,"QUEUED",0,51,null,token);progress(false,"QUEUED",0,0,null,token);
        Future<Map<String,JsonNode>> first=null,second=null;
        try{
            first=nifty.submit(()->loadNifty(List.copyOf(context),news,scanPrices,risk,cache,buckets,active,bucket,token));
            second=other.submit(()->loadOther(List.copyOf(members),news,risk,cache,buckets,active,coverage,bucket,token));
            var result=new LinkedHashMap<String,JsonNode>();result.putAll(first.get());result.putAll(second.get());
            if(!active.getAsBoolean())throw new CancellationException("Automation run changed.");
            coverage.put("nifty50Histories",50).put("otherHistories",result.size()-51);return new Batch(Map.copyOf(result),Set.copyOf(context),coverage,bucket);
        }catch(ExecutionException failure){var cause=failure.getCause();if(cause instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("History lane failed.",cause);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("History lanes interrupted.",interrupted);}
        finally{if(first!=null&&!first.isDone())first.cancel(true);if(second!=null&&!second.isDone())second.cancel(true);nifty.purge();other.purge();}
    }
    @Override public void close(){nifty.shutdownNow();other.shutdownNow();}
}
