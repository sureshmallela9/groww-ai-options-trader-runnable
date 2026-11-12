package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.tradingai.TradingAiClient;
import com.optionstrader.groww.GrowwReadBudget;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Shared risk gates; paper fills are synthetic, real fills are reconciled by LiveExecutionService. */
@Service
public class PaperPlatformService {
    public record Intent(String decisionId,String mode,Integer quantity,boolean confirmed) {}
    // Ignore the retired cash cap in existing database records and older clients.
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"dailyLossAmount"})
    public record Policy(BigDecimal capital,BigDecimal riskPerTradePercent,BigDecimal dailyLossPercent,
        BigDecimal maxPositionValue,int maxOpenPositions,int maxTradesPerDay,int maxConsecutiveLosses,
        BigDecimal maxSpreadPercent,BigDecimal maxSlippagePercent,int maxQuoteAgeSeconds,int maxSignalAgeSeconds,
        BigDecimal roundTripCostBps,boolean strategyEnabled,BigDecimal minimumDayTurnover,BigDecimal dailyProfitTarget) {
        public Policy {
            if(dailyProfitTarget==null)dailyProfitTarget=BigDecimal.ZERO;
        }
        public static Policy defaults(){return new Policy(new BigDecimal("10000"),new BigDecimal("0.5"),
            new BigDecimal("2"),new BigDecimal("10000"),1,5,3,new BigDecimal("0.2"),
            new BigDecimal("0.2"),10,300,new BigDecimal("20"),true,new BigDecimal("1000000"),BigDecimal.ZERO);}
        public void validate(){
            if(capital==null||riskPerTradePercent==null||dailyLossPercent==null||maxPositionValue==null||
               maxSpreadPercent==null||maxSlippagePercent==null||roundTripCostBps==null||minimumDayTurnover==null||dailyProfitTarget==null||dailyProfitTarget.signum()<0||dailyProfitTarget.compareTo(new BigDecimal("10000000"))>0||
               capital.signum()<=0||capital.compareTo(new BigDecimal("10000000"))>0||
               riskPerTradePercent.signum()<=0||riskPerTradePercent.compareTo(new BigDecimal("2"))>0||
               dailyLossPercent.signum()<=0||dailyLossPercent.compareTo(new BigDecimal("10"))>0||
               maxPositionValue.signum()<=0||maxPositionValue.compareTo(capital)>0||
               maxOpenPositions<1||maxOpenPositions>5||maxTradesPerDay<1||maxTradesPerDay>20||
               maxConsecutiveLosses<1||maxConsecutiveLosses>10||maxQuoteAgeSeconds<1||maxQuoteAgeSeconds>60||
               maxSignalAgeSeconds<1||maxSignalAgeSeconds>300||maxSpreadPercent.signum()<=0||
               maxSpreadPercent.compareTo(BigDecimal.ONE)>0||maxSlippagePercent.signum()<0||
               maxSlippagePercent.compareTo(BigDecimal.ONE)>0||roundTripCostBps.signum()<0||
               roundTripCostBps.compareTo(new BigDecimal("100"))>0||minimumDayTurnover.signum()<0)
                throw new IllegalArgumentException("Risk policy is outside supported development bounds.");
        }
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ExecutionModeService executionModes;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private LiveExecutionService live;
    public String mode(){return executionModes==null?"PAPER":executionModes.mode();}
    public boolean liveEnabled(){return executionModes!=null&&executionModes.enabled();}
    private boolean real(JsonNode row){return "REAL".equals(row.path("mode").asText());}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final NormalizedMarketGateway gateway;
    private final TradingAiClient ai;
    private final SessionCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final boolean monitorEnabled;
    @Value("${trading.platform.intraday-defaults:legacy}") private String intradayDefaultsProfile="legacy";
    private static final long AI_REFRESH_SECONDS=5;
    private boolean shortPosition(JsonNode row){return "SHORT".equals(row.path("positionSide").asText());}
    private BigDecimal exitQuote(JsonNode row,NormalizedMarketGateway.QuoteData quote){return shortPosition(row)?quote.ask():quote.bid();}
    private BigDecimal profit(JsonNode row,BigDecimal exit,int quantity){return (shortPosition(row)?amount(row,"entryPrice").subtract(exit):exit.subtract(amount(row,"entryPrice"))).multiply(BigDecimal.valueOf(quantity));}
    private java.util.function.Supplier<Map<String,Object>> dashboardRules=Map::of;
    private java.util.function.BooleanSupplier automationEnabled=()->false;
    public void configureAutomation(java.util.function.Supplier<Map<String,Object>> rules,
                                    java.util.function.BooleanSupplier enabled){
        dashboardRules=rules;automationEnabled=enabled;if(live!=null){
            live.configureAutomation(enabled);
            live.configureRiskValidator((row,quote)->transaction.executeWithoutResult(tx->{
                var risk=lockedRisk();var policy=policy(risk);
                boolean shortEntry="SHORT".equals(row.path("positionSide").asText());BigDecimal limit=amount(row,"entryLimitPrice");
                String priceRejection=intraday(risk).capitalPriceRejection(policy.capital(),shortEntry?quote.bid():quote.ask());
                if(priceRejection!=null)throw new IllegalStateException("Dispatch risk check failed: "+priceRejection);
                var worstQuote=new NormalizedMarketGateway.QuoteData(quote.symbol(),quote.last(),shortEntry?limit:quote.bid(),shortEntry?quote.ask():limit,quote.timestamp(),quote.verified(),quote.buyAllowed(),quote.equity(),quote.tickSize(),quote.dayVolume(),quote.bidQuantity(),quote.askQuantity(),quote.sellAllowed());
                var errors=validate((ObjectNode)row.path("decision"),worstQuote,row.path("quantity").asInt(),risk,policy,row.path("intentId").asText(),amount(row,"marginRequired"));
                if(!errors.isEmpty())throw new IllegalStateException("Dispatch risk check failed: "+String.join("; ",errors));
                row.put("maxIntradayLeverage",intraday(risk).realMaxLeverage().min(row.path("maxIntradayLeverage").isNumber()?amount(row,"maxIntradayLeverage"):BigDecimal.ONE));
                row.put("approvedCapital",policy.capital().min(policy.maxPositionValue()).min(amount(row,"approvedCapital")));
                row.put("costBps",policy.roundTripCostBps().max(amount(row,"costBps")));
            }));
        }
    }
    public PaperPlatformService(JdbcTemplate db,ObjectMapper json,NormalizedMarketGateway gateway,
            TradingAiClient ai,SessionCalendar calendar,Clock clock,PlatformTransactionManager manager,
            @Value("${trading.platform.monitor-enabled:true}") boolean monitorEnabled){
        this.db=db;this.json=json;this.gateway=gateway;this.ai=ai;this.calendar=calendar;this.clock=clock;
        this.transaction=new TransactionTemplate(manager);this.monitorEnabled=monitorEnabled;
    }
    private ObjectNode read(String value){
        try{return (ObjectNode)json.readTree(value);}catch(Exception error){throw new IllegalStateException("Stored platform record is invalid.",error);}
    }
    private String encode(Object value){
        try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalArgumentException("Invalid platform JSON.",error);}
    }
    private Policy policy(ObjectNode root){
        try{
            ObjectNode merged=json.valueToTree(Policy.defaults());
            if(root.path("policy").isObject())root.path("policy").fields().forEachRemaining(field->merged.set(field.getKey(),field.getValue()));
            Map<String,Object> settings=dashboardRules.get();
            for(var pair:Map.of("capital","capitalLimit","riskPerTradePercent","riskPerTradePercent",
                    "dailyLossPercent","dailyLossPercent","maxTradesPerDay","maxEntryOrdersPerDay").entrySet()) {
                if(settings.containsKey(pair.getValue())) {
                    BigDecimal bound=new BigDecimal(settings.get(pair.getValue()).toString());
                    merged.put(pair.getKey(),merged.path(pair.getKey()).decimalValue().min(bound));
                }
            }
            merged.put("maxOpenPositions",1); // One active trade is a hard platform invariant.
            merged.put("maxPositionValue",merged.path("maxPositionValue").decimalValue().min(merged.path("capital").decimalValue()));
            return json.treeToValue(merged,Policy.class);
        }
        catch(Exception error){throw new IllegalStateException("Stored risk policy is invalid.",error);}
    }
    private ObjectNode lockedRisk(){
        return read(db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='RISK' FOR UPDATE",String.class));
    }
    private IntradayRules intraday(ObjectNode root){
        try{
            var rules=root.has("intraday")?json.treeToValue(root.get("intraday"),IntradayRules.class):
                switch(intradayDefaultsProfile){case "paper-price-v1"->IntradayRules.paperDefaults();case "legacy"->IntradayRules.defaults();default->throw new IllegalArgumentException("Unknown intraday defaults profile.");};
            rules.validate();return rules;
        }catch(Exception error){throw new IllegalStateException("Stored intraday rules are invalid.",error);}
    }
    private DailyLossBudget dailyLossBudget(ObjectNode root,Policy policy){return DailyLossBudget.forMode(policy,intraday(root),mode());}
    private String now(){return clock.instant().toString();}
    private boolean exitDeadlineReached(){return !clock.instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalTime().isBefore(java.time.LocalTime.of(14,59));}
    private String sessionDate(){return clock.instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();}
    private BigDecimal amount(JsonNode node,String field){
        if(!node.path(field).isNumber())throw new IllegalArgumentException("Required numeric field is missing: "+field);
        return node.path(field).decimalValue();
    }
    private String required(JsonNode node,String field){
        String value=node.path(field).asText();
        if(value.isBlank())throw new IllegalArgumentException("Required field is missing: "+field);
        return value;
    }
    private void audit(String type,JsonNode value){
        String correlation=UUID.randomUUID().toString();
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet){
            Object supplied=servlet.getRequest().getAttribute("correlationId");
            if(supplied!=null)correlation=supplied.toString();
        }
        db.update("INSERT INTO PLATFORM_AUDIT(AUDIT_ID,EVENT_TYPE,PAYLOAD_JSON,CREATED_AT) VALUES(?,?,?,?)",
            UUID.randomUUID().toString(),type,encode(Map.of("correlationId",correlation,"record",value)),now());
    }
    public List<ObjectNode> intents(){String selected=mode();return allIntents().stream().filter(row->selected.equals(row.path("mode").asText("PAPER"))).toList();}
    public List<ObjectNode> allIntents(){
        return db.query("SELECT PAYLOAD_JSON FROM PAPER_INTENTS ORDER BY UPDATED_AT DESC",(row,index)->read(row.getString(1)))
            .stream().sorted(java.util.Comparator.comparing((ObjectNode value)->Instant.parse(value.path("updatedAt").asText())).reversed()).toList();
    }
    public ObjectNode intent(String id){
        var found=db.query("SELECT PAYLOAD_JSON FROM PAPER_INTENTS WHERE INTENT_ID=?",(row,index)->read(row.getString(1)),id);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Paper intent not found.");
        return found.getFirst();
    }
    public Map<String,Object> status(){
        ObjectNode root=read(db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='RISK'",String.class));
        var policy=policy(root);var budget=dailyLossBudget(root,policy);var all=intents();var positions=all.stream().filter(this::activeEntry).toList();
        return Map.ofEntries(Map.entry("mode",mode()),Map.entry("liveExecutionEnabled",liveEnabled()),
            Map.entry("autoEntryEnabled",automationEnabled.getAsBoolean()),Map.entry("killSwitch",root.path("killSwitch").asBoolean()),
            Map.entry("policy",policy),Map.entry("intraday",intraday(root)),Map.entry("calendar",calendar.status(clock)),
            Map.entry("openPositions",positions),Map.entry("dailyLossLimit",budget.limit()),
            Map.entry("dailyLossBasis","CONFIGURED_INTRADAY_EXPOSURE"),Map.entry("dailyLossCashAllocation",budget.cashAllocation()),
            Map.entry("dailyLossLeverage",budget.leverage()),Map.entry("dailyLossExposureBase",budget.exposureBase()),
            Map.entry("riskBudgetBasis","CONFIGURED_INTRADAY_EXPOSURE"),Map.entry("riskExposureBase",budget.exposureBase()),
            Map.entry("riskPerTradeLimit",budget.perTradeRiskLimit(policy)),
            Map.entry("dailyLossLatched",dailyLossLatched(all)),
            Map.entry("pendingEvents",db.queryForObject("SELECT COUNT(*) FROM PLATFORM_OUTBOX WHERE STATUS='PENDING'",Integer.class)));
    }
    private boolean dailyLossLatched(List<ObjectNode> rows){return rows.stream().anyMatch(row->
        sessionDate().equals(row.path("dailyLossTriggeredOn").asText())||
        (closedToday(row)&&"DAILY_LOSS_LIMIT".equals(row.path("exitReason").asText())));}
    private boolean activeEntry(ObjectNode value){return List.of("POSITION_OPEN","EXIT_PENDING","ENTRY_PENDING","ORDER_SUBMITTED","ACCEPTED","FILLED","APPROVED","EXIT_SUBMITTED").contains(value.path("state").asText());}
    private boolean dailyTargetLatched(List<ObjectNode> rows){return rows.stream().anyMatch(row->closedToday(row)&&"DAILY_PROFIT_TARGET".equals(row.path("exitReason").asText()));}
    private boolean open(ObjectNode value){return real(value)?ExecutionModeService.active(value)&&value.path("remainingQuantity").asInt()>0:List.of("POSITION_OPEN","EXIT_PENDING").contains(value.path("state").asText());}
    public Map<String,Object> portfolio(){
        var rows=intents();
        BigDecimal realized=rows.stream().filter(row->closedToday(row)||(real(row)&&open(row)&&sessionDate().equals(row.path("sessionDate").asText()))).map(row->row.path("realizedPnl").decimalValue()).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal unrealized=rows.stream().filter(this::open).map(row->row.path("unrealizedPnl").decimalValue()).reduce(BigDecimal.ZERO,BigDecimal::add);
        long wins=rows.stream().filter(this::closedToday).filter(row->row.path("realizedPnl").decimalValue().signum()>0).count();
        boolean stale=rows.stream().filter(this::open).anyMatch(row->row.has("incident")||row.has("brokerIncident")||
            (real(row)&&(!row.has("lastBrokerReceivedAt")||Instant.parse(row.path("lastBrokerReceivedAt").asText()).isBefore(clock.instant().minusSeconds(10))))||!row.has("quoteTimestamp")||
            Duration.between(Instant.parse(row.path("quoteTimestamp").asText()),clock.instant()).getSeconds()>10);
        return Map.of("mode",mode(),"sessionDate",sessionDate(),"realizedPnl",realized,"unrealizedPnl",unrealized,
            "netPnl",realized.add(unrealized),"profitableTrades",wins,"hasStaleMarks",stale,"positions",rows,
            "updatedAt",now(),"liveExecutionEnabled",liveEnabled());
    }
    private void transition(ObjectNode value,String state,String reason){
        value.put("state",state);value.put("updatedAt",now());
        var timeline=value.withArray("timeline").addObject();
        timeline.put("state",state);timeline.put("at",now());timeline.put("reason",reason);
    }
    private String hash(Intent body){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            encode(body).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    public ObjectNode submit(Intent body,String key){
        return submit(body,key,false);
    }
    public ObjectNode submitAutomatic(Intent body,String key){return submit(body,key,true);}
    public void cancelUnsentLiveEntries(){
        transaction.executeWithoutResult(tx->{lockedRisk();for(var row:allIntents())if(real(row)&&activeEntry(row)&&
            "PREPARED".equals(row.path("brokerCommands").path(0).path("phase").asText())){
                row.put("liveExitRequested",true);transition(row,"CANCELLED","Automation stopped before broker dispatch.");save(row);
            }
        });
    }
    private ObjectNode submit(Intent body,String key,boolean exclusive){
        if(body==null||body.decisionId()==null||!body.decisionId().matches("DEC-[A-Za-z0-9-]{8,80}")||
           !mode().equals(body.mode())||!("PAPER".equals(body.mode())||liveEnabled())||!body.confirmed()||key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("A decision ID, selected execution mode, confirmation and Idempotency-Key are required.");
        // A completed request can be replayed even when the AI or broker is offline.
        var replay=db.query("SELECT PAYLOAD_JSON,REQUEST_HASH FROM PAPER_INTENTS WHERE REQUEST_KEY=? OR DECISION_ID=?",
            (row,index)->Map.entry(read(row.getString(1)),row.getString(2)),key,body.decisionId());
        if(!replay.isEmpty()){
            if(!replay.getFirst().getValue().equals(hash(body)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key or decision was used with different terms.");
            return replay.getFirst().getKey();
        }
        // Only the authenticated AI service supplies decision terms. No caller-supplied prices are trusted.
        ObjectNode decision=json.valueToTree(ai.internalGet("/api/v1/decisions/"+body.decisionId()));
        String symbol=required(decision,"symbol");
        var sourced=gateway.quote(symbol);
        if("REAL".equals(body.mode())&&(sourced.bid()==null||sourced.ask()==null||sourced.tickSize()==null||sourced.tickSize().signum()<=0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Verified executable prices and tick size are required for a real entry.");
        var quote="REAL".equals(body.mode())?new NormalizedMarketGateway.QuoteData(sourced.symbol(),sourced.last(),
            LiveExecutionService.tick(sourced.bid(),sourced.tickSize(),false),LiveExecutionService.tick(sourced.ask(),sourced.tickSize(),true),
            sourced.timestamp(),sourced.verified(),sourced.buyAllowed(),sourced.equity(),sourced.tickSize(),sourced.dayVolume(),sourced.bidQuantity(),sourced.askQuantity(),sourced.sellAllowed()):sourced;
        LiveMarginSizer.Approval marginApproval=null;int quantityBeforeMargin=0;ObjectNode sizingLimits=json.createObjectNode();
        if("REAL".equals(body.mode())){
            executionModes.verifyIp();executionModes.assertBrokerFlat();
            int proposed=body.quantity()==null?decision.path("requestedQuantity").asInt():body.quantity();
            if(proposed<1||proposed>decision.path("requestedQuantity").asInt())throw new IllegalArgumentException("Invalid live quantity.");
            var riskSnapshot=read(db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='RISK'",String.class));
            var cap=policy(riskSnapshot);var limits=intraday(riskSnapshot);
            String priceRejection=limits.capitalPriceRejection(cap.capital(),"SELL".equals(decision.path("action").asText())?quote.bid():quote.ask());
            if(priceRejection!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,priceRejection);
            if(body.quantity()==null){
                BigDecimal entry="SELL".equals(decision.path("action").asText())?quote.bid():quote.ask();
                boolean shortEntry="SELL".equals(decision.path("action").asText());
                BigDecimal stopTrigger=LiveExecutionService.tick(amount(decision,"stopLoss"),quote.tickSize(),shortEntry);
                BigDecimal riskStop=LiveExecutionService.protectionLimit(stopTrigger,quote.tickSize(),shortEntry?"BUY":"SELL");
                BigDecimal unitRisk=(shortEntry?riskStop.subtract(entry):entry.subtract(riskStop))
                    .add(entry.multiply(cap.roundTripCostBps()).divide(new BigDecimal("10000")));
                if(unitRisk.signum()<=0)throw new IllegalStateException("Valid stop risk is required before live sizing.");
                var budget=DailyLossBudget.forMode(cap,limits,"REAL");
                int byRisk=budget.perTradeRiskLimit(cap).divide(unitRisk,0,RoundingMode.DOWN).intValueExact();
                int byDaily=budget.limit().add(dailyPnl(intents())).max(BigDecimal.ZERO).divide(unitRisk,0,RoundingMode.DOWN).intValueExact();
                int byExposure=budget.exposureBase().divide(entry,0,RoundingMode.DOWN).intValueExact();
                int byDepth=Math.min(quote.bidQuantity(),quote.askQuantity());
                sizingLimits.put("aiProposal",proposed).put("stopRisk",byRisk).put("remainingDailyRisk",byDaily).put("exposure",byExposure).put("executableDepth",byDepth);
                proposed=Math.min(proposed,Math.min(Math.min(byRisk,byDaily),Math.min(byExposure,byDepth)));
                if(proposed<1)throw new IllegalStateException("No whole share fits the current entry risk, daily risk, exposure and executable depth limits.");
            }
            quantityBeforeMargin=proposed;
            marginApproval=live.sizeEntry(symbol,decision.path("action").asText(),proposed,
                "SELL".equals(decision.path("action").asText())?quote.bid():quote.ask(),
                cap.capital().min(cap.maxPositionValue()),limits.realMaxLeverage(),cap.roundTripCostBps(),body.quantity()==null);
        }
        final LiveMarginSizer.Approval approved=marginApproval;final int riskSizedQuantity=quantityBeforeMargin;
        return transaction.execute(tx->{
            var root=lockedRisk();var policy=policy(root);policy.validate();var intraday=intraday(root);
            var previous=db.query("SELECT PAYLOAD_JSON,REQUEST_HASH FROM PAPER_INTENTS WHERE REQUEST_KEY=? OR DECISION_ID=?",
                (row,index)->Map.entry(read(row.getString(1)),row.getString(2)),key,body.decisionId());
            if(!previous.isEmpty()){
                if(!previous.getFirst().getValue().equals(hash(body)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key or decision was used with different terms.");
                return previous.getFirst().getKey();
            }
            if(!body.mode().equals(root.path("executionMode").asText("PAPER")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Execution mode changed while entry was being checked.");
            var value=json.createObjectNode();value.put("intentId",UUID.randomUUID().toString());
            value.put("decisionId",body.decisionId());value.put("symbol",symbol);value.put("mode",body.mode());
            boolean shortEntry="SELL".equals(decision.path("action").asText());
            value.put("positionSide",shortEntry?"SHORT":"LONG");value.put("entryAction",shortEntry?"SELL":"BUY");value.put("exitAction",shortEntry?"BUY":"SELL");
            value.put("automatic",exclusive);value.put("createdAt",now());value.put("sessionDate",sessionDate());value.set("decision",decision);
            transition(value,"SIGNAL_GENERATED","AI proposal received.");
            transition(value,"RISK_VALIDATION","Independent Java hard limits evaluated.");
            int quantity=approved!=null?approved.quantity():body.quantity()==null?decision.path("requestedQuantity").asInt():body.quantity();
            var errors=validate(decision,quote,quantity,root,policy,"",approved==null?null:approved.required());
            if(exclusive&&intents().stream().anyMatch(this::open))errors.add("Automatic mode monitors one open position at a time.");
            if(!errors.isEmpty()){
                value.set("riskRejections",json.valueToTree(errors));
                transition(value,"REJECTED",String.join("; ",errors));
            }else if("REAL".equals(body.mode())){
                live.prepare(value,quote,quantity);approved.record(value);value.put("approvedCapital",policy.capital().min(policy.maxPositionValue()));value.put("costBps",policy.roundTripCostBps());
                value.put("requestedQuantity",decision.path("requestedQuantity").asInt());
                value.put("quantityBeforeMargin",riskSizedQuantity).put("quantityAdjustedForBrokerMargin",quantity<riskSizedQuantity)
                    .put("quantityAdjustedForRiskOrDepth",riskSizedQuantity<decision.path("requestedQuantity").asInt());
                value.set("sizingLimits",sizingLimits);
                var riskBudget=dailyLossBudget(root,policy);
                value.put("riskBudgetBasis","CONFIGURED_INTRADAY_EXPOSURE").put("riskExposureBase",riskBudget.exposureBase()).put("riskPerTradeLimit",riskBudget.perTradeRiskLimit(policy));
            }else{
                BigDecimal entry=shortEntry?quote.bid():quote.ask(); // Executable-side paper simulation only.
                BigDecimal initialExit=shortEntry?quote.ask():quote.bid();
                value.put("quantity",quantity);value.put("remainingQuantity",quantity);
                BigDecimal leverage=effectiveLeverage(decision,intraday);
                value.put("paperLeverage",leverage);value.put("paperMarginAssumption",true);
                value.put("positionValue",entry.multiply(BigDecimal.valueOf(quantity)));
                value.put("marginRequired",entry.multiply(BigDecimal.valueOf(quantity)).divide(leverage,2,RoundingMode.UP));
                value.set("priceRiskBand",decision.path("priceRiskBand"));
                value.put("entryPrice",entry);value.put("lastPrice",quote.last());
                value.put("stopLoss",amount(decision,"stopLoss"));value.put("targetPrice",amount(decision,"targetPrice"));
                value.put("quoteTimestamp",quote.timestamp().toString());value.put("entryTime",now());
                BigDecimal initialFees=entry.add(initialExit).multiply(BigDecimal.valueOf(quantity))
                    .multiply(policy.roundTripCostBps()).divide(new BigDecimal("20000"),2,RoundingMode.HALF_UP);
                value.put("lastExecutableExitPrice",initialExit);
                value.put("unrealizedPnl",profit(value,initialExit,quantity).subtract(initialFees));
                value.put("estimatedCharges",initialFees);value.put("realizedPnl",BigDecimal.ZERO);
                value.put("orderId","PAPER-"+UUID.randomUUID());value.put("filledQuantity",quantity);
                value.put("lastAiPrice",quote.last());value.put("lastAiBucket",clock.instant().getEpochSecond()/AI_REFRESH_SECONDS);
                for(String state:List.of("APPROVED","ORDER_SUBMITTED","ACCEPTED","FILLED","POSITION_OPEN"))
                    transition(value,state,"Paper simulation only; no broker order.");
            }
            db.update("INSERT INTO PAPER_INTENTS(INTENT_ID,DECISION_ID,REQUEST_KEY,REQUEST_HASH,STATE,SYMBOL,SESSION_DATE,PAYLOAD_JSON,UPDATED_AT) VALUES(?,?,?,?,?,?,?,?,?)",
                value.path("intentId").asText(),body.decisionId(),key,hash(body),value.path("state").asText(),symbol,sessionDate(),encode(value),now());
            if(open(value)&&!real(value))enqueue(value,"POSITION_OPEN");
            audit("INTENT_"+value.path("state").asText(),value);
            return value;
        });
    }
    private List<String> validate(ObjectNode decision,NormalizedMarketGateway.QuoteData quote,int quantity,ObjectNode root,Policy p){
        return validate(decision,quote,quantity,root,p,"",null);
    }
    private List<String> validate(ObjectNode decision,NormalizedMarketGateway.QuoteData quote,int quantity,ObjectNode root,Policy p,String ignoreId,BigDecimal brokerMargin){
        if("REAL".equals(decision.path("mode").asText())){
            decision=decision.deepCopy();boolean shortEntry="SELL".equals(decision.path("action").asText());
            decision.put("stopLoss",LiveExecutionService.tick(amount(decision,"stopLoss"),quote.tickSize(),shortEntry));
            decision.put("targetPrice",LiveExecutionService.tick(amount(decision,"targetPrice"),quote.tickSize(),!shortEntry));
        }
        var errors=new ArrayList<String>();var instant=clock.instant();var all=intents().stream().filter(row->!ignoreId.equals(row.path("intentId").asText())).toList();
        var rules=intraday(root);
        if(exitDeadlineReached())errors.add("No entries at or after the 14:59 IST exit deadline.");
        if(new BigDecimal(dashboardRules.get().getOrDefault("legacyActivePositions",0).toString()).signum()>0)errors.add("An existing legacy demo record is open; resolve it before a new managed paper entry.");
        if(allIntents().stream().filter(row->!ignoreId.equals(row.path("intentId").asText())).anyMatch(this::activeEntry))errors.add("Only one active order or position is allowed; wait for its confirmed exit.");
        if(root.path("killSwitch").asBoolean())errors.add("Kill switch is active.");
        if(!p.strategyEnabled())errors.add("The strategy is disabled by administrator policy.");
        if(!Boolean.TRUE.equals(calendar.status(clock).get("entryAllowed")))errors.add("No verified active entry session.");
        boolean shortEntry="SELL".equals(decision.path("action").asText());
        boolean knownStrategy=("NSE_CASH_MOMENTUM_V1".equals(decision.path("strategy").asText())&&decision.path("strategyVersion").asInt()==1&&!shortEntry)||
            ("NSE_CASH_MOMENTUM_V2".equals(decision.path("strategy").asText())&&decision.path("strategyVersion").asInt()==2);
        if(!("BUY".equals(decision.path("action").asText())||shortEntry)||!"ENTRY".equals(decision.path("intentType").asText())||
           !"EQUITY".equals(decision.path("instrumentType").asText())||!"NSE".equals(decision.path("exchange").asText())||
           !mode().equals(decision.path("mode").asText())||!knownStrategy)
            errors.add("Only approved directional NSE cash entry proposals for the selected mode are supported.");
        try{
            Instant created=Instant.parse(required(decision,"createdAt")),until=Instant.parse(required(decision,"validUntil"));
            if(created.isAfter(instant.plusSeconds(2))||Duration.between(created,instant).compareTo(Duration.ofSeconds(p.maxSignalAgeSeconds()))>0||
               !until.isAfter(instant)||until.isAfter(created.plusSeconds(300)))errors.add("Signal is stale, expired or has invalid validity.");
        }catch(Exception error){errors.add("Signal timestamps are invalid.");}
        if(quote.timestamp()==null||quote.timestamp().isAfter(instant.plusSeconds(2))||
           Duration.between(quote.timestamp(),instant).compareTo(Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0)errors.add("Market quote is stale or undated.");
        if(!quote.verified()||!(shortEntry?quote.sellAllowed():quote.buyAllowed())||!quote.equity()||quote.tickSize()==null)errors.add("Equity instrument eligibility was not verified.");
        if(!quote.symbol().equals(decision.path("symbol").asText()))errors.add("Quote instrument differs from the decision symbol.");
        if(quote.bid()==null||quote.ask()==null||quote.ask().compareTo(quote.bid())<0){errors.add("Executable bid/ask is missing.");return errors;}
        BigDecimal spread=quote.ask().subtract(quote.bid()).multiply(new BigDecimal("100")).divide(quote.ask(),8,RoundingMode.HALF_UP);
        if(spread.compareTo(p.maxSpreadPercent())>0)errors.add("Spread limit exceeded.");
        if(quantity<1||quantity>decision.path("requestedQuantity").asInt())errors.add("Quantity exceeds the proposed whole-share quantity.");
        if(quote.dayVolume()==null||quote.dayVolume().multiply(quote.last()).compareTo(p.minimumDayTurnover())<0)
            errors.add("Verified day turnover is unavailable or below the hard liquidity limit.");
        if(quote.askQuantity()<quantity||quote.bidQuantity()<quantity)errors.add("Current executable depth cannot support a full paper fill.");
        BigDecimal reference=amount(decision,"referencePrice"),stop=amount(decision,"stopLoss"),target=amount(decision,"targetPrice");
        BigDecimal executableEntry=shortEntry?quote.bid():quote.ask();
        String priceRejection=rules.capitalPriceRejection(p.capital(),executableEntry);
        if(priceRejection!=null)errors.add(priceRejection);
        if("PRICE_FALLBACK".equals(decision.path("selectionMode").asText())&&
           (!rules.fallbackEnabled()||executableEntry.compareTo(rules.fallbackMaxPrice())>=0))
            errors.add("Price fallback is disabled or the executable price is not strictly below its ceiling.");
        var band=rules.band(reference);
        if(band!=null){
            BigDecimal stopDistance=shortEntry?stop.subtract(reference):reference.subtract(stop);
            BigDecimal targetDistance=shortEntry?reference.subtract(target):target.subtract(reference);
            BigDecimal rounding=new BigDecimal("0.011");
            if(stopDistance.add(rounding).compareTo(reference.multiply(band.stopLossPercent()).divide(new BigDecimal("100")))<0||
               targetDistance.add(rounding).compareTo(reference.multiply(band.targetProfitPercent()).divide(new BigDecimal("100")))<0)
                errors.add("Stop or target does not meet the configured price-band minimum.");
        }
        if(reference.signum()<=0||stop.signum()<=0||target.signum()<=0||
            (shortEntry?(target.compareTo(executableEntry)>=0||stop.compareTo(executableEntry)<=0):(stop.compareTo(executableEntry)>=0||target.compareTo(executableEntry)<=0)))errors.add("Stop/target geometry is invalid.");
        if(reference.signum()>0&&executableEntry.subtract(reference).abs().multiply(new BigDecimal("100")).divide(reference,8,RoundingMode.HALF_UP).compareTo(p.maxSlippagePercent())>0)
            errors.add("Quote moved beyond allowed revalidation slippage.");
        BigDecimal cost=executableEntry.multiply(p.roundTripCostBps()).divide(new BigDecimal("10000"),8,RoundingMode.HALF_UP);
        BigDecimal riskStop="REAL".equals(decision.path("mode").asText())?LiveExecutionService.protectionLimit(LiveExecutionService.tick(stop,quote.tickSize(),shortEntry),quote.tickSize(),shortEntry?"BUY":"SELL"):stop;
        BigDecimal risk=(shortEntry?riskStop.subtract(executableEntry):executableEntry.subtract(riskStop)).add(cost);
        if(risk.signum()<=0||(shortEntry?executableEntry.subtract(target):target.subtract(executableEntry)).subtract(cost).compareTo(risk.multiply(new BigDecimal("1.5")))<0)errors.add("Net risk/reward is below 1.5.");
        BigDecimal value=executableEntry.multiply(BigDecimal.valueOf(Math.max(quantity,0)));
        BigDecimal leverage=effectiveLeverage(decision,rules);
        BigDecimal reserved=all.stream().filter(this::open).map(row->amount(row,"entryPrice").multiply(BigDecimal.valueOf(row.path("remainingQuantity").asInt()))
            .divide(row.path("paperLeverage").isNumber()?row.path("paperLeverage").decimalValue().max(BigDecimal.ONE):BigDecimal.ONE,8,RoundingMode.UP)).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean realMode="REAL".equals(decision.path("mode").asText());
        BigDecimal margin=(realMode?(brokerMargin==null?value:brokerMargin):value.divide(leverage,8,RoundingMode.UP)).add(cost.multiply(BigDecimal.valueOf(Math.max(quantity,0))));
        if(realMode&&(brokerMargin==null||brokerMargin.signum()<=0))errors.add("An independently verified Groww MIS margin estimate is required.");
        if(value.compareTo(p.maxPositionValue().multiply(leverage))>0||margin.add(reserved).compareTo(realMode?p.capital().min(p.maxPositionValue()):p.capital())>0)errors.add("Position value or available cash / margin allocation exceeded.");
        if(risk.multiply(BigDecimal.valueOf(Math.max(quantity,0))).compareTo(dailyLossBudget(root,p).perTradeRiskLimit(p))>0)errors.add("Risk per trade exceeded.");
        if(all.stream().filter(this::open).count()>=p.maxOpenPositions())errors.add("Maximum open positions reached.");
        if(all.stream().filter(this::open).anyMatch(row->row.has("incident")||!row.has("quoteTimestamp")||
            Duration.between(Instant.parse(row.path("quoteTimestamp").asText()),instant).compareTo(Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0))
            errors.add("An existing position has stale or unresolved risk data.");
        if(all.stream().anyMatch(row->open(row)&&quote.symbol().equals(row.path("symbol").asText())))errors.add("An active position already exists for this symbol.");
        var today=all.stream().filter(row->sessionDate().equals(row.path("sessionDate").asText())&&row.has("filledQuantity")).toList();
        if(today.size()>=p.maxTradesPerDay())errors.add("Maximum daily entries reached.");
        int winLimit=Integer.parseInt(dashboardRules.get().getOrDefault("stopAfterProfitableTrades",20).toString());
        if(all.stream().filter(this::closedToday).filter(row->row.path("realizedPnl").decimalValue().signum()>0).count()>=winLimit)
            errors.add("Configured profitable-trade stop reached.");
        BigDecimal pnl=dailyPnl(all);
        if(dailyTargetLatched(all)||(p.dailyProfitTarget().signum()>0&&pnl.compareTo(p.dailyProfitTarget())>=0))errors.add("Daily profit target reached; new entries are stopped for this session.");
        if(dailyLossLatched(all)||pnl.compareTo(dailyLossBudget(root,p).limit().negate())<=0)errors.add("Daily realized/unrealized loss limit reached.");
        BigDecimal remainingDailyRisk=dailyLossBudget(root,p).limit().add(pnl);
        BigDecimal openRisk=all.stream().filter(this::open).map(row->
            (shortPosition(row)?amount(row,"stopLoss").subtract(amount(row,"lastPrice")):amount(row,"lastPrice").subtract(amount(row,"stopLoss"))).max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(row.path("remainingQuantity").asInt())))
            .reduce(BigDecimal.ZERO,BigDecimal::add);
        if(risk.multiply(BigDecimal.valueOf(Math.max(quantity,0))).add(openRisk).compareTo(remainingDailyRisk)>0)
            errors.add("Planned trade risk exceeds the remaining daily loss budget.");
        int losses=0;
        for(var row:all.stream().filter(item->closedToday(item))
            .sorted(java.util.Comparator.comparing((ObjectNode item)->Instant.parse(item.path("exitTime").asText())).reversed()).toList()){
            if(row.path("realizedPnl").decimalValue().signum()<0)losses++;else break;
        }
        if(losses>=p.maxConsecutiveLosses())errors.add("Maximum consecutive losses reached.");
        return errors;
    }
    private BigDecimal effectiveLeverage(JsonNode decision,IntradayRules rules){
        if("REAL".equals(decision.path("mode").asText()))return rules.realMaxLeverage();
        BigDecimal proposed=decision.path("paperLeverage").isNumber()?decision.path("paperLeverage").decimalValue():BigDecimal.ONE;
        return proposed.max(BigDecimal.ONE).min(rules.paperLeverage());
    }
    private boolean closedToday(ObjectNode row){
        return "POSITION_CLOSED".equals(row.path("state").asText())&&row.has("exitTime")&&
            Instant.parse(row.path("exitTime").asText()).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString().equals(sessionDate());
    }
    private BigDecimal dailyPnl(List<ObjectNode> rows){
        return rows.stream().map(row->closedToday(row)?row.path("realizedPnl").decimalValue():
            open(row)?row.path("unrealizedPnl").decimalValue().add(real(row)?row.path("realizedPnl").decimalValue():BigDecimal.ZERO):BigDecimal.ZERO).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private void save(ObjectNode value){
        db.update("UPDATE PAPER_INTENTS SET STATE=?,PAYLOAD_JSON=?,UPDATED_AT=? WHERE INTENT_ID=?",
            value.path("state").asText(),encode(value),now(),value.path("intentId").asText());
    }
    private void enqueue(ObjectNode value,String reason){
        if("REASSESSMENT".equals(reason)&&db.query("SELECT PAYLOAD_JSON FROM PLATFORM_OUTBOX WHERE INTENT_ID=? AND STATUS='PENDING'",
            (row,index)->row.getString(1),value.path("intentId").asText()).stream().anyMatch(payload->"REASSESSMENT".equals(read(payload).path("reason").asText())))return;
        var event=json.createObjectNode();String id=UUID.randomUUID().toString();
        event.put("eventId",id);event.put("eventVersion",1);event.put("positionId",value.path("intentId").asText());
        event.put("symbol",value.path("symbol").asText());event.put("quantity",value.path("remainingQuantity").asInt());
        event.set("averageEntryPrice",value.path("entryPrice"));event.set("lastPrice",value.path("lastPrice"));
        event.set("stopLoss",value.path("stopLoss"));event.set("targetPrice",value.path("targetPrice"));
        event.put("positionSide",shortPosition(value)?"SHORT":"LONG");
        if(value.has("lastExecutableExitPrice"))event.set("executableExitPrice",value.get("lastExecutableExitPrice"));
        if(value.has("quoteTimestamp"))event.set("quoteTime",value.get("quoteTimestamp"));
        event.put("status","POSITION_CLOSED".equals(value.path("state").asText())?"CLOSED":
            "EXIT_PENDING".equals(value.path("state").asText())?"EXIT_PENDING":"OPEN");
        event.put("eventTime",now());event.put("reason",reason);
        db.update("INSERT INTO PLATFORM_OUTBOX(EVENT_ID,INTENT_ID,PAYLOAD_JSON,STATUS,ATTEMPTS,NEXT_ATTEMPT_AT,CREATED_AT) VALUES(?,?,?,'PENDING',0,?,?)",
            id,value.path("intentId").asText(),encode(event),now(),now());
        value.put("lastAiRequestedAt",now());save(value);
    }
    public ObjectNode kill(boolean enabled){
        return transaction.execute(tx->{
            var root=lockedRisk();root.put("killSwitch",enabled);
            db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='RISK'",encode(root));
            if(enabled)for(var value:allIntents().stream().filter(this::open).toList()){
                if(real(value))value.put("liveExitRequested",true);
                transition(value,"EXIT_PENDING","KILL_SWITCH");save(value);enqueue(value,"KILL_SWITCH");
            }
            audit("KILL_SWITCH",root);return root;
        });
    }
    public ObjectNode updatePolicy(Policy policy){
        policy.validate();
        return transaction.execute(tx->{
            var root=lockedRisk();root.set("policy",json.valueToTree(policy));
            db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='RISK'",encode(root));
            audit("RISK_POLICY_UPDATED",root);return root;
        });
    }
    public ObjectNode updateIntraday(IntradayRules rules){
        rules.validate();
        return transaction.execute(tx->{
            var root=lockedRisk();root.set("intraday",json.valueToTree(rules));
            db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='RISK'",encode(root));
            audit("INTRADAY_RULES_UPDATED",json.valueToTree(rules));return root;
        });
    }
    @Scheduled(fixedDelayString="${trading.platform.monitor-delay-ms:1000}")
    public void monitor(){
        if(!monitorEnabled)return;
        for(var row:allIntents().stream().filter(this::real).filter(this::activeEntry).toList()){
            try{live.tick(row.path("intentId").asText());notifyLive(row.path("intentId").asText());}catch(Exception error){recordIncident(row.path("intentId").asText(),"LIVE_MONITOR_UNAVAILABLE");}
        }
        if(exitDeadlineReached())transaction.executeWithoutResult(tx->{
            lockedRisk();for(var value:allIntents().stream().filter(this::open).toList()){
                if(!"EXIT_PENDING".equals(value.path("state").asText())){
                    transition(value,"EXIT_PENDING","TIME_EXIT_1459");value.put("forcedExitReason","TIME_EXIT_1459");save(value);
                }
            }
        });
        for(var row:allIntents().stream().filter(this::open).toList()){
            try{mark(row.path("intentId").asText(),GrowwReadBudget.riskQuote(()->gateway.quote(row.path("symbol").asText())));}
            catch(Exception unavailable){recordIncident(row.path("intentId").asText(),"MARKET_DATA_UNAVAILABLE");}
        }
    }
    public ObjectNode refreshPosition(String id){
        var value=intent(id);
        if(!open(value))return value;
        return mark(id,GrowwReadBudget.riskQuote(()->gateway.quote(value.path("symbol").asText())));
    }
    private ObjectNode mark(String id,NormalizedMarketGateway.QuoteData quote){
        return transaction.execute(tx->{
            var root=lockedRisk();var p=policy(root);var value=intent(id);
            if(!open(value))return value;
            boolean fresh=quote.timestamp()!=null&&!quote.timestamp().isAfter(clock.instant().plusSeconds(2))&&
                Duration.between(quote.timestamp(),clock.instant()).compareTo(Duration.ofSeconds(p.maxQuoteAgeSeconds()))<=0;
            BigDecimal executableExit=exitQuote(value,quote);
            if(!fresh||executableExit==null){
                value.put("incident","QUOTE_STALE_OR_UNAVAILABLE");value.put("incidentAt",now());save(value);return value;
            }
            value.remove("incident");value.put("lastPrice",quote.last());value.put("lastExecutableExitPrice",executableExit);value.put("quoteTimestamp",quote.timestamp().toString());
            int quantity=value.path("remainingQuantity").asInt();
            BigDecimal fee=amount(value,"entryPrice").add(executableExit).multiply(BigDecimal.valueOf(quantity))
                .multiply(p.roundTripCostBps()).divide(new BigDecimal("20000"),2,RoundingMode.HALF_UP);
            BigDecimal pnl=profit(value,executableExit,quantity).subtract(fee);
            value.put("unrealizedPnl",pnl);value.put("estimatedCharges",fee);value.put("markedAt",now());
            var session=calendar.status(clock);
            String reason=root.path("killSwitch").asBoolean()?"KILL_SWITCH":
                exitDeadlineReached()?"TIME_EXIT_1459":
                "EXIT_PENDING".equals(value.path("state").asText())?"PENDING_RISK_EXIT":
                !sessionDate().equals(value.path("sessionDate").asText())?"OVERNIGHT_POSITION":
                !Boolean.TRUE.equals(session.get("verified"))?"CALENDAR_UNVERIFIED":
                (shortPosition(value)?executableExit.compareTo(amount(value,"stopLoss"))>=0:executableExit.compareTo(amount(value,"stopLoss"))<=0)?"STOP_LOSS":
                (shortPosition(value)?executableExit.compareTo(amount(value,"targetPrice"))<=0:executableExit.compareTo(amount(value,"targetPrice"))>=0)?"TARGET":
                Boolean.TRUE.equals(session.get("squareOffDue"))?"SESSION_SQUARE_OFF":null;
            save(value);
            BigDecimal daily=dailyPnl(intents());
            if(reason==null&&p.dailyProfitTarget().signum()>0&&daily.compareTo(p.dailyProfitTarget())>=0)reason="DAILY_PROFIT_TARGET";
            if(daily.compareTo(dailyLossBudget(root,p).limit().negate())<=0){
                value.put("dailyLossTriggeredOn",sessionDate());
                if(reason==null)reason="DAILY_LOSS_LIMIT";
            }
            if(reason==null&&dailyLossLatched(intents()))reason="DAILY_LOSS_LIMIT";
            if(reason!=null&&real(value)){
                boolean firstRequest=!value.path("liveExitRequested").asBoolean();value.put("liveExitRequested",true);if(!value.has("exitReason"))value.put("exitReason",reason);
                if(firstRequest)transition(value,"EXIT_PENDING",reason);save(value);if(firstRequest)enqueue(value,reason);return value;
            }
            if(reason!=null){
                if((shortPosition(value)?quote.askQuantity():quote.bidQuantity())<quantity){
                    transition(value,"EXIT_PENDING",reason);value.put("incident","INSUFFICIENT_EXIT_DEPTH");
                    value.put("incidentAt",now());save(value);return value;
                }
                transition(value,"EXIT_SUBMITTED",reason);value.put("exitPrice",executableExit);value.put("exitTime",now());
                value.put("charges",fee);value.put("grossPnl",pnl.add(fee));value.put("realizedPnl",pnl);
                value.put("unrealizedPnl",BigDecimal.ZERO);value.put("remainingQuantity",0);
                transition(value,"EXIT_RECONCILED","Paper fill at fresh executable bid.");
                transition(value,"POSITION_CLOSED",reason);value.put("exitReason",reason);
                save(value);enqueue(value,reason);audit("POSITION_CLOSED",value);
            }else{
                long bucket=clock.instant().getEpochSecond()/AI_REFRESH_SECONDS;
                if(bucket!=value.path("lastAiBucket").asLong()){
                    value.put("lastAiPrice",quote.last());value.put("lastAiBucket",bucket);save(value);enqueue(value,"REASSESSMENT");
                }
            }
            return value;
        });
    }
    private void notifyLive(String id){
        transaction.executeWithoutResult(tx->{lockedRisk();var row=intent(id);
            String signature=row.path("filledQuantity").asInt()+":"+row.path("remainingQuantity").asInt()+":"+row.path("state").asText();
            if(row.path("filledQuantity").asInt()>0&&!signature.equals(row.path("lastReportedBrokerState").asText())){
                row.put("lastReportedBrokerState",signature);save(row);enqueue(row,row.path("state").asText());audit("LIVE_RECONCILED",row);
            }
        });
    }
    @Scheduled(fixedDelayString="${trading.platform.outbox-delay-ms:3000}")
    public void dispatchEvents(){
        var rows=db.query("SELECT EVENT_ID,PAYLOAD_JSON,ATTEMPTS,NEXT_ATTEMPT_AT FROM PLATFORM_OUTBOX WHERE STATUS='PENDING' ORDER BY CREATED_AT",
            (row,index)->Map.of("id",row.getString(1),"payload",row.getString(2),"attempts",row.getInt(3),"next",row.getString(4)));
        for(var row:rows.stream().limit(10).toList()){
            if(Instant.parse(row.get("next").toString()).isAfter(clock.instant()))continue;
            try{
                JsonNode response=json.valueToTree(ai.internalPost("/api/v1/positions/events",read(row.get("payload").toString())));
                receiveAssessment(read(row.get("payload").toString()),response);
                db.update("UPDATE PLATFORM_OUTBOX SET STATUS='DELIVERED' WHERE EVENT_ID=?",row.get("id"));
            }catch(Exception retryable){
                int attempts=((Number)row.get("attempts")).intValue()+1;
                db.update("UPDATE PLATFORM_OUTBOX SET ATTEMPTS=?,NEXT_ATTEMPT_AT=? WHERE EVENT_ID=?",
                    attempts,clock.instant().plusSeconds(Math.min(60,2L<<Math.min(attempts,5))).toString(),row.get("id"));
            }
        }
    }
    private void recordIncident(String id,String message){
        transaction.executeWithoutResult(tx->{
            lockedRisk();var value=intent(id);
            if(open(value)&&!message.equals(value.path("incident").asText())){
                value.put("incident",message);value.put("incidentAt",now());save(value);audit("RISK_INCIDENT",value);
            }
        });
    }
    private void receiveAssessment(ObjectNode event,JsonNode response){
        if(!event.path("eventId").asText().equals(response.path("eventId").asText())||
           !event.path("positionId").asText().equals(response.path("positionId").asText()))
            throw new IllegalStateException("AI assessment does not match its position event.");
        transaction.executeWithoutResult(tx->{
            lockedRisk();
            Integer existing=db.queryForObject("SELECT COUNT(*) FROM PLATFORM_INBOX WHERE EVENT_ID=?",Integer.class,event.path("eventId").asText());
            if(existing!=null&&existing>0)return;
            var value=intent(event.path("positionId").asText());
            Instant eventTime=Instant.parse(event.path("eventTime").asText());
            if(!value.has("lastAiEventTime")||!eventTime.isBefore(Instant.parse(value.path("lastAiEventTime").asText()))){
                // Record the assessment without moving the position out of its
                // configured target/stop hold. Local price, time and risk gates own exits.
                value.put("lastAiAssessment",response.path("reason").asText());value.put("lastAiAction",response.path("action").asText());
                value.put("lastAiEventTime",eventTime.toString());value.put("lastAiReceivedAt",now());value.set("lastAiResponse",response);save(value);
            }
            db.update("INSERT INTO PLATFORM_INBOX(EVENT_ID,INTENT_ID,RESPONSE_JSON,RECEIVED_AT) VALUES(?,?,?,?)",
                event.path("eventId").asText(),event.path("positionId").asText(),encode(response),now());
        });
    }
    public ObjectNode requestExit(String id){
        if(real(intent(id)))return transaction.execute(tx->{lockedRisk();var row=intent(id);if(activeEntry(row)){
            row.put("liveExitRequested",true);row.put("exitReason","MANUAL_EXIT");transition(row,"EXIT_PENDING","Manual real exit requested; waiting for broker confirmation.");save(row);}
            return row;});
        transaction.executeWithoutResult(tx->{
            lockedRisk();var value=intent(id);
            if(open(value)){transition(value,"EXIT_PENDING","MANUAL_EXIT");save(value);}
        });
        try{return refreshPosition(id);}catch(Exception unavailable){recordIncident(id,"EXIT_QUOTE_UNAVAILABLE");return intent(id);}
    }
}
