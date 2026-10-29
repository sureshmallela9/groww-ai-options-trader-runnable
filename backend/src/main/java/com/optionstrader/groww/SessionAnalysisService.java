package com.optionstrader.groww;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.AnalysisService;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read-only session analysis. Polling never submits trading or backtest commands. */
@Service
public class SessionAnalysisService {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private final GrowwMarketDataService market;
    private final AnalysisService analysis;
    private final ObjectMapper json;
    private final Clock clock;
    private final Semaphore capacity = new Semaphore(4);
    private final ConcurrentHashMap<String, State> sessions = new ConcurrentHashMap<>();
    private static final class State {
        JsonNode bars;
        Map<String,Object> analysis;
        Map<String,Object> response;
        long nextFetch;
    }

    @Autowired
    public SessionAnalysisService(GrowwMarketDataService market, AnalysisService analysis, ObjectMapper json) {
        this(market, analysis, json, Clock.systemUTC());
    }
    SessionAnalysisService(GrowwMarketDataService market, AnalysisService analysis, ObjectMapper json, Clock clock) {
        this.market=market; this.analysis=analysis; this.json=json; this.clock=clock;
    }

    public Map<String,Object> poll(JsonNode body) {
        if(body==null || !body.isObject()) throw new IllegalArgumentException("Session settings are required.");
        var now=clock.instant().atZone(INDIA);
        var start=now.toLocalDate().atTime(9,15);
        var close=now.toLocalDate().atTime(15,30);
        var end=now.toLocalDateTime().isAfter(close)?close:now.toLocalDateTime();
        // Validate the same historical contract, but the server owns today's India-time range.
        var normalized=(com.fasterxml.jackson.databind.node.ObjectNode)body.deepCopy();
        normalized.put("start_time",start.format(TIME));
        normalized.put("end_time",(end.isAfter(start)?end:start.plusSeconds(1)).format(TIME));
        var request=market.parseHistoricalRequest(normalized);
        String key=now.toLocalDate()+"|"+request.exchange()+"|"+request.segment()+"|"+
            request.growwSymbol()+"|"+request.candleInterval()+"|"+request.analysisPrompt()+"|"+request.knowledgeQuery();
        final State state;
        synchronized(sessions) {
            sessions.keySet().removeIf(value->!value.startsWith(now.toLocalDate().toString()+"|"));
            if(sessions.size()>=64 && !sessions.containsKey(key))
                throw new IllegalStateException("Automatic analysis capacity is full; stop an unused session or retry tomorrow.");
            state=sessions.computeIfAbsent(key,ignored->new State());
        }
        if(!capacity.tryAcquire()) throw new IllegalStateException("Automatic analysis workers are busy; retry in five seconds.");
        try {
            synchronized(state) {
                if(clock.millis()<state.nextFetch && state.response!=null) return state.response;
                var result=new LinkedHashMap<String,Object>();
                result.put("checkedAt",clock.instant().toString());
                result.put("pollAfterMs",5000);
                result.put("tradeAuthorized",false);
                result.put("automaticAnalysis",true);
                if(!now.toLocalDateTime().isAfter(start)) {
                    result.put("status","WAITING_FOR_SESSION");
                    result.put("message","Waiting for 09:15 India time and the first completed candle. Exchange holidays can also have no data.");
                } else {
                    var raw=market.fetchHistoricalCandles(request);
                    var input=market.buildAvailableAnalysisInput(request,raw);
                    var source=new LinkedHashMap<String,Object>();
                    source.put("provider","GROWW"); source.put("growwSymbol",request.growwSymbol());
                    source.put("candleInterval",request.candleInterval());
                    source.put("startTime",request.startTime()); source.put("endTime",request.endTime());
                    source.put("sourceCandleCount",input.sourceCandleCount());
                    source.put("analysisCandleCount",input.analysisCandleCount());
                    result.put("source",source);
                    if(input.analysisCandleCount()==0) {
                        result.put("status","WAITING_FOR_CANDLES");
                        result.put("message","No completed valid candles yet. Groww returned "+input.sourceCandleCount()+
                            " raw rows. Waiting for candle completion or provider availability; check symbol and segment if this persists.");
                    } else {
                        JsonNode bars=json.valueToTree(input.payload().get("bars"));
                        boolean unchanged=bars.equals(state.bars) && state.analysis!=null;
                        Map<String,Object> response=state.analysis;
                        if(!unchanged) {
                            response=analysis.analyze(json.valueToTree(input.payload()));
                            // Do not automatically replay ambiguous timeouts; they may have saved a report.
                            if(response.containsKey("report_id") || "UNAVAILABLE".equals(response.get("status"))) {
                                state.bars=bars;
                                state.analysis=response;
                            }
                        }
                        result.put("status",response.getOrDefault("status","ANALYZED"));
                        result.put("analysis",response);
                        result.put("reusedAnalysis",unchanged);
                    }
                }
                state.nextFetch=clock.millis()+5000;
                state.response=result;
                return result;
            }
        } finally {capacity.release();}
    }
}
