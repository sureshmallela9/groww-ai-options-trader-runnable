package com.optionstrader.platform;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ProfitLossReportServiceTest {
    final ObjectMapper json=new ObjectMapper();final Clock clock=Clock.fixed(Instant.parse("2026-11-06T05:00:00Z"),ZoneOffset.UTC);
    ProfitLossReportService.Builder builder(String start,String end,String group,String mode){return new ProfitLossReportService.Builder(json,clock,start,end,group,mode);}
    ObjectNode trade(String id,String mode,String exit,double net,double charges){return json.createObjectNode().put("intentId",id).put("mode",mode).put("symbol","TEST").put("state","POSITION_CLOSED").put("entryPrice",100).put("filledQuantity",5).put("remainingQuantity",0).put("entryTime","2026-10-30T05:00:00Z").put("exitTime",exit).put("realizedPnl",net).put("charges",charges);}
    @Test void istMidnightDatesAndModeLedgersStaySeparate(){
        var b=builder("2026-11-01","2026-11-02","DAY","PAPER");
        b.add(trade("one","PAPER","2026-11-01T18:29:59Z",100,5));b.add(trade("two","PAPER","2026-11-01T18:30:00Z",-40,3));b.add(trade("real","REAL","2026-11-01T18:29:59Z",999,20));
        var report=b.result();assertThat(report.at("/totals/net").decimalValue()).isEqualByComparingTo("60");assertThat(report.at("/totals/profit").asInt()).isEqualTo(100);assertThat(report.at("/totals/loss").asInt()).isEqualTo(40);
        assertThat(report.at("/totals/charges").asInt()).isEqualTo(8);assertThat(report.at("/totals/gross").asInt()).isEqualTo(68);assertThat(report.at("/groups/0/net").asInt()).isEqualTo(100);assertThat(report.at("/groups/1/net").asInt()).isEqualTo(-40);assertThat(report.path("trades").size()).isEqualTo(2);
    }
    @Test void actualRealPartialExitsAreAllocatedToTheirOwnMonthsWithoutDoubleCountingFinalTotal(){
        var row=trade("real","REAL","2026-11-02T05:00:00Z",999,99);
        row.putObject("realizedByDay").putObject("2026-10-30").put("net",10).put("gross",12).put("charges",2).put("exitQuantity",2);
        ((ObjectNode)row.path("realizedByDay")).putObject("2026-11-02").put("net",20).put("gross",23).put("charges",3).put("exitQuantity",3);
        var b=builder("2026-10-01","2026-11-06","MONTH","REAL");b.add(row);var r=b.result();
        assertThat(r.at("/groups/0/net").asInt()).isEqualTo(10);assertThat(r.at("/groups/1/net").asInt()).isEqualTo(20);assertThat(r.at("/totals/net").asInt()).isEqualTo(30);assertThat(r.at("/totals/realizedTrades").asInt()).isEqualTo(1);assertThat(r.at("/totals/realizedEvents").asInt()).isEqualTo(2);
        var narrow=builder("2026-11-01","2026-11-06","DAY","REAL");narrow.add(row);assertThat(narrow.result().at("/trades/0/range/net").asInt()).isEqualTo(20);
    }
    @Test void pendingAndUnfilledOrdersNeverAppearAsCompletedExits(){
        var b=builder("2026-11-01","2026-11-06","DAY","PAPER");
        b.add(trade("pending","PAPER","2026-11-02T05:00:00Z",100,2).put("state","EXIT_PENDING").put("remainingQuantity",5));
        b.add(trade("rejected","PAPER","2026-11-02T05:00:00Z",100,2).put("state","REJECTED").put("filledQuantity",0));
        assertThat(b.result().at("/totals/realizedEvents").asInt()).isZero();assertThat(b.result().path("trades").isEmpty()).isTrue();
    }
    @Test void missingAmountsAndUndatedRecordsAreExplicitlyIncomplete(){
        var b=builder("2026-11-01","2026-11-06","DAY","PAPER");var row=trade("bad","PAPER","2026-11-02T05:00:00Z",10,2);row.remove("realizedPnl");b.add(row);
        b.add(trade("undated","PAPER","invalid",10,2));var result=b.result();
        assertThat(result.path("complete").asBoolean()).isFalse();assertThat(result.at("/totals/missingNet").asInt()).isEqualTo(1);assertThat(result.path("undatedRecords").asInt()).isEqualTo(1);
    }
    @Test void noHistoricalMarksAreInventedAndFutureOrStaleQuotesAreExcluded(){
        var row=trade("open","PAPER","2026-11-06T05:00:00Z",0,0).put("state","POSITION_OPEN").put("remainingQuantity",5).put("unrealizedPnl",20).put("quoteTimestamp","2026-11-06T04:59:59Z");
        var b=builder("2026-11-01","2026-11-06","DAY","PAPER");b.add(row);assertThat(b.result().at("/currentOpen/pnl").asInt()).isEqualTo(20);assertThat(b.result().at("/totals/net").asInt()).isZero();
        var past=builder("2026-11-01","2026-11-05","DAY","PAPER");past.add(row);assertThat(past.result().at("/currentOpen/pnl").isNull()).isTrue();
        for(String date:List.of("2026-11-06T04:58:59Z","2026-11-06T05:00:09Z")){var stale=builder("2026-11-01","2026-11-06","DAY","PAPER");stale.add(row.deepCopy().put("quoteTimestamp",date));assertThat(stale.result().at("/currentOpen/available").asBoolean()).isFalse();}
    }
    @Test void rangesAreValidatedAndEmptyCalendarBucketsAreIncluded(){
        for(String date:List.of("2026-02-30","2026-2-01","invalid"))assertThatThrownBy(()->builder(date,"2026-11-06","DAY","PAPER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->builder("2026-11-06","2026-11-05","DAY","PAPER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->builder("2026-11-06","2026-11-07","DAY","PAPER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->builder("2026-11-01","2026-11-06","HOUR","PAPER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->builder("2026-11-01","2026-11-06","DAY","LIVE")).isInstanceOf(IllegalArgumentException.class);
        var result=builder("2025-12-31","2026-11-06","YEAR","PAPER").result();assertThat(result.path("groups").size()).isEqualTo(2);assertThat(result.at("/groups/0/period").asText()).isEqualTo("2025");assertThat(result.at("/totals/net").asInt()).isZero();
    }
}
