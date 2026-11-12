package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded read-only checks on technically qualified candidates, before final AI selection. */
final class IntradayCandidateScreen {
    private final NormalizedMarketGateway gateway;private final Clock clock;
    private final Map<String,ObjectNode> cache=new LinkedHashMap<>();
    IntradayCandidateScreen(NormalizedMarketGateway gateway,ObjectMapper json,Clock clock){this.gateway=gateway;this.clock=clock;}
    private boolean fresh(JsonNode check){
        try{var start=Instant.parse(check.path("checkedAt").asText());var until=Instant.parse(check.path("validUntil").asText());return !start.isAfter(clock.instant())&&
            !start.plusSeconds(60).isBefore(clock.instant())&&until.isAfter(clock.instant())&&until.isAfter(start)&&!until.isAfter(start.plusSeconds(60));}
        catch(RuntimeException invalid){return false;}
    }
    void attach(ObjectNode request){
        cache.entrySet().removeIf(e->!fresh(e.getValue()));var rows=request.putObject("intradayChecks");
        cache.forEach((symbol,check)->rows.set(symbol,check.deepCopy()));request.put("requireIntraday",true);
    }
    boolean approved(String symbol,String side){var check=cache.get(symbol);return fresh(check)&&"PASSED".equals(check.path("status").asText())&&side.equals(check.path("side").asText())&&"MIS".equals(check.path("product").asText())&&"GROWW".equals(check.path("source").asText())&&check.path("requiredMargin").isNumber()&&check.path("requiredMargin").decimalValue().signum()>0;}
    int checkPending(JsonNode selection,BooleanSupplier active,int limit){
        var candidates=new ArrayList<JsonNode>();selection.path("candidates").forEach(row->{if(row.path("technicalQualified").asBoolean()&&"PENDING_INTRADAY_CHECK".equals(row.path("candidateStatus").asText()))candidates.add(row);});
        candidates.sort(Comparator.comparing((JsonNode row)->!"NEWS".equals(row.path("selectionSource").asText())).thenComparing(Comparator.comparingDouble((JsonNode row)->row.path("score").asDouble()).reversed()).thenComparing(row->row.path("symbol").asText()));
        int count=0;
        for(var candidate:candidates){
            if(count==limit||!active.getAsBoolean())break;
            String symbol=candidate.path("symbol").asText(),side=candidate.path("side").asText();
            if(!symbol.matches("[A-Z0-9&.-]{1,32}")||!Set.of("BUY","SELL").contains(side)||!candidate.path("latestPrice").isNumber())continue;
            var prior=cache.get(symbol);
            if(fresh(prior)&&side.equals(prior.path("side").asText())&&prior.path("referencePrice").isNumber()&&prior.path("referencePrice").decimalValue().signum()>0&&
                candidate.path("latestPrice").decimalValue().divide(prior.path("referencePrice").decimalValue(),6,java.math.RoundingMode.HALF_UP).subtract(java.math.BigDecimal.ONE).abs().compareTo(new java.math.BigDecimal("0.1"))<=0)continue;
            var check=gateway.intradayCandidateCheck(symbol,side,candidate.path("latestPrice").decimalValue(),clock);
            if(check==null||!fresh(check))throw new IllegalStateException("Intraday preflight did not return fresh evidence.");
            cache.put(symbol,check.deepCopy());count++;
        }
        return count;
    }
}
