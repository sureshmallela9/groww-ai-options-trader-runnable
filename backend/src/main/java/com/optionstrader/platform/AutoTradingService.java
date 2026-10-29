package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.TradingService;
import com.optionstrader.tradingai.TradingAiClient;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;

/** Explicitly started server-side paper automation; never invokes a broker order API. */
@Service
public class AutoTradingService {
    private final PaperPlatformService paper;
    private final TradingService dashboard;
    private final NormalizedMarketGateway gateway;
    private final SessionCalendar calendar;
    private final TradingAiClient ai;
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean schedulerEnabled;
    private final String owner=UUID.randomUUID().toString();
    private volatile boolean enabled;
    private volatile long retryAt;
    private volatile long referenceRetryAt;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"automatic-paper-selection");thread.setDaemon(true);return thread;
    });
    private final Map<String,JsonNode> candles=new ConcurrentHashMap<>();
    private final Map<String,Long> candleBuckets=new ConcurrentHashMap<>();

    public AutoTradingService(PaperPlatformService paper,TradingService dashboard,NormalizedMarketGateway gateway,
            SessionCalendar calendar,TradingAiClient ai,ObjectMapper json,JdbcTemplate db,
            PlatformTransactionManager manager,Clock clock,
            @Value("${trading.automation.scheduler-enabled:true}") boolean schedulerEnabled){
        this.paper=paper;this.dashboard=dashboard;this.gateway=gateway;this.calendar=calendar;this.ai=ai;
        this.json=json;this.db=db;this.tx=new TransactionTemplate(manager);this.clock=clock;this.schedulerEnabled=schedulerEnabled;
        paper.configureAutomation(dashboard::settings,()->enabled);
    }
    private ObjectNode node(Object value){return value==null?json.createObjectNode():json.valueToTree(value);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    private ObjectNode parse(String value){try{return (ObjectNode)json.readTree(value);}catch(Exception error){throw new IllegalStateException(error);}}
    private String now(){return clock.instant().toString();}
    private void initialize(){
        if(db.queryForObject("SELECT COUNT(*) FROM PLATFORM_STATE WHERE STATE_ID='AUTO'",Integer.class)==0)
            tx.executeWithoutResult(status->{
                db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='RISK' FOR UPDATE",String.class);
                if(db.queryForObject("SELECT COUNT(*) FROM PLATFORM_STATE WHERE STATE_ID='AUTO'",Integer.class)==0)
                    db.update("INSERT INTO PLATFORM_STATE(STATE_ID,PAYLOAD_JSON) VALUES('AUTO',?)","{\"enabled\":false,\"state\":\"STOPPED\"}");
            });
    }
    private ObjectNode read(boolean lock){
        initialize();return parse(db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='AUTO'"+(lock?" FOR UPDATE":""),String.class));
    }
    private boolean owns(JsonNode state){return enabled&&state.path("enabled").asBoolean()&&owner.equals(state.path("owner").asText());}
    private void save(ObjectNode state){
        state.put("updatedAt",now());
        db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='AUTO'",encode(state));
    }
    private void event(String type,ObjectNode state){
        db.update("INSERT INTO PLATFORM_AUDIT(AUDIT_ID,EVENT_TYPE,PAYLOAD_JSON,CREATED_AT) VALUES(?,?,?,?)",
            UUID.randomUUID().toString(),type,encode(state),now());
    }
    private void phase(String name,String message){
        tx.executeWithoutResult(status->{var state=read(true);if(!owns(state))return;
            boolean changed=!name.equals(state.path("state").asText())||!message.equals(state.path("message").asText());
            state.put("state",name);state.put("message",message);state.put("lastCheckedAt",now());save(state);
            if(changed)event("AUTO_"+name,state);
        });
    }
    public ObjectNode status(){
        var state=read(false);boolean active=owns(state);
        state.put("enabled",active);state.put("busy",busy.get());state.put("mode","PAPER");
        state.put("liveExecutionEnabled",false);state.put("pollIntervalMs",5000);
        if(!active&&!"STOPPED".equals(state.path("state").asText())){
            state.put("state","PAUSED");state.put("message","Automatic entries are paused. Start paper auto mode explicitly after a restart.");
        }
        state.remove("owner");return state;
    }
    public ObjectNode start(JsonNode body){
        if(!"PAPER".equals(body.path("mode").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Real order execution is not implemented or enabled. Use PAPER mode.");
        if(!body.path("confirmed").asBoolean(false))throw new IllegalArgumentException("Confirm automatic simulated paper entries before starting.");
        tx.executeWithoutResult(status->{
            var state=read(true);if(owns(state)){
                state.put("scheduledEventRisk",body.path("scheduledEventRisk").asBoolean(false));
                save(state);event("AUTO_EVENT_RISK_UPDATED",state);return;
            }
            state.removeAll();state.put("enabled",true);state.put("owner",owner);state.put("runId",UUID.randomUUID().toString());
            state.put("mode","PAPER");state.put("state","STARTING");state.put("startedAt",now());
            state.put("scheduledEventRisk",body.path("scheduledEventRisk").asBoolean(false));
            state.put("message","Automatic paper selection started; validation and risk approval are required for every entry.");
            save(state);event("AUTO_STARTED",state);enabled=true;retryAt=0;
        });
        tick();return status();
    }
    public ObjectNode stop(){
        tx.executeWithoutResult(status->{var state=read(true);state.put("enabled",false);state.put("state","STOPPED");
            state.put("message","Automatic entries stopped. Existing paper positions remain under stop, target and session risk monitoring.");
            save(state);event("AUTO_STOPPED",state);enabled=false;});
        return status();
    }
    public ObjectNode eventRisk(boolean enabledRisk){
        tx.executeWithoutResult(status->{var state=read(true);state.put("scheduledEventRisk",enabledRisk);
            save(state);event("AUTO_EVENT_RISK_UPDATED",state);});
        return status();
    }
    @Scheduled(fixedDelayString="${trading.automation.check-delay-ms:5000}")
    public void tick(){
        if(!schedulerEnabled||!enabled||clock.millis()<retryAt||!busy.compareAndSet(false,true))return;
        worker.submit(()->{try{cycle();}catch(Exception error){
                retryAt=clock.millis()+30000;
                phase("DATA_UNAVAILABLE","A required data or AI service failed ("+error.getClass().getSimpleName()+"). Entries are blocked. Data collection retries after 30 seconds; a final decision attempt is not repeated within the same candle.");
            }finally{busy.set(false);}});
    }
    private JsonNode bars(String symbol,long bucket){
        if(!Objects.equals(candleBuckets.get(symbol),bucket)){
            var fetched=node(gateway.candles(symbol,100,clock));candles.put(symbol,fetched);candleBuckets.put(symbol,bucket);
        }
        return candles.get(symbol).path("bars");
    }
    private boolean active(){boolean owned=owns(read(false));if(!owned)enabled=false;return owned;}
    private void waitingSession(Map<String,Object> session){
        String code=session.getOrDefault("reasonCode","WAITING_FOR_SESSION").toString();
        String reason=session.getOrDefault("reason","A verified open exchange entry session is required.").toString();
        String next=session.getOrDefault("nextEntrySession","").toString();
        String message=reason+(next.isBlank()?"":" Next configured entry session: "+next+".");
        phase(code,message);
        if(clock.millis()<referenceRetryAt)return;
        try{
            bars("NIFTY",clock.instant().getEpochSecond()/300);
            tx.executeWithoutResult(status->{var current=read(true);if(!owns(current))return;
                current.put("referenceSymbol","NIFTY");
                current.put("referenceMessage","NIFTY is a read-only market reference, not a selected stock. Entries remain blocked by the session rules.");save(current);
            });
        }catch(Exception unavailable){
            referenceRetryAt=clock.millis()+30000;
            phase(code,message+" Reference chart unavailable ("+unavailable.getClass().getSimpleName()+"); retrying data collection after 30 seconds.");
        }
    }
    private Map<String,Object> aiPolicy(JsonNode risk){
        var p=risk.path("policy");
        return Map.of("capital",p.path("capital").decimalValue(),"riskPerTradePercent",p.path("riskPerTradePercent").decimalValue(),
            "maxSpreadPercent",p.path("maxSpreadPercent").decimalValue(),"maxQuoteAgeSeconds",p.path("maxQuoteAgeSeconds").asInt(),
            "roundTripCostBps",p.path("roundTripCostBps").decimalValue(),"strategyEnabled",p.path("strategyEnabled").asBoolean(),
            "minimumTurnover",p.path("minimumDayTurnover").decimalValue());
    }
    void cycle(){
        if(!active())return;
        var control=read(false);var risk=node(paper.status());var portfolio=node(paper.portfolio());
        if(control.path("scheduledEventRisk").asBoolean()){
            phase("EVENT_RISK","The configured scheduled-event risk flag blocks automatic entries.");return;
        }
        if(risk.path("killSwitch").asBoolean()||!risk.at("/policy/strategyEnabled").asBoolean()){
            phase("BLOCKED","Kill switch or strategy-disable policy blocks automatic entries.");return;
        }
        var session=calendar.status(clock);
        if(!Boolean.TRUE.equals(session.get("entryAllowed"))){
            waitingSession(session);return;
        }
        if(!risk.path("openPositions").isEmpty()){
            phase("MONITORING_POSITION","An open or unresolved paper position already exists. Monitoring it before selecting another stock.");return;
        }
        var p=risk.path("policy");
        int wins=Integer.parseInt(dashboard.settings().getOrDefault("stopAfterProfitableTrades",20).toString());
        long entries=paper.intents().stream().filter(row->row.has("filledQuantity")&&
            portfolio.path("sessionDate").asText().equals(row.path("sessionDate").asText())).count();
        BigDecimal loss=p.path("capital").decimalValue().multiply(p.path("dailyLossPercent").decimalValue()).divide(new BigDecimal("-100"));
        if(portfolio.path("profitableTrades").asLong()>=wins||entries>=p.path("maxTradesPerDay").asInt()||
            portfolio.path("netPnl").decimalValue().compareTo(loss)<=0){
            phase("DAILY_LIMIT","Configured daily entry, profit-count or loss limit blocks another automatic entry.");return;
        }
        long bucket=clock.instant().getEpochSecond()/300;
        if(control.path("lastAttemptBucket").asLong(-1)==bucket)return;
        phase("VERIFYING_DATA","Loading completed NIFTY and all 50 constituent histories; missing or stale evidence blocks selection.");
        JsonNode universe=node(ai.internalGet("/api/v1/universe"));
        if(!universe.has("retrievedAt")||Duration.between(Instant.parse(universe.path("retrievedAt").asText()),clock.instant()).toDays()>=7)
            universe=node(ai.internalPost("/api/v1/universe/refresh",Map.of()));
        if(!universe.path("constituents").isArray()||universe.path("constituents").size()!=50)
            throw new IllegalStateException("Maintained universe is unavailable.");
        var request=json.createObjectNode();request.set("niftyBars",bars("NIFTY",bucket));
        var snapshots=request.putArray("snapshots");
        for(JsonNode member:universe.path("constituents")){
            if(!active())return;
            String symbol=member.path("symbol").asText();
            snapshots.addObject().put("symbol",symbol).set("bars",bars(symbol,bucket));
        }
        request.set("policy",json.valueToTree(aiPolicy(risk)));
        request.put("scheduledEventRisk",control.path("scheduledEventRisk").asBoolean());
        if(!active())return;
        phase("ANALYZING","Comparing all maintained stocks and their derived industry baskets; selecting at most one candidate.");
        var selection=node(ai.internalPost("/api/v1/automation/select",request));
        boolean accepted=Boolean.TRUE.equals(tx.execute(status->{
            var current=read(true);if(!owns(current))return false;
            current.remove("referenceSymbol");current.remove("referenceMessage");
            current.put("lastAttemptBucket",bucket);current.set("selection",selection);save(current);event("AUTO_SELECTION",current);return true;
        }));
        if(!accepted)return;
        JsonNode selected=selection.path("selected");
        if(!selected.isObject()){
            phase("NO_TRADE",selection.path("reasons").toString());return;
        }
        String symbol=selected.path("symbol").asText();
        var decisionRequest=json.createObjectNode().put("symbol",symbol).put("mode","PAPER");
        decisionRequest.set("bars",candles.get(symbol).path("bars"));
        decisionRequest.set("quote",node(gateway.quoteResponse(symbol,calendar,clock)).path("quote"));
        decisionRequest.set("market",selected.path("market"));decisionRequest.set("policy",json.valueToTree(aiPolicy(risk)));
        String key="AUTO-"+control.path("runId").asText()+"-"+bucket;
        phase("FINAL_DECISION","Selected "+symbol+". Rechecking its fresh quote and final AI decision.");
        var decision=node(ai.internalPost("/api/v1/decisions/evaluate",decisionRequest,key));
        tx.executeWithoutResult(status->{
            var current=read(true);if(!owns(current))return;
            current.set("decision",decision);save(current);
            if(current.path("scheduledEventRisk").asBoolean()){
                current.put("state","EVENT_RISK");current.put("message","Scheduled-event risk was activated; the pending entry is blocked.");save(current);return;
            }
            if(!"BUY".equals(decision.path("action").asText())){
                current.put("state","NO_TRADE");current.put("message",decision.path("reasons").toString());save(current);event("AUTO_NO_TRADE",current);return;
            }
            // Holding the control row linearizes Stop against entry. The paper engine
            // independently locks risk state, reloads the decision and obtains a fresh quote.
            var intent=paper.submitAutomatic(new PaperPlatformService.Intent(decision.path("decisionId").asText(),"PAPER",null,true),key);
            current.put("lastIntentId",intent.path("intentId").asText());
            current.put("state",intent.path("state").asText());
            current.put("message",intent.path("riskRejections").isArray()?intent.path("riskRejections").toString():"Automatic simulated paper entry recorded for "+symbol+".");
            save(current);event("AUTO_INTENT",current);
        });
    }
    public ObjectNode chart(String symbol){
        if(symbol==null||!symbol.matches("[A-Z0-9&.-]{1,32}"))throw new IllegalArgumentException("Choose a valid stock symbol.");
        var result=json.createObjectNode().put("symbol",symbol).put("mode","PAPER");
        try{if(!symbol.equals("NIFTY")||clock.millis()>=referenceRetryAt)bars(symbol,clock.instant().getEpochSecond()/300);}
        catch(Exception unavailable){result.put("dataStatus","Candle history unavailable; displaying recorded fills only.");}
        JsonNode cached=candles.get(symbol);
        result.set("bars",cached==null?json.createArrayNode():cached.path("bars"));
        var markers=result.putArray("markers");
        Instant first=cached!=null&&cached.path("bars").isArray()&&!cached.path("bars").isEmpty()
            ?java.time.OffsetDateTime.parse(cached.path("bars").get(0).path("timestamp").asText()).toInstant():Instant.MIN;
        int outside=0;
        for(var row:paper.intents())if(symbol.equals(row.path("symbol").asText())&&row.has("filledQuantity")){
            if(!Instant.parse(row.path("entryTime").asText()).isBefore(first))
                markers.addObject().put("side","BUY").put("time",row.path("entryTime").asText()).set("price",row.path("entryPrice"));
            else outside++;
            if("POSITION_CLOSED".equals(row.path("state").asText())){
                if(!Instant.parse(row.path("exitTime").asText()).isBefore(first))markers.addObject().put("side","SELL")
                    .put("time",row.path("exitTime").asText()).set("price",row.path("exitPrice"));
                else outside++;
            }
        }
        result.put("outsideWindowMarkers",outside);
        result.put("updatedAt",cached==null?now():cached.path("receivedAt").asText());
        return result;
    }
    @PreDestroy public void shutdown(){enabled=false;worker.shutdownNow();}
}
