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

/** Authoritative paper fills and hard risk gates. This class has no broker-order client. */
@Service
public class PaperPlatformService {
    public record Intent(String decisionId,String mode,Integer quantity,boolean confirmed) {}
    public record Policy(BigDecimal capital,BigDecimal riskPerTradePercent,BigDecimal dailyLossPercent,
        BigDecimal maxPositionValue,int maxOpenPositions,int maxTradesPerDay,int maxConsecutiveLosses,
        BigDecimal maxSpreadPercent,BigDecimal maxSlippagePercent,int maxQuoteAgeSeconds,int maxSignalAgeSeconds,
        BigDecimal roundTripCostBps,boolean strategyEnabled,BigDecimal minimumDayTurnover) {
        public static Policy defaults(){return new Policy(new BigDecimal("10000"),new BigDecimal("0.5"),
            new BigDecimal("2"),new BigDecimal("10000"),1,5,3,new BigDecimal("0.2"),
            new BigDecimal("0.2"),10,300,new BigDecimal("20"),true,new BigDecimal("1000000"));}
        public void validate(){
            if(capital==null||riskPerTradePercent==null||dailyLossPercent==null||maxPositionValue==null||
               maxSpreadPercent==null||maxSlippagePercent==null||roundTripCostBps==null||minimumDayTurnover==null||
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
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final NormalizedMarketGateway gateway;
    private final TradingAiClient ai;
    private final SessionCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final boolean monitorEnabled;
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
            return json.treeToValue(merged,Policy.class);
        }
        catch(Exception error){throw new IllegalStateException("Stored risk policy is invalid.",error);}
    }
    private ObjectNode lockedRisk(){
        return read(db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='RISK' FOR UPDATE",String.class));
    }
    private String now(){return clock.instant().toString();}
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
    public List<ObjectNode> intents(){
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
        var policy=policy(root);var positions=intents().stream().filter(this::open).toList();
        return Map.of("mode","PAPER","liveExecutionEnabled",false,"autoEntryEnabled",false,"killSwitch",root.path("killSwitch").asBoolean(),
            "policy",policy,"calendar",calendar.status(clock),"openPositions",positions,
            "pendingEvents",db.queryForObject("SELECT COUNT(*) FROM PLATFORM_OUTBOX WHERE STATUS='PENDING'",Integer.class));
    }
    private boolean open(ObjectNode value){return List.of("POSITION_OPEN","EXIT_PENDING").contains(value.path("state").asText());}
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
        if(body==null||body.decisionId()==null||!body.decisionId().matches("DEC-[A-Za-z0-9-]{8,80}")||
           !"PAPER".equals(body.mode())||!body.confirmed()||key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("A decision ID, PAPER mode, explicit confirmation and Idempotency-Key are required.");
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
        var quote=gateway.quote(symbol);
        return transaction.execute(tx->{
            var root=lockedRisk();var policy=policy(root);policy.validate();
            var previous=db.query("SELECT PAYLOAD_JSON,REQUEST_HASH FROM PAPER_INTENTS WHERE REQUEST_KEY=? OR DECISION_ID=?",
                (row,index)->Map.entry(read(row.getString(1)),row.getString(2)),key,body.decisionId());
            if(!previous.isEmpty()){
                if(!previous.getFirst().getValue().equals(hash(body)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key or decision was used with different terms.");
                return previous.getFirst().getKey();
            }
            var value=json.createObjectNode();value.put("intentId",UUID.randomUUID().toString());
            value.put("decisionId",body.decisionId());value.put("symbol",symbol);value.put("mode","PAPER");
            value.put("createdAt",now());value.put("sessionDate",sessionDate());value.set("decision",decision);
            transition(value,"SIGNAL_GENERATED","AI proposal received.");
            transition(value,"RISK_VALIDATION","Independent Java hard limits evaluated.");
            int quantity=body.quantity()==null?decision.path("requestedQuantity").asInt():body.quantity();
            var errors=validate(decision,quote,quantity,root,policy);
            if(!errors.isEmpty()){
                value.set("riskRejections",json.valueToTree(errors));
                transition(value,"REJECTED",String.join("; ",errors));
            }else{
                BigDecimal entry=quote.ask(); // Deterministic paper fill at current ask; no fabricated broker fill.
                value.put("quantity",quantity);value.put("remainingQuantity",quantity);
                value.put("entryPrice",entry);value.put("lastPrice",quote.last());
                value.put("stopLoss",amount(decision,"stopLoss"));value.put("targetPrice",amount(decision,"targetPrice"));
                value.put("quoteTimestamp",quote.timestamp().toString());value.put("entryTime",now());
                BigDecimal initialFees=entry.add(quote.bid()).multiply(BigDecimal.valueOf(quantity))
                    .multiply(policy.roundTripCostBps()).divide(new BigDecimal("20000"),2,RoundingMode.HALF_UP);
                value.put("unrealizedPnl",quote.bid().subtract(entry).multiply(BigDecimal.valueOf(quantity)).subtract(initialFees));
                value.put("estimatedCharges",initialFees);value.put("realizedPnl",BigDecimal.ZERO);
                value.put("orderId","PAPER-"+UUID.randomUUID());value.put("filledQuantity",quantity);
                value.put("lastAiPrice",quote.last());value.put("lastAiBucket",clock.instant().getEpochSecond()/300);
                for(String state:List.of("APPROVED","ORDER_SUBMITTED","ACCEPTED","FILLED","POSITION_OPEN"))
                    transition(value,state,"Paper simulation only; no broker order.");
            }
            db.update("INSERT INTO PAPER_INTENTS(INTENT_ID,DECISION_ID,REQUEST_KEY,REQUEST_HASH,STATE,SYMBOL,SESSION_DATE,PAYLOAD_JSON,UPDATED_AT) VALUES(?,?,?,?,?,?,?,?,?)",
                value.path("intentId").asText(),body.decisionId(),key,hash(body),value.path("state").asText(),symbol,sessionDate(),encode(value),now());
            if(open(value))enqueue(value,"POSITION_OPEN");
            audit("INTENT_"+value.path("state").asText(),value);
            return value;
        });
    }
    private List<String> validate(ObjectNode decision,NormalizedMarketGateway.QuoteData quote,int quantity,ObjectNode root,Policy p){
        var errors=new ArrayList<String>();var instant=clock.instant();var all=intents();
        if(root.path("killSwitch").asBoolean())errors.add("Kill switch is active.");
        if(!p.strategyEnabled())errors.add("The strategy is disabled by administrator policy.");
        if(!Boolean.TRUE.equals(calendar.status(clock).get("entryAllowed")))errors.add("No verified active entry session.");
        if(!"BUY".equals(decision.path("action").asText())||!"ENTRY".equals(decision.path("intentType").asText())||
           !"EQUITY".equals(decision.path("instrumentType").asText())||!"NSE".equals(decision.path("exchange").asText())||
           !"PAPER".equals(decision.path("mode").asText())||!"NSE_CASH_MOMENTUM_V1".equals(decision.path("strategy").asText())||
           decision.path("strategyVersion").asInt()!=1)
            errors.add("Only approved long-only NSE cash paper entry proposals are supported.");
        try{
            Instant created=Instant.parse(required(decision,"createdAt")),until=Instant.parse(required(decision,"validUntil"));
            if(created.isAfter(instant.plusSeconds(2))||Duration.between(created,instant).compareTo(Duration.ofSeconds(p.maxSignalAgeSeconds()))>0||
               !until.isAfter(instant)||until.isAfter(created.plusSeconds(300)))errors.add("Signal is stale, expired or has invalid validity.");
        }catch(Exception error){errors.add("Signal timestamps are invalid.");}
        if(quote.timestamp()==null||quote.timestamp().isAfter(instant.plusSeconds(2))||
           Duration.between(quote.timestamp(),instant).compareTo(Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0)errors.add("Market quote is stale or undated.");
        if(!quote.verified()||!quote.buyAllowed()||!quote.equity()||quote.tickSize()==null)errors.add("Equity instrument eligibility was not verified.");
        if(!quote.symbol().equals(decision.path("symbol").asText()))errors.add("Quote instrument differs from the decision symbol.");
        if(quote.bid()==null||quote.ask()==null||quote.ask().compareTo(quote.bid())<0){errors.add("Executable bid/ask is missing.");return errors;}
        BigDecimal spread=quote.ask().subtract(quote.bid()).multiply(new BigDecimal("100")).divide(quote.ask(),8,RoundingMode.HALF_UP);
        if(spread.compareTo(p.maxSpreadPercent())>0)errors.add("Spread limit exceeded.");
        if(quantity<1||quantity>decision.path("requestedQuantity").asInt())errors.add("Quantity exceeds the proposed whole-share quantity.");
        if(quote.dayVolume()==null||quote.dayVolume().multiply(quote.last()).compareTo(p.minimumDayTurnover())<0)
            errors.add("Verified day turnover is unavailable or below the hard liquidity limit.");
        if(quote.askQuantity()<quantity||quote.bidQuantity()<quantity)errors.add("Current executable depth cannot support a full paper fill.");
        BigDecimal reference=amount(decision,"referencePrice"),stop=amount(decision,"stopLoss"),target=amount(decision,"targetPrice");
        if(reference.signum()<=0||stop.signum()<=0||stop.compareTo(quote.ask())>=0||target.compareTo(quote.ask())<=0)errors.add("Stop/target geometry is invalid.");
        if(reference.signum()>0&&quote.ask().subtract(reference).abs().multiply(new BigDecimal("100")).divide(reference,8,RoundingMode.HALF_UP).compareTo(p.maxSlippagePercent())>0)
            errors.add("Quote moved beyond allowed revalidation slippage.");
        BigDecimal cost=quote.ask().multiply(p.roundTripCostBps()).divide(new BigDecimal("10000"),8,RoundingMode.HALF_UP);
        BigDecimal risk=quote.ask().subtract(stop).add(cost);
        if(risk.signum()<=0||target.subtract(quote.ask()).subtract(cost).compareTo(risk.multiply(new BigDecimal("1.5")))<0)errors.add("Net risk/reward is below 1.5.");
        BigDecimal value=quote.ask().multiply(BigDecimal.valueOf(Math.max(quantity,0)));
        BigDecimal reserved=all.stream().filter(this::open).map(row->amount(row,"entryPrice").multiply(BigDecimal.valueOf(row.path("remainingQuantity").asInt()))).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(value.compareTo(p.maxPositionValue())>0||value.add(reserved).compareTo(p.capital())>0)errors.add("Position value or available paper capital exceeded.");
        if(risk.multiply(BigDecimal.valueOf(Math.max(quantity,0))).compareTo(p.capital().multiply(p.riskPerTradePercent()).divide(new BigDecimal("100")))>0)errors.add("Risk per trade exceeded.");
        if(all.stream().filter(this::open).count()>=p.maxOpenPositions())errors.add("Maximum open positions reached.");
        if(all.stream().filter(this::open).anyMatch(row->row.has("incident")||!row.has("quoteTimestamp")||
            Duration.between(Instant.parse(row.path("quoteTimestamp").asText()),instant).compareTo(Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0))
            errors.add("An existing position has stale or unresolved risk data.");
        if(all.stream().anyMatch(row->open(row)&&quote.symbol().equals(row.path("symbol").asText())))errors.add("An active position already exists for this symbol.");
        var today=all.stream().filter(row->sessionDate().equals(row.path("sessionDate").asText())&&row.has("filledQuantity")).toList();
        if(today.size()>=p.maxTradesPerDay())errors.add("Maximum daily entries reached.");
        BigDecimal pnl=dailyPnl(all);
        if(pnl.compareTo(p.capital().multiply(p.dailyLossPercent()).divide(new BigDecimal("-100")))<=0)errors.add("Daily realized/unrealized loss limit reached.");
        BigDecimal remainingDailyRisk=p.capital().multiply(p.dailyLossPercent()).divide(new BigDecimal("100")).add(pnl);
        BigDecimal openRisk=all.stream().filter(this::open).map(row->
            amount(row,"lastPrice").subtract(amount(row,"stopLoss")).max(BigDecimal.ZERO)
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
    private boolean closedToday(ObjectNode row){
        return "POSITION_CLOSED".equals(row.path("state").asText())&&row.has("exitTime")&&
            Instant.parse(row.path("exitTime").asText()).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString().equals(sessionDate());
    }
    private BigDecimal dailyPnl(List<ObjectNode> rows){
        return rows.stream().map(row->closedToday(row)?row.path("realizedPnl").decimalValue():
            open(row)?row.path("unrealizedPnl").decimalValue():BigDecimal.ZERO).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private void save(ObjectNode value){
        db.update("UPDATE PAPER_INTENTS SET STATE=?,PAYLOAD_JSON=?,UPDATED_AT=? WHERE INTENT_ID=?",
            value.path("state").asText(),encode(value),now(),value.path("intentId").asText());
    }
    private void enqueue(ObjectNode value,String reason){
        var event=json.createObjectNode();String id=UUID.randomUUID().toString();
        event.put("eventId",id);event.put("eventVersion",1);event.put("positionId",value.path("intentId").asText());
        event.put("symbol",value.path("symbol").asText());event.put("quantity",value.path("remainingQuantity").asInt());
        event.set("averageEntryPrice",value.path("entryPrice"));event.set("lastPrice",value.path("lastPrice"));
        event.set("stopLoss",value.path("stopLoss"));event.set("targetPrice",value.path("targetPrice"));
        event.put("status","POSITION_CLOSED".equals(value.path("state").asText())?"CLOSED":
            "EXIT_PENDING".equals(value.path("state").asText())?"EXIT_PENDING":"OPEN");
        event.put("eventTime",now());event.put("reason",reason);
        db.update("INSERT INTO PLATFORM_OUTBOX(EVENT_ID,INTENT_ID,PAYLOAD_JSON,STATUS,ATTEMPTS,NEXT_ATTEMPT_AT,CREATED_AT) VALUES(?,?,?,'PENDING',0,?,?)",
            id,value.path("intentId").asText(),encode(event),now(),now());
    }
    public ObjectNode kill(boolean enabled){
        return transaction.execute(tx->{
            var root=lockedRisk();root.put("killSwitch",enabled);
            db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='RISK'",encode(root));
            if(enabled)for(var value:intents().stream().filter(this::open).toList()){
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
    @Scheduled(fixedDelayString="${trading.platform.monitor-delay-ms:1000}")
    public void monitor(){
        if(!monitorEnabled)return;
        for(var row:intents().stream().filter(this::open).toList()){
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
            if(!fresh||quote.bid()==null){
                value.put("incident","QUOTE_STALE_OR_UNAVAILABLE");value.put("incidentAt",now());save(value);return value;
            }
            value.remove("incident");value.put("lastPrice",quote.last());value.put("quoteTimestamp",quote.timestamp().toString());
            int quantity=value.path("remainingQuantity").asInt();
            BigDecimal fee=amount(value,"entryPrice").add(quote.bid()).multiply(BigDecimal.valueOf(quantity))
                .multiply(p.roundTripCostBps()).divide(new BigDecimal("20000"),2,RoundingMode.HALF_UP);
            BigDecimal pnl=quote.bid().subtract(amount(value,"entryPrice")).multiply(BigDecimal.valueOf(quantity)).subtract(fee);
            value.put("unrealizedPnl",pnl);
            var session=calendar.status(clock);
            String reason=root.path("killSwitch").asBoolean()?"KILL_SWITCH":
                "EXIT_PENDING".equals(value.path("state").asText())?"PENDING_RISK_EXIT":
                !sessionDate().equals(value.path("sessionDate").asText())?"OVERNIGHT_POSITION":
                !Boolean.TRUE.equals(session.get("verified"))?"CALENDAR_UNVERIFIED":
                quote.bid().compareTo(amount(value,"stopLoss"))<=0?"STOP_LOSS":
                quote.bid().compareTo(amount(value,"targetPrice"))>=0?"TARGET":
                Boolean.TRUE.equals(session.get("squareOffDue"))?"SESSION_SQUARE_OFF":null;
            save(value);
            BigDecimal daily=dailyPnl(intents());
            if(reason==null&&daily.compareTo(p.capital().multiply(p.dailyLossPercent()).divide(new BigDecimal("-100")))<=0)reason="DAILY_LOSS_LIMIT";
            if(reason!=null){
                if(quote.bidQuantity()<quantity){
                    transition(value,"EXIT_PENDING",reason);value.put("incident","INSUFFICIENT_EXIT_DEPTH");
                    value.put("incidentAt",now());save(value);return value;
                }
                transition(value,"EXIT_SUBMITTED",reason);value.put("exitPrice",quote.bid());value.put("exitTime",now());
                value.put("charges",fee);value.put("grossPnl",pnl.add(fee));value.put("realizedPnl",pnl);
                value.put("unrealizedPnl",BigDecimal.ZERO);value.put("remainingQuantity",0);
                transition(value,"EXIT_RECONCILED","Paper fill at fresh executable bid.");
                transition(value,"POSITION_CLOSED",reason);value.put("exitReason",reason);
                save(value);enqueue(value,reason);audit("POSITION_CLOSED",value);
            }else{
                long bucket=clock.instant().getEpochSecond()/300;
                BigDecimal prior=value.path("lastAiPrice").decimalValue();
                boolean moved=prior.signum()>0&&quote.last().subtract(prior).abs().divide(prior,8,RoundingMode.HALF_UP).compareTo(new BigDecimal("0.005"))>=0;
                if(bucket!=value.path("lastAiBucket").asLong()||moved){
                    value.put("lastAiPrice",quote.last());value.put("lastAiBucket",bucket);save(value);enqueue(value,"REASSESSMENT");
                }
            }
            return value;
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
            if(open(value)&&"EXIT".equals(response.path("action").asText())&&
               Duration.between(Instant.parse(event.path("eventTime").asText()),clock.instant()).getSeconds()<=30){
                transition(value,"EXIT_PENDING","AI_EXIT_PROPOSAL: "+response.path("reason").asText());save(value);
            }
            db.update("INSERT INTO PLATFORM_INBOX(EVENT_ID,INTENT_ID,RESPONSE_JSON,RECEIVED_AT) VALUES(?,?,?,?)",
                event.path("eventId").asText(),event.path("positionId").asText(),encode(response),now());
        });
    }
    public ObjectNode requestExit(String id){
        transaction.executeWithoutResult(tx->{
            lockedRisk();var value=intent(id);
            if(open(value)){transition(value,"EXIT_PENDING","MANUAL_EXIT");save(value);}
        });
        try{return refreshPosition(id);}catch(Exception unavailable){recordIncident(id,"EXIT_QUOTE_UNAVAILABLE");return intent(id);}
    }
}
