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

/** One explicitly started selection coordinator; independent risk approval controls every entry. */
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
    private volatile long newsRetryAt;
    private final AtomicBoolean busy=new AtomicBoolean();
    private record WorkProgress(String phase,String symbol,int completed,int total,Instant since) {}
    private volatile WorkProgress progress;
    @Value("${trading.automation.check-delay-ms:5000}") private long checkDelayMs=5000;
    @Value("${groww.api.poll-interval:1000}") private long indexPollMs=1000;
    @Value("${groww.api.option-chain-poll-interval:30000}") private long optionPollMs=30000;
    @Value("${trading.platform.monitor-delay-ms:1000}") private long monitorDelayMs=1000;
    @Value("${trading.platform.outbox-delay-ms:3000}") private long outboxDelayMs=3000;
    @Value("${groww.api.enabled:false}") private boolean growwConfiguredEnabled;
    @Value("${trading.ai.enabled:true}") private boolean aiConfiguredEnabled=true;
    @Value("${trading.platform.monitor-enabled:true}") private boolean monitorConfiguredEnabled=true;
    private void work(String name,String symbol,int completed,int total){
        var previous=progress;
        progress=new WorkProgress(name,symbol,completed,total,
            previous!=null&&name.equals(previous.phase())?previous.since():clock.instant());
    }
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"automatic-paper-selection");thread.setDaemon(true);return thread;
    });
    private final DualUniverseHistories universeHistories;
    private final IntradayCandidateScreen intradayScreen;
    private final Map<String,JsonNode> candles=new ConcurrentHashMap<>();
    private final Map<String,Long> candleBuckets=new ConcurrentHashMap<>();
    private volatile ObjectNode shortlistSnapshot;

    public AutoTradingService(PaperPlatformService paper,TradingService dashboard,NormalizedMarketGateway gateway,
            SessionCalendar calendar,TradingAiClient ai,ObjectMapper json,JdbcTemplate db,
            PlatformTransactionManager manager,Clock clock,
            @Value("${trading.automation.scheduler-enabled:true}") boolean schedulerEnabled){
        this.paper=paper;this.dashboard=dashboard;this.gateway=gateway;this.calendar=calendar;this.ai=ai;
        this.universeHistories=new DualUniverseHistories(gateway,json,clock);
        this.intradayScreen=new IntradayCandidateScreen(gateway,json,clock);
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
        String payload=encode(state);
        db.update(connection->{var statement=connection.prepareStatement("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='AUTO'");JsonParameter.bind(statement,1,payload);return statement;});
    }
    private void event(String type,ObjectNode state){
        var audit=json.createObjectNode();
        for(String field:List.of("state","message","runId","lastIntentId","lastSelectionAt","lastDecisionAt","enabled","scheduledEventRisk"))if(state.has(field))audit.set(field,state.get(field));
        if(state.has("selection")){
            var selection=state.path("selection");var summary=audit.putObject("selection");
            for(String field:List.of("selectionId","status","candidateSymbols","reasons"))if(selection.has(field))summary.set(field,selection.get(field));
            if(selection.path("selected").isObject()){
                var selected=summary.putObject("selected");for(String field:List.of("symbol","side","score","candleTimestamp"))if(selection.path("selected").has(field))selected.set(field,selection.path("selected").get(field));
            }
        }
        if(state.has("decision")){
            var decision=state.path("decision");var summary=audit.putObject("decision");
            for(String field:List.of("decisionId","symbol","action","reasons","createdAt"))if(decision.has(field))summary.set(field,decision.get(field));
        }
        String id=UUID.randomUUID().toString(),payload=encode(audit),created=now();
        db.update(connection->{var statement=connection.prepareStatement("INSERT INTO PLATFORM_AUDIT(AUDIT_ID,EVENT_TYPE,PAYLOAD_JSON,CREATED_AT) VALUES(?,?,?,?)");
            statement.setString(1,id);statement.setString(2,type);JsonParameter.bind(statement,3,payload);statement.setString(4,created);return statement;});
    }
    private void phase(String name,String message){
        tx.executeWithoutResult(status->{var state=read(true);if(!owns(state))return;
            boolean changed=!name.equals(state.path("state").asText())||!message.equals(state.path("message").asText());
            describePhase(state,name,message);state.put("lastCheckedAt",now());save(state);
            if(changed)event("AUTO_"+name,state);
        });
    }
    private void describePhase(ObjectNode state,String name,String message){
        if(!name.equals(state.path("state").asText())){
            state.put("phaseStartedAt",now());
            var history=state.withArray("activityHistory");
            history.addObject().put("phase",name).put("at",now()).put("message",message);
            while(history.size()>12)history.remove(0);
        }
        state.put("state",name);state.put("message",message);
    }
    private ObjectNode activity(ObjectNode state,boolean active,boolean working){
        var result=json.createObjectNode();var current=progress;
        String phase=state.path("state").asText("STOPPED");
        boolean processing=active&&working&&current!=null;
        String kind;
        if(!active){processing=false;kind="STOPPED".equals(phase)?"STOPPED":"PAUSED";}
        else if(state.path("scheduledEventRisk").asBoolean()){
            processing=false;kind="BLOCKED";phase="EVENT_RISK";
        }
        else if(processing){kind="PROCESSING";phase=current.phase();}
        else if(Set.of("EVENT_RISK","BLOCKED","DAILY_LIMIT","DATA_UNAVAILABLE","REJECTED",
                "NEWS_WAIT","CALENDAR_MISSING","WAITING_FOR_SESSION","ENTRY_WINDOW_CLOSED","MARKET_CLOSED",
                "BEFORE_OPEN","NON_TRADING_DAY","CALENDAR_NOT_REVIEWED","SPECIAL_SESSION_NOT_CONFIGURED",
                "DATE_NOT_CONFIGURED","INVALID_SESSION_TIMES","CALENDAR_INVALID").contains(phase))kind="BLOCKED";
        else if(Set.of("MONITORING_POSITION","POSITION_OPEN","EXIT_PENDING","ENTRY_PENDING").contains(phase))kind="MONITORING";
        else kind="WAITING";
        result.put("status",kind);result.put("processing",processing);result.put("phase",phase);
        result.put("historyWorkers",2).put("activeHistoryWorkers",universeHistories.activeCount())
            .put("queuedHistories",universeHistories.queuedCount()).put("selectionCoordinators",1);
        result.set("universeWorkers",universeHistories.status());
        result.put("serverTime",now());result.put("checkIntervalMs",checkDelayMs);result.put("scanIntervalSeconds",5);
        for(String key:List.of("lastCheckedAt","lastScanStartedAt","lastSelectionAt","lastDecisionAt","lastScanSummary","lastNewsAt","lastNewsSummary"))
            if(state.has(key))result.set(key,state.get(key));
        if("DATA_UNAVAILABLE".equals(phase)&&state.has("lastFailure"))result.set("failure",state.get("lastFailure"));
        if(state.path("scheduledEventRisk").asBoolean()){
            result.put("blockSource","MANUAL_SETTING");
            result.put("nextAction","Review the manual scheduled-event pause in Automation. Clear it only when you intend to allow normal entry checks.");
        }
        if(processing){
            result.put("phaseStartedAt",current.since().toString());
            result.put("completedHistories",current.completed());result.put("totalHistories",current.total());
            if(current.symbol()!=null)result.put("currentSymbol",current.symbol());
            if("VERIFYING_DATA".equals(current.phase())){
                var lanes=universeHistories.status();var first=lanes.path("nifty50");var second=lanes.path("otherStocks");
                if(first.has("completedHistories")){
                    result.put("completedHistories",first.path("completedHistories").asInt()+second.path("completedHistories").asInt());
                    result.put("totalHistories",51+second.path("totalHistories").asInt());
                    var loading="LOADING".equals(first.path("status").asText())?first:second;
                    if(loading.has("currentSymbol"))result.set("currentSymbol",loading.path("currentSymbol"));
                }
            }
        }else if(state.has("phaseStartedAt"))result.set("phaseStartedAt",state.get("phaseStartedAt"));
        long bucket=clock.instant().getEpochSecond()/5;
        if(active&&!processing&&"WAITING".equals(kind)&&state.path("lastAttemptBucket").asLong(-1)==bucket)
            result.put("nextScanEligibleAt",Instant.ofEpochSecond((bucket+1)*5).toString());
        if(active&&newsRetryAt>clock.millis()&&"NEWS_WAIT".equals(phase))result.put("retryAt",Instant.ofEpochMilli(newsRetryAt).toString());
        if(active&&retryAt>clock.millis())result.put("retryAt",Instant.ofEpochMilli(retryAt).toString());
        if(state.at("/selection/selected/symbol").isTextual())result.set("selectedSymbol",state.at("/selection/selected/symbol"));
        result.set("recentStages",state.has("activityHistory")?state.get("activityHistory"):json.createArrayNode());
        return result;
    }
    /** Configuration metadata only; polling this does not call the broker or AI. */
    private ObjectNode flowInfo(ObjectNode state){
        var flow=json.createObjectNode().put("version",1).put("configurationSource","server");
        flow.put("automationCheckMs",checkDelayMs).put("scanCandleMs",300000).put("scanIntervalMs",5000)
            .put("growwIndexPollMs",indexPollMs).put("growwOptionPollMs",optionPollMs)
            .put("positionMonitorMs",monitorDelayMs).put("positionEventsMs",outboxDelayMs)
            .put("dataRetryMs",30000).put("historyCount",51).put("strategyWarmupBars",100)
            .put("historyCacheBucketMs",300000).put("normalizedReadSpacingMs",250)
            .put("newsRefreshMs",state.path("newsPlan").path("refreshIntervalSeconds").asLong(60)*1000).put("maxActiveOrders",1).put("exitDeadline","14:59 IST").put("holdUntilTargetOrStop",true)
            .put("shortlistCandleReviewMs",5000).put("historyWindowDays",14).put("allAvailableCompletedCandles",true)
            .put("newsCandidateLimit",3).put("positionAssessmentMs",5000).put("paperShortSellingEnabled",true)
            .put("instrumentCacheMs",21600000).put("universeRefreshAgeMs",604800000);
        flow.put("intradayPreflightRequired",true).put("intradayProbeLimit",3).put("intradayCheckCacheMs",60000);
        flow.put("selectionHistoryWorkers",2).put("selectionCoordinatorThreads",1)
            .put("nifty50Workers",1).put("otherStockWorkers",1).put("otherCandidateBatchLimit",12).put("otherPriceScreenMs",60000).put("maxConcurrentSelectionCycles",1).put("historyQueueCapacity",2);
        flow.put("automationSchedulerEnabled",schedulerEnabled).put("growwEnabled",growwConfiguredEnabled)
            .put("aiEnabled",aiConfiguredEnabled).put("positionMonitorEnabled",monitorConfiguredEnabled);
        return flow;
    }
    public ObjectNode status(){
        var state=read(false);boolean active=owns(state);boolean working=busy.get();
        state.put("enabled",active);state.put("busy",active&&working);state.put("mode",paper.mode());
        state.put("liveExecutionEnabled",paper.liveEnabled());state.put("pollIntervalMs",5000);
        if(!active&&!Set.of("STOPPED","BROKER_PROTECTION_FAILURE").contains(state.path("state").asText())){
            state.put("state","PAUSED");state.put("message","Automatic entries are paused. Start paper auto mode explicitly after a restart.");
        }
        state.set("activity",activity(state,active,working));
        state.set("flow",flowInfo(state));
        state.remove("owner");return state;
    }
    public ObjectNode start(JsonNode body){
        if(!paper.mode().equals(body.path("mode").asText())||!("PAPER".equals(paper.mode())||paper.liveEnabled()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose the execution mode in the workspace before starting automation.");
        if(!body.path("confirmed").asBoolean(false))throw new IllegalArgumentException("Confirm automatic entries in the selected execution mode before starting.");
        tx.executeWithoutResult(status->{
            var state=read(true);
            if(!paper.mode().equals(body.path("mode").asText()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Execution mode changed; review the selected mode before starting.");
            if(owns(state)){
                if(body.has("scheduledEventRisk"))updateEventRisk(state,body.path("scheduledEventRisk").asBoolean(false));
                save(state);event("AUTO_EVENT_RISK_UPDATED",state);return;
            }
            state.removeAll();state.put("enabled",true);state.put("owner",owner);state.put("runId",UUID.randomUUID().toString());
            state.put("mode",paper.mode());state.put("startedAt",now());
            describePhase(state,"STARTING","Automatic "+paper.mode()+" selection started; validation and risk approval are required for every entry.");
            updateEventRisk(state,body.path("scheduledEventRisk").asBoolean(false));
            state.put("message","Automatic "+paper.mode()+" selection started; validation and risk approval are required for every entry.");
            save(state);event("AUTO_STARTED",state);enabled=true;retryAt=0;newsRetryAt=0;
        });
        tick();return status();
    }
    public ObjectNode stop(){
        tx.executeWithoutResult(status->{var state=read(true);state.put("enabled",false);describePhase(state,"STOPPED","Automatic entries stopped. Existing paper positions remain under stop, target and session risk monitoring.");
            state.put("message","Automatic entries stopped. Existing paper positions remain under stop, target and session risk monitoring.");
            save(state);event("AUTO_STOPPED",state);enabled=false;});
        paper.cancelUnsentLiveEntries();return status();
    }
    public void modeChanged(){
        tx.executeWithoutResult(status->{var state=read(true);for(String field:List.of("decision","selection","lastIntentId","lastDecisionAt","lastSelectionAt"))state.remove(field);
            state.put("enabled",false);state.put("mode",paper.mode());describePhase(state,"STOPPED","Execution mode changed. Review the selected mode and explicitly start automation.");save(state);enabled=false;});
        shortlistSnapshot=null;
    }
    public ObjectNode eventRisk(boolean enabledRisk){
        tx.executeWithoutResult(status->{var state=read(true);updateEventRisk(state,enabledRisk);
            save(state);event("AUTO_EVENT_RISK_UPDATED",state);});
        return status();
    }
    private void updateEventRisk(ObjectNode state,boolean enabledRisk){
        state.put("scheduledEventRisk",enabledRisk);
        state.put("scheduledEventRiskSource","MANUAL_SETTING");
        state.put("scheduledEventRiskUpdatedAt",now());
        if(!enabledRisk&&owns(state)&&"EVENT_RISK".equals(state.path("state").asText()))
            describePhase(state,"CHECKING_RULES","Manual event pause cleared. Waiting for the next scheduled check of session, news, data and risk rules.");
    }
    private void failure(Exception error){
        if(error instanceof DualUniverseHistories.HistoryFailure history){
            work("VERIFYING_DATA",history.symbol,0,0);
            error=(RuntimeException)history.getCause();
        }
        var current=progress;
        String stage=current==null?"CHECKING_RULES":current.phase();
        String symbol=current==null?null:current.symbol();
        String detail=error instanceof IllegalArgumentException&&error.getMessage()!=null&&
            (error.getMessage().startsWith("No completed, valid candles")||error.getMessage().startsWith("groww_symbol must contain"))
            ?error.getMessage():error.getClass().getSimpleName();
        var info=json.createObjectNode().put("at",now()).put("stage",stage).put("errorType",error.getClass().getSimpleName()).put("detail",detail);
        if(symbol!=null)info.put("symbol",symbol);
        tx.executeWithoutResult(status->{var state=read(true);if(!owns(state))return;state.set("lastFailure",info);save(state);});
        phase("DATA_UNAVAILABLE",(symbol==null?stage.replace('_',' '):"OTHER_STOCKS".equals(symbol)?"Other-stock price screening":symbol+" candle loading")+" failed: "+detail+
            ". Entries are blocked. Data collection retries after 30 seconds; failed data requests back off before another attempt.");
    }
    @Scheduled(fixedDelayString="${trading.automation.check-delay-ms:5000}")
    public void tick(){
        if(!schedulerEnabled||!enabled||clock.millis()<retryAt||!busy.compareAndSet(false,true))return;
        worker.submit(()->{try{cycle();}catch(Exception error){
                retryAt=clock.millis()+30000;
                failure(error);
            }finally{progress=null;busy.set(false);}});
    }
    private JsonNode bars(String symbol,long bucket){
        if(!Objects.equals(candleBuckets.get(symbol),bucket)){
            var fetched=node(gateway.availableCandles(symbol,clock));candles.put(symbol,fetched);candleBuckets.put(symbol,bucket);
        }
        return candles.get(symbol).path("bars");
    }
    private boolean active(){boolean owned=owns(read(false));if(!owned)enabled=false;return owned;}
    private boolean active(String runId){var state=read(false);return owns(state)&&runId.equals(state.path("runId").asText());}
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
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("capital","riskPerTradePercent","maxSpreadPercent","maxQuoteAgeSeconds","roundTripCostBps","strategyEnabled"))
            result.put(field,json.convertValue(p.path(field),Object.class));
        result.put("riskBudgetBasis","CONFIGURED_INTRADAY_EXPOSURE");
        result.put("minimumTurnover",p.path("minimumDayTurnover").decimalValue());result.put("allowPaperShorts",true);
        JsonNode rules=risk.path("intraday");
        for(String field:List.of("fallbackEnabled","fallbackMaxPrice","paperLeverage","realMaxLeverage","priceBandsEnabled","priceBands","capitalPriceFilterEnabled","capitalPriceBands"))
            if(rules.has(field))result.put(field,json.convertValue(rules.get(field),Object.class));
        String leverageField="REAL".equals(paper.mode())?"realMaxLeverage":"paperLeverage";
        BigDecimal leverage=rules.path(leverageField).isNumber()?rules.path(leverageField).decimalValue():BigDecimal.ONE;
        result.put("maxPositionValue",p.path("maxPositionValue").decimalValue().multiply(leverage));
        return result;
    }
    private boolean broadScanPermitted(JsonNode plan){
        try{return Set.of("READY","NEWS_UNAVAILABLE","NO_DIRECTIONAL_NEWS").contains(plan.path("status").asText())&&plan.path("configuredFeedCount").asInt()>0&&plan.path("providerErrors").isArray()&&plan.path("providerErrors").isEmpty()&&
            Duration.between(Instant.parse(plan.path("lastCollectionAt").asText()),clock.instant()).getSeconds()>=0&&Duration.between(Instant.parse(plan.path("lastCollectionAt").asText()),clock.instant()).getSeconds()<=180&&Instant.parse(plan.path("validUntil").asText()).isAfter(clock.instant());}
        catch(Exception invalid){return false;}
    }
    private boolean fallbackPermitted(JsonNode plan,JsonNode rules){
        if(!rules.path("fallbackEnabled").asBoolean()||!plan.path("candidates").isArray()||!plan.path("candidates").isEmpty()||
           !Set.of("NEWS_UNAVAILABLE","NO_DIRECTIONAL_NEWS").contains(plan.path("status").asText())||
           plan.path("planId").asText().isBlank()||plan.path("configuredFeedCount").asInt()<1||!plan.path("providerErrors").isEmpty())return false;
        try{
            long age=Duration.between(Instant.parse(plan.path("lastCollectionAt").asText()),clock.instant()).getSeconds();
            return age>=0&&age<=180&&!Instant.parse(plan.path("createdAt").asText()).isAfter(clock.instant())&&
                Instant.parse(plan.path("validUntil").asText()).isAfter(clock.instant());
        }catch(Exception invalid){return false;}
    }
    void cycle(){
        if(!active())return;
        var control=read(false);String runId=control.path("runId").asText();
        work("CHECKING_RULES",null,0,0);
        tx.executeWithoutResult(status->{var current=read(true);if(!owns(current)||!runId.equals(current.path("runId").asText()))return;
            current.put("lastCheckedAt",now());save(current);
        });
        var risk=node(paper.status());var portfolio=node(paper.portfolio());
        Instant runStart=Instant.parse(control.path("startedAt").asText());
        boolean protectionFailed=paper.intents().stream().anyMatch(row->{
            String at=row.path("protectionFailureAt").asText();
            if(at.isBlank()&&"PROTECTION_REJECTED".equals(row.path("exitReason").asText()))at=row.path("exitTime").asText();
            try{return !at.isBlank()&&!Instant.parse(at).isBefore(runStart);}catch(Exception invalid){return !at.isBlank();}
        });
        if(protectionFailed){
            tx.executeWithoutResult(t->{var current=read(true);if(!owns(current))return;
                current.put("enabled",false);describePhase(current,"BROKER_PROTECTION_FAILURE","Broker stop protection failed. New entries are paused; existing exits continue. Review Groww protection before explicitly starting automation again.");
                save(current);event("AUTO_PROTECTION_FAILURE",current);enabled=false;
            });return;
        }
        if(control.path("scheduledEventRisk").asBoolean()){
            phase("EVENT_RISK","The manual scheduled-event pause is on. Automatic stock analysis and new entries wait until this setting is cleared; existing positions remain under the risk monitor.");return;
        }
        if(risk.path("killSwitch").asBoolean()||!risk.at("/policy/strategyEnabled").asBoolean()){
            phase("BLOCKED","Kill switch or strategy-disable policy blocks automatic entries.");return;
        }
        var session=calendar.status(clock);
        if(!Boolean.TRUE.equals(session.get("entryAllowed"))){
            waitingSession(session);return;
        }
        if(!risk.path("openPositions").isEmpty()){
            reviewHeld(control,risk);
            phase("MONITORING_POSITION","An open or unresolved managed position already exists. Full candle updates and position risk monitoring continue; no new stock or entry is allowed.");return;
        }
        var p=risk.path("policy");
        int wins=Integer.parseInt(dashboard.settings().getOrDefault("stopAfterProfitableTrades",20).toString());
        long entries=paper.intents().stream().filter(row->row.has("filledQuantity")&&
            portfolio.path("sessionDate").asText().equals(row.path("sessionDate").asText())).count();
        BigDecimal loss=(risk.path("dailyLossLimit").isNumber()?risk.path("dailyLossLimit").decimalValue():
            DailyLossBudget.forMode(json.convertValue(p,PaperPlatformService.Policy.class),
                json.convertValue(risk.path("intraday"),IntradayRules.class),paper.mode()).limit()).negate();
        if(portfolio.path("profitableTrades").asLong()>=wins||entries>=p.path("maxTradesPerDay").asInt()||
            risk.path("dailyLossLatched").asBoolean()||portfolio.path("netPnl").decimalValue().compareTo(loss)<=0||
            (p.path("dailyProfitTarget").decimalValue().signum()>0&&portfolio.path("netPnl").decimalValue().compareTo(p.path("dailyProfitTarget").decimalValue())>=0)){
            phase("DAILY_LIMIT","Configured daily entry, profit-count or loss limit blocks another automatic entry.");return;
        }
        long bucket=clock.instant().getEpochSecond()/5;
        if(control.path("lastAttemptBucket").asLong(-1)==bucket)return;
        if(clock.millis()<newsRetryAt)return;
        work("NEWS_ANALYSIS",null,0,0);
        phase("NEWS_ANALYSIS","Trading AI is checking fresh market news and preparing a stock shortlist before candle confirmation.");
        JsonNode universe=node(ai.internalGet("/api/v1/universe"));
        if(!universe.has("retrievedAt")||Duration.between(Instant.parse(universe.path("retrievedAt").asText()),clock.instant()).toDays()>=7)
            universe=node(ai.internalPost("/api/v1/universe/refresh",Map.of()));
        JsonNode extended=node(ai.internalGet("/api/v1/universe/extended"));
        if(!extended.has("retrievedAt")||Duration.between(Instant.parse(extended.path("retrievedAt").asText()),clock.instant()).toDays()>=7)
            extended=node(ai.internalPost("/api/v1/universe/extended/refresh",Map.of()));
        var newsPlan=node(ai.internalPost("/api/v1/automation/news-plan",Map.of()));
        tx.executeWithoutResult(status->{var current=read(true);if(!owns(current)||!runId.equals(current.path("runId").asText()))return;
            current.set("newsPlan",newsPlan);current.put("lastNewsAt",now());
            current.put("lastNewsSummary",newsPlan.path("reasons").toString());save(current);
        });
        if(!active(runId))return;
        boolean broad=risk.at("/intraday/fallbackEnabled").asBoolean()&&broadScanPermitted(newsPlan);
        boolean fallback=!broad&&!"READY".equals(newsPlan.path("status").asText())&&fallbackPermitted(newsPlan,risk.path("intraday"));
        if(!"READY".equals(newsPlan.path("status").asText())&&!fallback&&!broad){
            newsRetryAt=clock.millis()+60000;
            phase("NEWS_WAIT",newsPlan.path("reasons").toString()+" Price fallback requires a recent successful news check with no directional candidates and enabled rules.");return;
        }
        work("VERIFYING_DATA","NIFTY",0,51);
        tx.executeWithoutResult(status->{var current=read(true);if(!owns(current)||!runId.equals(current.path("runId").asText()))return;
            current.put("lastScanStartedAt",now());save(current);
        });
        phase("VERIFYING_DATA","Loading completed NIFTY and all 50 constituent histories; missing or stale evidence blocks selection.");
        if(!universe.path("constituents").isArray()||universe.path("constituents").size()!=50)
            throw new IllegalStateException("Maintained universe is unavailable.");
        var request=json.createObjectNode().put("mode",paper.mode()).put("newsPlanId",newsPlan.path("planId").asText()).put("selectionMode",broad?"NEWS_THEN_PRICE":fallback?"PRICE_FALLBACK":"NEWS");
        var candidateSymbols=request.putArray("candidateSymbols");var seenCandidates=new HashSet<String>();
        for(var candidate:newsPlan.path("candidates")){
            String candidateSymbol=candidate.path("symbol").asText();
            if(candidateSymbol.matches("[A-Z0-9&.-]{1,32}")&&candidateSymbols.size()<3&&seenCandidates.add(candidateSymbol))candidateSymbols.add(candidateSymbol);
        }
        if(candidateSymbols.isEmpty()&&!fallback&&!broad){phase("NEWS_WAIT","No valid news candidates are available. Waiting for up to three evidenced stocks.");newsRetryAt=clock.millis()+60000;return;}
        phase("VERIFYING_DATA","Worker 1 loads NIFTY 50 context. Worker 2 screens other maintained NIFTY 500 stocks and loads an affordable candidate batch.");
        DualUniverseHistories.Batch batch;
        try{batch=universeHistories.load(universe.path("constituents"),extended.path("constituents"),seenCandidates,broad||fallback,risk,candles,candleBuckets,()->active(runId));}
        catch(CancellationException stopped){return;}
        if(!active(runId))return;
        long historyBucket=batch.bucket();
        for(var entry:batch.histories().entrySet()){candles.put(entry.getKey(),entry.getValue());candleBuckets.put(entry.getKey(),historyBucket);}
        request.set("niftyBars",candles.get("NIFTY").path("bars"));
        var snapshots=request.putArray("snapshots");var extraSnapshots=request.putArray("candidateSnapshots");
        for(JsonNode member:universe.path("constituents")){String symbol=member.path("symbol").asText();snapshots.addObject().put("symbol",symbol).set("bars",candles.get(symbol).path("bars"));}
        for(var entry:batch.histories().entrySet().stream().filter(e->!"NIFTY".equals(e.getKey())&&!batch.contextSymbols().contains(e.getKey())).sorted(Map.Entry.comparingByKey()).toList())
            extraSnapshots.addObject().put("symbol",entry.getKey()).set("bars",entry.getValue().path("bars"));
        request.set("policy",json.valueToTree(aiPolicy(risk)));
        request.put("scheduledEventRisk",control.path("scheduledEventRisk").asBoolean());
        if(!active(runId))return;
        work("ANALYZING",null,51,51);
        phase("ANALYZING",broad?"Ranking news candidates first, then affordable candle candidates across NIFTY 50 and other stocks; at most one entry.":fallback?"No directional news candidate. Checking maintained stocks below INR "+risk.at("/intraday/fallbackMaxPrice").asText()+" with candles and market context; no entry is forced.":"Comparing "+candidateSymbols.size()+" news-selected stocks using NIFTY and 50-stock market context; selecting at most one BUY or SELL candidate.");
        String sentAt=now();
        var selection=selectIntraday(request,runId,newsPlan);
        selection.set("screeningCoverage",batch.coverage());
        boolean accepted=Boolean.TRUE.equals(tx.execute(status->{
            var current=read(true);if(!owns(current)||!runId.equals(current.path("runId").asText()))return false;
            current.remove("referenceSymbol");current.remove("referenceMessage");
            var display=selection.deepCopy();
            if(display.at("/selected/market").isObject())((ObjectNode)display.at("/selected/market")).remove(List.of("niftyBars","sectorBars"));
            current.put("lastAttemptBucket",bucket);current.set("selection",display);current.put("lastSelectionAt",now());
            current.put("selectionMode",broad?"NEWS_THEN_PRICE":fallback?"PRICE_FALLBACK":"NEWS");
            var candlePlan=newsPlan.deepCopy();if(fallback||broad)candlePlan.set("candidates",selection.path("shortlist"));
            current.set("candlePlan",candlePlan);
            current.put("lastScanSummary",selection.path("selected").isObject()
                ?"Candidate: "+selection.at("/selected/symbol").asText()+". Final decision and risk checks are still required."
                :"No qualifying stock. "+selection.path("reasons").toString());
            save(current);event("AUTO_SELECTION",current);return true;
        }));
        if(!accepted)return;
        var displayPlan=newsPlan.deepCopy();if(fallback||broad)displayPlan.set("candidates",selection.path("shortlist"));
        publishShortlist(displayPlan,selection,sentAt,false);
        JsonNode selected=selection.path("selected");
        if(!selected.isObject()){
            phase("NO_TRADE",selection.path("reasons").toString());return;
        }
        String symbol=selected.path("symbol").asText();
        var decisionRequest=json.createObjectNode().put("symbol",symbol).put("side",selected.path("side").asText("BUY")).put("mode",paper.mode()).put("newsPlanId",newsPlan.path("planId").asText());
        decisionRequest.put("selectionMode",selected.path("selectionSource").asText(fallback?"PRICE_FALLBACK":"NEWS"));
        if(fallback||broad||"OTHER_STOCKS".equals(selected.path("universeScope").asText()))decisionRequest.put("selectionId",selection.path("selectionId").asText());
        decisionRequest.set("bars",candles.get(symbol).path("bars"));
        decisionRequest.set("quote",node(gateway.quoteResponse(symbol,calendar,clock)).path("quote"));
        decisionRequest.set("market",selected.path("market"));decisionRequest.set("policy",json.valueToTree(aiPolicy(risk)));
        String key="AUTO-"+control.path("runId").asText()+"-"+bucket;
        work("FINAL_DECISION",symbol,51,51);
        phase("FINAL_DECISION","Selected "+symbol+". Rechecking its fresh quote and final AI decision.");
        var decision=node(ai.internalPost("/api/v1/decisions/evaluate",decisionRequest,key));
        tx.executeWithoutResult(status->{
            var current=read(true);if(!owns(current)||!runId.equals(current.path("runId").asText()))return;
            current.set("decision",decision);current.put("lastDecisionAt",now());save(current);
            if(current.path("scheduledEventRisk").asBoolean()){
                describePhase(current,"EVENT_RISK","Scheduled-event risk was activated; the pending entry is blocked.");save(current);return;
            }
            if(!Set.of("BUY","SELL").contains(decision.path("action").asText())){
                describePhase(current,"NO_TRADE",decision.path("reasons").toString());save(current);event("AUTO_NO_TRADE",current);return;
            }
            if(!symbol.equals(decision.path("symbol").asText())||!selected.path("side").asText("BUY").equals(decision.path("action").asText())){
                describePhase(current,"REJECTED","Final AI response differs from the selected stock or direction. No entry was submitted.");save(current);return;
            }
            // Holding the control row linearizes Stop against entry. The paper engine
            // independently locks risk state, reloads the decision and obtains a fresh quote.
            work("RISK_CHECK",symbol,51,51);
            var intent=paper.submitAutomatic(new PaperPlatformService.Intent(decision.path("decisionId").asText(),paper.mode(),null,true),key);
            current.put("lastIntentId",intent.path("intentId").asText());
            describePhase(current,intent.path("state").asText(),
                intent.path("riskRejections").isArray()?intent.path("riskRejections").toString():"Managed entry reserved for "+symbol+".");
            save(current);event("AUTO_INTENT",current);
        });
    }
    private void publishShortlist(JsonNode plan,JsonNode selection,String sentAt,boolean held){
        var value=json.createObjectNode().put("state","READY").put("updatedAt",now()).put("aiSentAt",sentAt).put("aiReceivedAt",now());
        value.put("message",held?"Monitoring the held stock; candle evidence cannot change its agreed exit rules.":"All available completed candles were shared with AI. Final quotes and risk rules still determine entries in the selected mode.");
        value.put("selectionMode",selection.path("selectionMode").asText("NEWS"));
        var stocks=value.putArray("stocks");
        for(var candidate:plan.path("candidates")){
            String symbol=candidate.path("symbol").asText();var cached=candles.get(symbol);if(cached==null||stocks.size()>=3)continue;
            var card=(ObjectNode)cached.deepCopy();card.put("newsSide",candidate.path("side").asText("BUY"));
            card.put("selectionSource",candidate.path("selectionSource").asText("NEWS"));
            card.put("aiSentAt",sentAt).put("aiReceivedAt",now()).put("entryCheckStatus",held?"POSITION_HELD":"WAITING");
            for(var review:selection.path("candidates"))if(symbol.equals(review.path("symbol").asText())){
                card.set("analysis",review.path("analysis"));card.put("aiCandleCount",review.path("candleCount").asInt());
                card.put("entryCheckStatus",review.path("candidateStatus").asText());card.set("entryReasons",review.path("reasons"));card.set("universeScope",review.path("universeScope"));card.set("capitalPriceCheck",review.path("capitalPriceCheck"));
            }
            stocks.add(card);
        }
        shortlistSnapshot=value;
    }
    private void reviewHeld(JsonNode control,JsonNode risk){
        var sourcePlan=control.has("candlePlan")?control.path("candlePlan"):control.path("newsPlan");
        var plan=sourcePlan.isObject()?((ObjectNode)sourcePlan).deepCopy():json.createObjectNode();
        var candidates=plan.withArray("candidates");
        for(var position:risk.path("openPositions")){
            String symbol=position.path("symbol").asText();boolean present=false;
            for(var row:candidates)if(symbol.equals(row.path("symbol").asText()))present=true;
            if(!present&&symbol.matches("[A-Z0-9&.-]{1,32}")){
                if(candidates.size()>=3)candidates.remove(candidates.size()-1);
                candidates.addObject().put("symbol",symbol).put("side","SHORT".equals(position.path("positionSide").asText())?"SELL":"BUY");
            }
        }
        var selection=json.createObjectNode().put("selectionMode",control.path("selectionMode").asText("NEWS"));var results=selection.putArray("candidates");String sentAt=now();
        for(var row:candidates){
            String symbol=row.path("symbol").asText();if(!symbol.matches("[A-Z0-9&.-]{1,32}")||results.size()>=3)continue;
            work("MONITORING_POSITION",symbol,results.size(),Math.min(3,candidates.size()));
            var history=node(gateway.availableCandles(symbol,clock));candles.put(symbol,history);candleBuckets.put(symbol,clock.instant().getEpochSecond()/300);
            var request=json.createObjectNode().put("symbol",symbol);request.set("bars",history.path("bars"));
            request.set("quote",node(gateway.quoteResponse(symbol,calendar,clock)).path("quote"));
            var response=node(ai.internalPost("/api/v1/analysis/stock",request));
            results.addObject().put("symbol",symbol).put("candidateStatus","POSITION_HELD").put("candleCount",history.path("bars").size()).set("analysis",response.path("analysis"));
        }
        if(active())publishShortlist(plan,selection,sentAt,true);
    }
    /** Cache-only view: opening charts cannot initiate broker calls or paper orders. */
    public ObjectNode shortlist(){
        var current=read(false);var value=shortlistSnapshot==null?json.createObjectNode():shortlistSnapshot.deepCopy();
        if(!value.has("stocks"))value.putArray("stocks");
        if(!value.has("state"))value.put("state","WAITING").put("message","Waiting for the first completed news and candle analysis.");
        value.put("processing",busy.get()).put("serverTime",now()).put("checkIntervalMs",5000).put("historyWindowDays",14).put("interval","5m").put("executionAllowed",false);
        value.put("automationState",current.path("state").asText()).put("automationEnabled",owns(current));
        value.put("mode",paper.mode());
        if(!owns(current))value.put("state","PAUSED").put("message","Paper automation is paused; charts show the last received data.");
        else if("DATA_UNAVAILABLE".equals(current.path("state").asText()))value.put("state","ERROR").put("message",current.path("message").asText());
        for(var row:value.path("stocks")){
            var markers=((ObjectNode)row).putArray("markers");String symbol=row.path("symbol").asText();
            ((ObjectNode)row).set("marketDepth",gateway.marketDepth(symbol,clock,calendar,false));
            for(var position:paper.intents())if(symbol.equals(position.path("symbol").asText())&&position.path("filledQuantity").asInt()>0){
                if("REAL".equals(position.path("mode").asText())&&position.path("executionFills").isArray()){
                    for(var fill:position.path("executionFills"))markers.add(fill.deepCopy());continue;
                }
                boolean shortSide="SHORT".equals(position.path("positionSide").asText());
                markers.addObject().put("side",shortSide?"SELL":"BUY").put("time",position.path("entryTime").asText()).set("price",position.path("entryPrice"));
                if("POSITION_CLOSED".equals(position.path("state").asText()))markers.addObject().put("side",shortSide?"BUY":"SELL").put("time",position.path("exitTime").asText()).set("price",position.path("exitPrice"));
            }
        }
        return value;
    }
    public ObjectNode chart(String symbol){
        if(symbol==null||!symbol.matches("[A-Z0-9&.-]{1,32}"))throw new IllegalArgumentException("Choose a valid stock symbol.");
        var result=json.createObjectNode().put("symbol",symbol).put("mode",paper.mode());
        try{if(!symbol.equals("NIFTY")||clock.millis()>=referenceRetryAt)bars(symbol,clock.instant().getEpochSecond()/300);}
        catch(Exception unavailable){result.put("dataStatus","Candle history unavailable; displaying recorded fills only.");}
        JsonNode cached=candles.get(symbol);
        result.set("bars",cached==null?json.createArrayNode():cached.path("bars"));
        var markers=result.putArray("markers");
        Instant first=cached!=null&&cached.path("bars").isArray()&&!cached.path("bars").isEmpty()
            ?java.time.OffsetDateTime.parse(cached.path("bars").get(0).path("timestamp").asText()).toInstant():Instant.MIN;
        int outside=0;
        for(var row:paper.intents())if(symbol.equals(row.path("symbol").asText())&&row.has("filledQuantity")){
            if("REAL".equals(row.path("mode").asText())&&row.path("executionFills").isArray()){
                for(var fill:row.path("executionFills")){if(!Instant.parse(fill.path("time").asText()).isBefore(first))markers.add(fill.deepCopy());else outside++;}continue;
            }
            if(!Instant.parse(row.path("entryTime").asText()).isBefore(first))
                markers.addObject().put("side","SHORT".equals(row.path("positionSide").asText())?"SELL":"BUY").put("time",row.path("entryTime").asText()).set("price",row.path("entryPrice"));
            else outside++;
            if("POSITION_CLOSED".equals(row.path("state").asText())){
                if(!Instant.parse(row.path("exitTime").asText()).isBefore(first))markers.addObject().put("side","SHORT".equals(row.path("positionSide").asText())?"BUY":"SELL")
                    .put("time",row.path("exitTime").asText()).set("price",row.path("exitPrice"));
                else outside++;
            }
        }
        result.set("marketDepth",gateway.marketDepth(symbol,clock,calendar,true));
        result.put("outsideWindowMarkers",outside);
        result.put("updatedAt",cached==null?now():cached.path("receivedAt").asText());
        return result;
    }
    private ObjectNode selectIntraday(ObjectNode request,String runId,JsonNode newsPlan){
        var primary=json.createObjectNode();var seed=primary.putArray("candidates");
        var allowed=new HashSet<String>();request.path("candidateSymbols").forEach(row->allowed.add(row.asText()));
        for(var candidate:newsPlan.path("candidates")){
            String symbol=candidate.path("symbol").asText();var history=candles.get(symbol);
            if(!allowed.contains(symbol)||history==null||!history.path("bars").isArray()||history.path("bars").isEmpty())continue;
            var close=history.path("bars").get(history.path("bars").size()-1).path("close");
            if(close.isNumber()&&close.decimalValue().signum()>0)seed.addObject().put("symbol",symbol).put("side",candidate.path("side").asText("NEGATIVE".equals(candidate.path("newsBias").asText())?"SELL":"BUY"))
                .put("technicalQualified",true).put("candidateStatus","PENDING_INTRADAY_CHECK").put("selectionSource","NEWS").set("latestPrice",close);
        }
        work("INTRADAY_CHECK",null,0,0);phase("INTRADAY_CHECK","Checking Groww MIS suitability before final stock selection; up to three fresh read-only preflights per cycle.");
        int checked=intradayScreen.checkPending(primary,()->active(runId),3);
        if(!active(runId))return json.createObjectNode().put("status","NO_TRADE").putNull("selected");
        intradayScreen.attach(request);
        work("ANALYZING",null,51,51);phase("ANALYZING","AI is comparing candle evidence and Groww intraday preflight results. Unknown or rejected MIS support cannot authorize selection.");
        var selection=node(ai.internalPost("/api/v1/automation/select",request.deepCopy()));
        work("INTRADAY_CHECK",null,0,0);
        if(intradayScreen.checkPending(selection,()->active(runId),3-checked)>0&&active(runId)){
            intradayScreen.attach(request);work("ANALYZING",null,51,51);selection=node(ai.internalPost("/api/v1/automation/select",request.deepCopy()));
        }
        var selected=selection.path("selected");
        if(selected.isObject()&&!intradayScreen.approved(selected.path("symbol").asText(),selected.path("side").asText())){
            selection.putNull("selected").put("status","NO_TRADE");selection.putArray("reasons").add("No stock has passed a fresh Groww MIS preflight for this entry direction. No delivery order will be substituted.");
        }
        selection.put("intradayRequired",true).put("intradayProbeLimit",3);
        return selection;
    }
    @PreDestroy public void shutdown(){enabled=false;worker.shutdownNow();universeHistories.close();}
}
