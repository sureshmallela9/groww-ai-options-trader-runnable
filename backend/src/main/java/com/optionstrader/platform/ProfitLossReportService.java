package com.optionstrader.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Saved managed-trade accounting only: no broker calls, order mutations or reconstructed marks. */
@Service
public class ProfitLossReportService {
    private final JdbcTemplate db;private final ObjectMapper json;private final Clock clock;
    public ProfitLossReportService(JdbcTemplate db,ObjectMapper json,Clock clock){this.db=db;this.json=json;this.clock=clock;}
    public ObjectNode report(String from,String to,String groupBy,String mode){
        var builder=new Builder(json,clock,from,to,groupBy,mode);
        db.query("SELECT PAYLOAD_JSON FROM PAPER_INTENTS",(org.springframework.jdbc.core.RowCallbackHandler)rs->{
            try{builder.add(json.readTree(rs.getString(1)));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("A saved trade cannot be read; a complete P&L report is unavailable.");}
        });return builder.result();
    }
    static LocalDate date(String text){try{if(text==null||!text.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();return LocalDate.parse(text);}catch(Exception e){throw new IllegalArgumentException("Use valid dates in YYYY-MM-DD format.");}}
    static LocalDate stamp(JsonNode value){try{return OffsetDateTime.parse(value.asText()).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate();}catch(Exception invalid){return null;}}
    static BigDecimal money(JsonNode row,String key){return row.path(key).isNumber()?row.path(key).decimalValue().setScale(2,RoundingMode.HALF_UP):null;}
    static class Totals {
        BigDecimal net=BigDecimal.ZERO,profit=BigDecimal.ZERO,loss=BigDecimal.ZERO,charges=BigDecimal.ZERO,gross=BigDecimal.ZERO;
        int events,missingNet,missingCharges,missingGross;boolean estimates;Set<String> trades=new HashSet<>();
        void add(String id,BigDecimal n,BigDecimal c,BigDecimal g,boolean estimated){
            events++;trades.add(id);estimates|=estimated;
            if(n==null)missingNet++;else{net=net.add(n);if(n.signum()>0)profit=profit.add(n);else loss=loss.add(n.negate());}
            if(c==null)missingCharges++;else charges=charges.add(c);
            if(g==null)missingGross++;else gross=gross.add(g);
        }
        ObjectNode node(ObjectMapper json){return json.createObjectNode().put("net",net).put("profit",profit).put("loss",loss).put("charges",charges).put("gross",gross)
            .put("realizedEvents",events).put("realizedTrades",trades.size()).put("missingNet",missingNet).put("missingCharges",missingCharges).put("missingGross",missingGross).put("estimatedCharges",estimates);}
    }
    static class Builder {
        final ObjectMapper json;final Clock clock;final LocalDate from,to,today;final String grouping,mode;
        final TreeMap<LocalDate,Totals> groups=new TreeMap<>();final Totals total=new Totals();final ArrayNode trades;
        LocalDate earliest,latest;int modeRecords,undated;BigDecimal open=BigDecimal.ZERO;int openPositions,missingMarks,estimatedOpen;
        Builder(ObjectMapper json,Clock clock,String from,String to,String groupBy,String mode){
            this.json=json;this.clock=clock;this.from=date(from);this.to=date(to);this.today=clock.instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
            this.grouping=groupBy;this.mode=mode;trades=json.createArrayNode();
            if(!Set.of("PAPER","REAL").contains(mode))throw new IllegalArgumentException("Choose PAPER or REAL reports.");
            if(!Set.of("DAY","MONTH","YEAR").contains(groupBy))throw new IllegalArgumentException("Choose DAY, MONTH or YEAR grouping.");
            if(this.from.isAfter(this.to)||this.to.isAfter(today)||ChronoUnit.DAYS.between(this.from,this.to)>36600)throw new IllegalArgumentException("Choose an ordered date range ending today or earlier, within 100 years.");
            for(LocalDate bucket=bucket(this.from);!bucket.isAfter(this.to);bucket=next(bucket)){
                if(groups.size()>=5000)throw new IllegalArgumentException("Choose monthly or yearly grouping for this long range.");groups.put(bucket,new Totals());
            }
        }
        LocalDate bucket(LocalDate day){return switch(grouping){case "MONTH"->day.withDayOfMonth(1);case "YEAR"->day.withDayOfYear(1);default->day;};}
        LocalDate next(LocalDate day){return switch(grouping){case "MONTH"->day.plusMonths(1);case "YEAR"->day.plusYears(1);default->day.plusDays(1);};}
        void coverage(LocalDate day){if(day!=null){if(earliest==null||day.isBefore(earliest))earliest=day;if(latest==null||day.isAfter(latest))latest=day;}}
        void add(JsonNode row){
            if(!mode.equals(row.path("mode").asText("PAPER")))return;modeRecords++;
            LocalDate entry=stamp(row.path("entryTime")),exit=stamp(row.path("exitTime"));coverage(entry);coverage(exit);coverage(stamp(row.path("createdAt")));
            boolean filled=row.path("filledQuantity").asInt()>0&&row.path("entryPrice").isNumber()&&row.path("entryPrice").decimalValue().signum()>0;
            boolean closed=filled&&"POSITION_CLOSED".equals(row.path("state").asText())&&row.path("remainingQuantity").asInt(-1)==0;
            var range=new Totals();String id=row.path("intentId").asText();if(id.isBlank())id="unidentified-"+modeRecords;
            boolean estimated="PAPER".equals(mode)||row.path("chargesEstimated").asBoolean(true);
            JsonNode daily=row.path("realizedByDay");
            if("REAL".equals(mode)&&daily.isObject()&&!daily.isEmpty()){
                var fields=daily.fields();while(fields.hasNext()){
                    var field=fields.next();LocalDate day;try{day=date(field.getKey());}catch(IllegalArgumentException invalid){undated++;continue;}
                    var values=field.getValue();coverage(day);
                    if(!filled||!values.path("exitQuantity").isIntegralNumber()||values.path("exitQuantity").asInt()<=0){undated++;continue;}
                    event(id,day,money(values,"net"),money(values,"charges"),money(values,"gross"),estimated,range);
                }
                if(closed&&exit!=null&&!daily.has(exit.toString())&&!exit.isBefore(from)&&!exit.isAfter(to))event(id,exit,null,null,null,estimated,range);
            }else if(closed){
                if(exit==null)undated++;else{
                    BigDecimal n=money(row,"realizedPnl"),c=money(row,"charges"),g=money(row,"grossPnl");
                    if(g==null&&n!=null&&c!=null)g=n.add(c);event(id,exit,n,c,g,estimated,range);
                }
            }
            if(range.events>0){
                var trade=json.createObjectNode();for(String field:List.of("intentId","symbol","positionSide","state","filledQuantity","remainingQuantity","entryPrice","exitPrice","entryTime","exitTime"))if(row.has(field))trade.set(field,row.get(field));
                trade.set("range",range.node(json));trades.add(trade);
            }
            // Current marks never enter historical realized totals.
            if(filled&&!closed&&row.path("remainingQuantity").asInt()>0&&ExecutionModeService.active(row)){
                openPositions++;BigDecimal mark=money(row,"unrealizedPnl");LocalDate marked=stamp(row.path("quoteTimestamp"));
                boolean stale=row.has("incident")||row.has("brokerIncident")||mark==null||!today.equals(marked);
                try{long age=Duration.between(OffsetDateTime.parse(row.path("quoteTimestamp").asText()).toInstant(),clock.instant()).getSeconds();stale|=age>10||age < -2;}catch(Exception invalid){stale=true;}
                if(stale)missingMarks++;else open=open.add(mark);if(estimated)estimatedOpen++;
            }
        }
        void event(String id,LocalDate day,BigDecimal net,BigDecimal charges,BigDecimal gross,boolean estimated,Totals range){
            if(day.isBefore(from)||day.isAfter(to))return;
            total.add(id,net,charges,gross,estimated);groups.get(bucket(day)).add(id,net,charges,gross,estimated);range.add(id,net,charges,gross,estimated);
        }
        ObjectNode result(){
            var result=json.createObjectNode().put("mode",mode).put("from",from.toString()).put("to",to.toString()).put("groupBy",grouping).put("asOf",clock.instant().toString()).put("timezone","Asia/Kolkata")
                .put("scope","SAVED_MANAGED_TRADES_ONLY").put("sourceRecords",modeRecords).put("undatedRecords",undated).put("complete",total.missingNet==0&&total.missingCharges==0&&total.missingGross==0&&undated==0);
            result.set("totals",total.node(json));result.set("trades",trades);
            var coverage=result.putObject("recordCoverage");if(earliest!=null)coverage.put("firstActivity",earliest.toString());if(latest!=null)coverage.put("lastActivity",latest.toString());
            var rows=result.putArray("groups");groups.forEach((day,value)->{var node=value.node(json);node.put("period",grouping.equals("DAY")?day.toString():grouping.equals("MONTH")?day.toString().substring(0,7):String.valueOf(day.getYear()));
                node.put("from",day.isBefore(from)?from.toString():day.toString());LocalDate end=next(day).minusDays(1);node.put("to",end.isAfter(to)?to.toString():end.toString());rows.add(node);});
            var current=result.putObject("currentOpen");current.put("includedInRealizedTotals",false).put("positions",openPositions).put("staleOrMissingMarks",missingMarks).put("asOf",clock.instant().toString());
            if(to.equals(today)&&missingMarks==0)current.put("pnl",open);else current.putNull("pnl");
            current.put("available",to.equals(today)&&missingMarks==0).put("estimatedCharges",estimatedOpen>0);
            return result;
        }
    }
}
