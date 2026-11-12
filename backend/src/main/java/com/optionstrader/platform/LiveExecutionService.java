package com.optionstrader.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable broker workflow. A create is sent once after SENDING is committed; an
 * unknown outcome is looked up by its original reference, never blindly resent.
 * All quantities, prices and timestamps come from completed broker trades.
 */
@Service
public class LiveExecutionService {
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;
    private final GrowwOrderAdapter broker;private final ExecutionModeService modes;
    private final NormalizedMarketGateway gateway;private final SessionCalendar calendar;private final Clock clock;
    private final LiveMarginSizer marginSizer;
    private final String owner=UUID.randomUUID().toString();
    private java.util.function.BooleanSupplier automaticAllowed=()->false;
    private java.util.function.BiConsumer<ObjectNode,NormalizedMarketGateway.QuoteData> riskValidator=(row,quote)->{throw new IllegalStateException("Independent live risk validation is not configured.");};
    public void configureAutomation(java.util.function.BooleanSupplier allowed){automaticAllowed=allowed;}
    public void configureRiskValidator(java.util.function.BiConsumer<ObjectNode,NormalizedMarketGateway.QuoteData> validator){riskValidator=validator;}
    public LiveExecutionService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,GrowwOrderAdapter broker,
            ExecutionModeService modes,NormalizedMarketGateway gateway,SessionCalendar calendar,Clock clock){
        this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);this.broker=broker;this.modes=modes;this.gateway=gateway;this.calendar=calendar;this.clock=clock;this.marginSizer=new LiveMarginSizer(broker,clock);
    }
    private String now(){return clock.instant().toString();}
    private ObjectNode parse(String s){try{return (ObjectNode)json.readTree(s);}catch(Exception e){throw new IllegalStateException("Invalid live execution record.");}}
    private ObjectNode load(String id){return parse(db.queryForObject("SELECT PAYLOAD_JSON FROM PAPER_INTENTS WHERE INTENT_ID=?",String.class,id));}
    private ObjectNode mutate(String id,Consumer<ObjectNode> change){return tx.execute(t->{
        modes.risk(true);var row=load(id);change.accept(row);row.put("updatedAt",now());
        db.update("UPDATE PAPER_INTENTS SET STATE=?,PAYLOAD_JSON=?,UPDATED_AT=? WHERE INTENT_ID=?",row.path("state").asText(),row.toString(),now(),id);return row;
    });}
    static BigDecimal decimal(JsonNode n,String key){if(!n.path(key).isNumber())throw new IllegalStateException("Broker numeric field unavailable: "+key);return n.path(key).decimalValue();}
    static BigDecimal tick(BigDecimal price,BigDecimal tick,boolean up){if(tick==null||tick.signum()<=0)throw new IllegalArgumentException("Verified tick size required.");return price.divide(tick,0,up?RoundingMode.CEILING:RoundingMode.FLOOR).multiply(tick);}
    private ObjectNode command(String kind,ObjectNode request){
        String reference="GT"+UUID.randomUUID().toString().replace("-","").substring(0,18);
        request.put("order_reference_id",reference);
        return json.createObjectNode().put("kind",kind).put("reference",reference).put("phase","PREPARED").put("preparedAt",now()).set("request",request);
    }
    ObjectNode order(String symbol,String side,int qty,String type,BigDecimal price,BigDecimal trigger){
        var request=json.createObjectNode().put("trading_symbol",symbol).put("exchange","NSE").put("segment","CASH").put("product","MIS")
            .put("validity","DAY").put("transaction_type",side).put("quantity",qty).put("order_type",type).put("price",price);
        request.put("trigger_price",trigger);return request;
    }
    static BigDecimal protectionLimit(BigDecimal trigger,BigDecimal step,String side){
        if(step==null||step.signum()<=0||trigger==null||trigger.signum()<=0)throw new IllegalStateException("Verified positive stop trigger and tick required.");
        BigDecimal limit="BUY".equals(side)?trigger.add(step):trigger.subtract(step);
        if(limit.signum()<=0)throw new IllegalStateException("Stop limit must remain positive.");
        return limit;
    }
    private ObjectNode protectiveOrder(ObjectNode row,int quantity){
        String side=row.path("exitAction").asText();BigDecimal trigger=decimal(row,"stopLoss");
        return order(row.path("symbol").asText(),side,quantity,"SL",protectionLimit(trigger,decimal(row,"tickSize"),side),trigger);
    }
    /** Called inside the shared risk transaction; this queues work but never calls an order API. */
    public void prepare(ObjectNode row,NormalizedMarketGateway.QuoteData quote,int quantity){
        boolean shortEntry="SHORT".equals(row.path("positionSide").asText());
        BigDecimal price=tick(shortEntry?quote.bid():quote.ask(),quote.tickSize(),!shortEntry);
        row.put("quantity",quantity).put("remainingQuantity",0).put("realizedPnl",0).put("unrealizedPnl",0)
            .put("paperMarginAssumption",false).put("paperLeverage",1).put("chargesEstimated",true).put("state","ENTRY_PENDING");
        row.put("stopLoss",tick(decimal(row.path("decision"),"stopLoss"),quote.tickSize(),shortEntry));
        row.put("targetPrice",tick(decimal(row.path("decision"),"targetPrice"),quote.tickSize(),!shortEntry));
        row.put("tickSize",quote.tickSize());row.put("entryLimitPrice",price);row.set("priceRiskBand",row.path("decision").path("priceRiskBand"));
        row.putArray("brokerCommands").add(command("ENTRY",order(row.path("symbol").asText(),row.path("entryAction").asText(),quantity,"LIMIT",price,BigDecimal.ZERO)));
        event(row,"ENTRY_PENDING","Real entry reserved. Awaiting broker execution; no fill assumed.");
    }
    public LiveMarginSizer.Approval sizeEntry(String symbol,String side,int quantity,BigDecimal price,BigDecimal capital,BigDecimal maxLeverage,BigDecimal costs,boolean resize){
        return marginSizer.select(symbol,side,quantity,price,capital,maxLeverage,costs,resize);
    }
    private void event(ObjectNode row,String state,String reason){
        if(!state.equals(row.path("state").asText())||!reason.equals(row.path("lastExecutionMessage").asText())){
            row.put("state",state);row.put("lastExecutionMessage",reason);
            row.withArray("timeline").addObject().put("state",state).put("at",now()).put("reason",reason);
        }
    }
    private boolean claim(String id){return Boolean.TRUE.equals(tx.execute(t->{
        modes.risk(true);var row=load(id);if(!"REAL".equals(row.path("mode").asText())||!ExecutionModeService.active(row))return false;
        if(row.has("liveLeaseUntil")&&Instant.parse(row.path("liveLeaseUntil").asText()).isAfter(clock.instant())&&!owner.equals(row.path("liveLeaseOwner").asText()))return false;
        row.put("liveLeaseOwner",owner).put("liveLeaseUntil",clock.instant().plusSeconds(120).toString());
        db.update("UPDATE PAPER_INTENTS SET PAYLOAD_JSON=? WHERE INTENT_ID=?",row.toString(),id);return true;
    }));}
    public void tick(String id){
        if(!claim(id))return;
        try{advance(id);}catch(Exception error){mutate(id,row->{row.put("brokerIncident","BROKER_RECONCILIATION_REQUIRED");row.put("incident","BROKER_RECONCILIATION_REQUIRED");row.put("incidentAt",now());
            row.put("executionError",safeMessage(error));});}
        finally{mutate(id,row->{if(owner.equals(row.path("liveLeaseOwner").asText())){row.remove("liveLeaseOwner");row.remove("liveLeaseUntil");}});}
    }
    private String safeMessage(Exception error){
        // Transport bodies can contain account identifiers. Persist only a stable category.
        if(error instanceof org.springframework.web.client.RestClientResponseException response)return "Broker request failed (HTTP "+response.getStatusCode().value()+"). Check Groww and reconcile orders before retrying.";
        return error instanceof IllegalStateException?Objects.toString(error.getMessage(),"Broker data unavailable."):"Broker request outcome could not be verified.";
    }
    private void advance(String id){
        var row=load(id);
        if(modes.risk(false).path("killSwitch").asBoolean())row=mutate(id,r->{r.put("liveExitRequested",true);r.put("exitReason","KILL_SWITCH");});
        if(deadline()&&!row.path("liveExitRequested").asBoolean())row=mutate(id,r->{r.put("liveExitRequested",true);r.put("exitReason","TIME_EXIT_1459");event(r,"EXIT_PENDING","14:59 IST exit requested; waiting for broker fills.");});
        // Read and reconcile every issued command before planning another order.
        for(int i=0;i<row.path("brokerCommands").size();i++){
            var cmd=row.path("brokerCommands").get(i);
            if(!Set.of("PREPARED","REJECTED").contains(cmd.path("phase").asText()))sync(id,i);
        }
        reconcile(id);row=load(id);
        if(!ExecutionModeService.active(row))return;
        var commands=row.withArray("brokerCommands");var entry=commands.get(0);
        if("PREPARED".equals(entry.path("phase").asText())){
            if(row.path("liveExitRequested").asBoolean()||deadline()||!modes.enabled()||(row.path("automatic").asBoolean()&&!automaticAllowed.getAsBoolean())){
                mutate(id,r->event(r,"CANCELLED","Unsent entry cancelled before broker submission."));return;
            }
            try{assertFreshEntry(row);}catch(Exception failed){mutate(id,r->{r.put("executionError",safeMessage(failed));event(r,"REJECTED","Live entry checks failed before any broker order was sent.");});return;}
            send(id,0);return;
        }
        if("REJECTED".equals(entry.path("phase").asText())){mutate(id,r->event(r,"REJECTED","Groww rejected the entry before accepting execution."));return;}
        if(!"ACKED".equals(entry.path("phase").asText()))return; // Unknown submission remains blocked, not retried.
        boolean entryDone=GrowwOrderAdapter.terminal(entry.path("detail").path("order_status").asText());
        int filled=row.path("filledQuantity").asInt(),remaining=row.path("remainingQuantity").asInt();
        // Bound a partial/unfilled entry to 15 seconds and cancel the balance. Cancellation is not a fill.
        if(!entryDone&&(filled>0||row.path("liveExitRequested").asBoolean()||deadline()||
                Instant.parse(row.path("createdAt").asText()).plusSeconds(15).isBefore(clock.instant()))){
            cancel(id,0);
            // Protect already filled shares while cancellation of the entry balance settles.
            if(remaining>0&&commands.size()==1){
                var request=protectiveOrder(row,remaining);
                mutate(id,r->{r.withArray("brokerCommands").add(command("STOP",request));r.put("protectionStatus","PARTIAL_ENTRY_STOP_QUEUED");});send(id,1);
            }else if(remaining>0&&commands.size()>1&&"PREPARED".equals(commands.get(commands.size()-1).path("phase").asText())){
                send(id,commands.size()-1);
            }
            return;
        }
        if(!entryDone)return;
        if(filled==0){mutate(id,r->event(r,"REJECTED","Broker entry ended without any completed trade."));return;}
        if(remaining==0)return;
        // External/manual account activity must not let our protective order reverse a position.
        try{assertPosition(row);}catch(IllegalStateException mismatch){
            if(recoverExternalClosure(id,row))return;
            for(int i=1;i<commands.size();i++)if("ACKED".equals(commands.get(i).path("phase").asText())&&
                !GrowwOrderAdapter.terminal(commands.get(i).path("detail").path("order_status").asText()))cancel(id,i);
            throw mismatch;
        }
        // One protective order is used for stop and explicit exits. Modifying this same order
        // to MARKET avoids a racing second sell/cover when a stop executes during square-off.
        int protection=-1;
        for(int i=1;i<commands.size();i++)if(!GrowwOrderAdapter.terminal(commands.get(i).path("detail").path("order_status").asText()))protection=i;
        if(protection<0){
            assertPosition(row);
            boolean rejectedProtection=false;
            for(int i=1;i<commands.size();i++)if(Set.of("REJECTED","FAILED").contains(commands.get(i).path("detail").path("order_status").asText()))rejectedProtection=true;
            boolean exiting=row.path("liveExitRequested").asBoolean()||deadline()||rejectedProtection;
            if(rejectedProtection)mutate(id,r->{r.put("liveExitRequested",true);r.put("exitReason","PROTECTION_REJECTED");if(!r.has("protectionFailureAt"))r.put("protectionFailureAt",now());});
            if(commands.size()>=5)throw new IllegalStateException("Repeated broker exit rejection. Position remains unresolved; check Groww immediately.");
            String kind=exiting?"EXIT":"STOP";
            var request=exiting?order(row.path("symbol").asText(),row.path("exitAction").asText(),remaining,"MARKET",BigDecimal.ZERO,BigDecimal.ZERO):protectiveOrder(row,remaining);
            mutate(id,r->{r.withArray("brokerCommands").add(command(kind,request));r.put("protectionStatus",exiting?"EXIT_QUEUED":"STOP_QUEUED");});
            send(id,commands.size());return;
        }
        var stop=commands.get(protection);String phase=stop.path("phase").asText();
        if("PREPARED".equals(phase)){send(id,protection);return;}
        if(!"ACKED".equals(phase))return;
        int desired=stop.path("detail").path("filled_quantity").asInt()+remaining;
        if(stop.path("request").path("quantity").asInt()!=desired){modifyQuantity(id,protection,desired);return;}
        boolean stopTriggered="STOP".equals(stop.path("kind").asText())&&"SL".equals(stop.path("detail").path("order_type").asText())&&
            (stop.path("detail").path("filled_quantity").asInt()>0||(stop.path("triggerPendingSeen").asBoolean()&&"OPEN".equals(stop.path("detail").path("order_status").asText())));
        if(stopTriggered&&!row.path("liveExitRequested").asBoolean())mutate(id,r->{r.put("liveExitRequested",true);r.put("exitReason","STOP_TRIGGERED");});
        boolean exiting=row.path("liveExitRequested").asBoolean()||deadline()||stopTriggered;
        if(exiting&&!"MARKET".equals(stop.path("detail").path("order_type").asText())){
            assertPosition(row);modifyToMarket(id,protection);return;
        }
        if(!exiting&&"REJECTED".equals(stop.path("detail").path("order_status").asText())){
            mutate(id,r->{r.put("liveExitRequested",true);r.put("exitReason","PROTECTION_REJECTED");});
        }
    }
    private boolean deadline(){return !clock.instant().atZone(ZoneId.of("Asia/Kolkata")).toLocalTime().isBefore(LocalTime.of(14,59));}
    private void assertFreshEntry(ObjectNode row){
        modes.verifyIp();modes.assertBrokerFlat();
        var risk=modes.risk(false);
        if(row.path("automatic").asBoolean()&&!automaticAllowed.getAsBoolean())throw new IllegalStateException("Automatic entries are paused.");
        if(risk.path("killSwitch").asBoolean()||!Boolean.TRUE.equals(calendar.status(clock).get("entryAllowed")))throw new IllegalStateException("Entry blocked by kill switch or verified session.");
        if(!Instant.parse(row.path("decision").path("validUntil").asText()).isAfter(clock.instant()))throw new IllegalStateException("Entry proposal expired before dispatch.");
        var quote=gateway.quote(row.path("symbol").asText());
        riskValidator.accept(row,quote);
        if(quote.timestamp()==null||quote.timestamp().isBefore(clock.instant().minusSeconds(10))||quote.timestamp().isAfter(clock.instant().plusSeconds(2)))throw new IllegalStateException("Fresh executable quote required before dispatch.");
        BigDecimal price="SELL".equals(row.path("entryAction").asText())?quote.bid():quote.ask();
        if(price==null||("SELL".equals(row.path("entryAction").asText())?price.compareTo(decimal(row,"entryLimitPrice"))<0:price.compareTo(decimal(row,"entryLimitPrice"))>0))
            throw new IllegalStateException("Quote moved beyond the approved limit. No live entry sent.");
        BigDecimal budget=decimal(row,"approvedCapital");
        BigDecimal ceiling=row.path("maxIntradayLeverage").isNumber()?decimal(row,"maxIntradayLeverage"):BigDecimal.ONE;
        var current=marginSizer.verify(row.path("symbol").asText(),row.path("entryAction").asText(),row.path("quantity").asInt(),decimal(row,"entryLimitPrice"),budget,ceiling,decimal(row,"costBps"));
        mutate(row.path("intentId").asText(),current::record);
    }
    private int brokerPosition(ObjectNode row){
        var positions=broker.positions().path("positions");if(!positions.isArray())throw new IllegalStateException("Broker positions unavailable.");
        int actual=0;
        for(var p:positions)if(row.path("symbol").asText().equals(p.path("trading_symbol").asText())&&"MIS".equals(p.path("product").asText())&&"NSE".equals(p.path("exchange").asText())){
            if(!p.path("quantity").isIntegralNumber())throw new IllegalStateException("Broker position quantity unavailable.");actual+=p.path("quantity").asInt();
        }
        return actual;
    }
    private void assertPosition(ObjectNode row){
        int actual=brokerPosition(row);
        int expected=row.path("remainingQuantity").asInt()*("SHORT".equals(row.path("positionSide").asText())?-1:1);
        if(actual!=expected)throw new IllegalStateException("Broker position differs from managed fills. Manual/external activity or delayed reconciliation requires attention; no extra exit is sent.");
    }
    private JsonNode listedOrder(String orderId){
        for(var order:broker.orders())if(orderId.equals(order.path("groww_order_id").asText()))return order;
        throw new IllegalStateException("Previously recorded external exit is not available in the broker order list.");
    }
    /** Adopt an unambiguous, completed manual exit from broker receipts; never send an extra exit. */
    private boolean recoverExternalClosure(String id,ObjectNode row){
        if(row.path("remainingQuantity").asInt()<=0||brokerPosition(row)!=0)return false;
        for(int i=1;i<row.path("brokerCommands").size();i++){
            var own=row.path("brokerCommands").get(i);
            if(!"REJECTED".equals(own.path("phase").asText())&&
               !GrowwOrderAdapter.terminal(own.path("detail").path("order_status").asText()))return false;
        }
        var known=new HashSet<String>();
        for(String payload:db.queryForList("SELECT PAYLOAD_JSON FROM PAPER_INTENTS",String.class))
            for(var command:parse(payload).path("brokerCommands"))if(!command.path("id").asText().isBlank())known.add(command.path("id").asText());
        var receipts=json.createArrayNode();int closed=0;var seenOrders=new HashSet<String>();var seenTrades=new HashSet<String>();
        Instant entry=Instant.parse(row.path("entryTime").asText());
        for(var order:broker.orders()){
            if(!row.path("symbol").asText().equals(order.path("trading_symbol").asText())||
               !"NSE".equals(order.path("exchange").asText())||!"CASH".equals(order.path("segment").asText())||!"MIS".equals(order.path("product").asText()))continue;
            String orderId=order.path("groww_order_id").asText();
            if(known.contains(orderId))continue;
            if(!seenOrders.add(orderId))return false;
            if(orderId.isBlank()||!GrowwOrderAdapter.terminal(order.path("order_status").asText()))return false;
            if(!order.path("filled_quantity").isIntegralNumber()||order.path("filled_quantity").asInt()<0)return false;
            if(order.path("filled_quantity").asInt()==0)continue;
            var trades=broker.trades(orderId);if(!trades.isArray()||trades.isEmpty())return false;
            boolean before=false,after=false;
            for(var trade:trades){
                if(!seenTrades.add(trade.path("groww_trade_id").asText()))return false;
                Instant at=tradeTime(trade);if(at.isBefore(entry))before=true;else after=true;
                if(at.isAfter(clock.instant().plusSeconds(2)))return false;
                if(!at.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString().equals(row.path("sessionDate").asText()))return false;
            }
            if(!after)continue;if(before||!row.path("exitAction").asText().equals(order.path("transaction_type").asText()))return false;
            if(!order.path("quantity").isIntegralNumber()||order.path("quantity").asInt()<1)return false;
            var request=order(row.path("symbol").asText(),row.path("exitAction").asText(),order.path("quantity").asInt(),
                order.path("order_type").asText(),order.path("price").isNumber()?decimal(order,"price"):BigDecimal.ZERO,
                order.path("trigger_price").isNumber()?decimal(order,"trigger_price"):BigDecimal.ZERO);
            var receipt=json.createObjectNode().put("kind","EXTERNAL_EXIT").put("readOnlyExternal",true).put("id",orderId)
                .put("reference",order.path("order_reference_id").asText()).put("phase","ACKED").put("receivedAt",now());
            receipt.set("request",request);receipt.set("detail",order);receipt.set("trades",trades);
            validateDetail(receipt,order,trades);closed=Math.addExact(closed,order.path("filled_quantity").asInt());receipts.add(receipt);
        }
        if(receipts.isEmpty()||closed!=row.path("remainingQuantity").asInt()||brokerPosition(row)!=0)return false;
        mutate(id,current->{
            if(current.path("remainingQuantity").asInt()!=row.path("remainingQuantity").asInt())throw new IllegalStateException("Position changed while external exit was verified.");
            for(var receipt:receipts)current.withArray("brokerCommands").add(receipt);
            current.put("exitReason","EXTERNAL_BROKER_EXIT");current.put("externalExitReconciledAt",now());
        });
        reconcile(id);return true;
    }
    private void send(String id,int index){
        var row=mutate(id,r->{var c=(ObjectNode)r.withArray("brokerCommands").get(index);if(!"PREPARED".equals(c.path("phase").asText()))throw new IllegalStateException("Command already dispatched.");
            if("ENTRY".equals(c.path("kind").asText())&&(r.path("liveExitRequested").asBoolean()||modes.risk(false).path("killSwitch").asBoolean()||
                (r.path("automatic").asBoolean()&&!automaticAllowed.getAsBoolean())))throw new IllegalStateException("Entry was cancelled before dispatch.");
            c.put("phase","SENDING").put("sentAt",now());});
        var command=row.path("brokerCommands").get(index);
        try{
            var result=broker.create(command.path("request"));
            if(result.path("groww_order_id").asText().isBlank())throw new IllegalStateException("Broker order acknowledgement missing.");
            mutate(id,r->{var c=(ObjectNode)r.withArray("brokerCommands").get(index);c.put("id",result.path("groww_order_id").asText()).put("phase","ACKED");});
        }catch(com.optionstrader.groww.GrowwApiClient.BrokerRejection rejected){
            mutate(id,r->{var c=(ObjectNode)r.withArray("brokerCommands").get(index);c.put("phase","REJECTED");c.put("rejection",rejected.getMessage());c.set("detail",json.createObjectNode().put("order_status","REJECTED").put("filled_quantity",0));r.put("executionError",rejected.getMessage());});
        }catch(com.optionstrader.groww.GrowwReadBudget.LimitExceeded deferred){
            mutate(id,r->{((ObjectNode)r.withArray("brokerCommands").get(index)).put("phase","PREPARED");r.put("incident","BROKER_RATE_LIMIT");});
        }catch(Exception unknown){mutate(id,r->{((ObjectNode)r.withArray("brokerCommands").get(index)).put("phase","UNKNOWN");r.put("brokerIncident","ORDER_OUTCOME_UNKNOWN");r.put("incident","ORDER_OUTCOME_UNKNOWN");});throw unknown;}
    }
    private void sync(String id,int index){
        var row=load(id);var c=row.path("brokerCommands").get(index);String orderId=c.path("id").asText();
        if(orderId.isBlank()){
            var found=broker.byReference(c.path("reference").asText());orderId=found.path("groww_order_id").asText();
            if(orderId.isBlank())throw new IllegalStateException("Unknown create outcome. Original reference retained; do not resend.");
        }
        var detail=c.path("readOnlyExternal").asBoolean()?listedOrder(orderId):broker.detail(orderId);
        var trades=broker.trades(orderId);validateDetail(c,detail,trades);
        final String knownId=orderId;
        mutate(id,r->{var command=(ObjectNode)r.withArray("brokerCommands").get(index);command.put("id",knownId).put("phase","ACKED").put("receivedAt",now());command.set("detail",detail);command.set("trades",trades);
            if("STOP".equals(command.path("kind").asText())&&"TRIGGER_PENDING".equals(detail.path("order_status").asText()))command.put("triggerPendingSeen",true);
            if(command.path("pendingQuantity").asInt(-1)==detail.path("quantity").asInt()){
                ((ObjectNode)command.path("request")).put("quantity",detail.path("quantity").asInt());command.remove("pendingQuantity");
            }
        });
    }
    static void validateDetail(JsonNode command,JsonNode detail,JsonNode trades){
        JsonNode request=command.path("request");
        for(String key:List.of("trading_symbol","exchange","segment","product","transaction_type"))
            if(!request.path(key).asText().equals(detail.path(key).asText()))throw new IllegalStateException("Broker order identity mismatch.");
        if((!command.path("id").asText().isBlank()&&!command.path("id").asText().equals(detail.path("groww_order_id").asText()))||
           (command.path("reference").asText().isBlank()&&!command.path("readOnlyExternal").asBoolean())||
           !command.path("reference").asText().equals(detail.path("order_reference_id").asText())||
           !detail.path("filled_quantity").isIntegralNumber()||!detail.path("quantity").isIntegralNumber()||
           (detail.path("quantity").asInt()!=request.path("quantity").asInt()&&detail.path("quantity").asInt()!=command.path("pendingQuantity").asInt(-1))||!trades.isArray())throw new IllegalStateException("Broker quantities/reference unavailable or mismatched.");
        int quantity=0;var ids=new HashSet<String>();
        for(var trade:trades){
            String tradeId=trade.path("groww_trade_id").asText();
            if(tradeId.isBlank()||!ids.add(tradeId)||!Set.of("COMPLETED","EXECUTED").contains(trade.path("trade_status").asText())||
               !detail.path("groww_order_id").asText().equals(trade.path("groww_order_id").asText())||
               !request.path("transaction_type").asText().equals(trade.path("transaction_type").asText())||
               !request.path("trading_symbol").asText().equals(trade.path("trading_symbol").asText())||
               !trade.path("quantity").isIntegralNumber()||trade.path("quantity").asInt()<=0||decimal(trade,"price").signum()<=0)
                throw new IllegalStateException("Broker trade identity, quantity or price mismatch.");
            tradeTime(trade);quantity=Math.addExact(quantity,trade.path("quantity").asInt());
        }
        if(quantity!=detail.path("filled_quantity").asInt()||quantity>detail.path("quantity").asInt())throw new IllegalStateException("Broker trades and cumulative filled quantity disagree.");
    }
    static Instant tradeTime(JsonNode trade){
        String stamp=trade.path("trade_date_time").asText();
        try{return OffsetDateTime.parse(stamp).toInstant();}catch(Exception noOffset){try{return LocalDateTime.parse(stamp).atZone(ZoneId.of("Asia/Kolkata")).toInstant();}catch(Exception bad){throw new IllegalStateException("Broker execution time unavailable.");}}
    }
    private void cancel(String id,int index){
        var c=load(id).path("brokerCommands").get(index);
        // Cancellation can safely be requested again only after another authoritative read.
        if(c.has("cancelRequestedAt")&&Instant.parse(c.path("cancelRequestedAt").asText()).plusSeconds(5).isAfter(clock.instant()))return;
        mutate(id,r->((ObjectNode)r.withArray("brokerCommands").get(index)).put("cancelRequestedAt",now()));
        broker.cancel(c.path("id").asText()); // acknowledgement is not authoritative final state
    }
    private void modifyToMarket(String id,int index){
        var row=load(id);var c=row.path("brokerCommands").get(index);
        if(c.has("modifyRequestedAt")&&Instant.parse(c.path("modifyRequestedAt").asText()).plusSeconds(5).isAfter(clock.instant()))return;
        mutate(id,r->{((ObjectNode)r.withArray("brokerCommands").get(index)).put("modifyRequestedAt",now());r.put("protectionStatus","EXIT_MODIFICATION_PENDING");});
        broker.modify(json.createObjectNode().put("groww_order_id",c.path("id").asText()).put("segment","CASH").put("order_type","MARKET").put("price",0).put("trigger_price",0));
    }
    private void modifyQuantity(String id,int index,int quantity){
        var c=load(id).path("brokerCommands").get(index);
        if(c.has("modifyRequestedAt")&&Instant.parse(c.path("modifyRequestedAt").asText()).plusSeconds(5).isAfter(clock.instant()))return;
        mutate(id,r->{var command=(ObjectNode)r.withArray("brokerCommands").get(index);command.put("pendingQuantity",quantity);command.put("modifyRequestedAt",now());});
        var request=c.path("request");var update=json.createObjectNode().put("groww_order_id",c.path("id").asText()).put("segment","CASH").put("quantity",quantity)
            .put("order_type",c.path("detail").path("order_type").asText());
        update.set("trigger_price",request.path("trigger_price"));update.set("price",request.path("price"));broker.modify(update);
    }
    /** Cumulative trade reconciliation is repeatable across restarts and never assumes an order ack is a fill. */
    void reconcile(String id){mutate(id,row->{
        int entered=0,exited=0;BigDecimal entryValue=BigDecimal.ZERO,exitValue=BigDecimal.ZERO;Instant first=null,last=null;
        var fills=json.createArrayNode();boolean allTerminal=true,entryTerminal=false;
        for(var command:row.path("brokerCommands")){
            boolean entry="ENTRY".equals(command.path("kind").asText());
            boolean terminal="REJECTED".equals(command.path("phase").asText())||("ACKED".equals(command.path("phase").asText())&&GrowwOrderAdapter.terminal(command.path("detail").path("order_status").asText()));
            allTerminal&=terminal;if(entry)entryTerminal=terminal;
            for(var trade:command.path("trades")){
                int q=trade.path("quantity").asInt();BigDecimal value=decimal(trade,"price").multiply(BigDecimal.valueOf(q));Instant at=tradeTime(trade);
                if(entry){entered+=q;entryValue=entryValue.add(value);if(first==null||at.isBefore(first))first=at;}
                else{exited+=q;exitValue=exitValue.add(value);if(last==null||at.isAfter(last))last=at;}
                fills.addObject().put("side",command.path("request").path("transaction_type").asText()).put("price",decimal(trade,"price"))
                    .put("quantity",q).put("time",at.toString()).put("tradeId",trade.path("groww_trade_id").asText()).put("kind",command.path("kind").asText());
            }
        }
        if(exited>entered)throw new IllegalStateException("Exit fills exceed entry quantity; broker reconciliation required.");
        row.set("executionFills",fills);row.put("remainingQuantity",entered-exited);row.put("lastBrokerReceivedAt",now());
        if(entered==0)return;
        row.put("filledQuantity",entered);row.put("entryPrice",entryValue.divide(BigDecimal.valueOf(entered),8,RoundingMode.HALF_UP));row.put("entryTime",first.toString());
        row.put("orderId",row.path("brokerCommands").get(0).path("id").asText());row.put("entryOrderTerminal",entryTerminal);
        BigDecimal average=entryValue.divide(BigDecimal.valueOf(entered),8,RoundingMode.HALF_UP);
        BigDecimal gross="SHORT".equals(row.path("positionSide").asText())?average.multiply(BigDecimal.valueOf(exited)).subtract(exitValue):exitValue.subtract(average.multiply(BigDecimal.valueOf(exited)));
        BigDecimal charges=average.multiply(BigDecimal.valueOf(exited)).add(exitValue).multiply(row.path("costBps").decimalValue()).divide(new BigDecimal("20000"),2,RoundingMode.HALF_UP);
        row.put("realizedPnl",gross.subtract(charges));row.put("estimatedCharges",charges);row.put("chargesEstimated",true);
        var byDay=json.createObjectNode();
        for(var fill:fills)if(!"ENTRY".equals(fill.path("kind").asText())){
            String day=Instant.parse(fill.path("time").asText()).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
            var daily=byDay.withObject(day);BigDecimal q=BigDecimal.valueOf(fill.path("quantity").asInt()),price=decimal(fill,"price");
            BigDecimal gain=("SHORT".equals(row.path("positionSide").asText())?average.subtract(price):price.subtract(average)).multiply(q);
            BigDecimal fee=average.add(price).multiply(q).multiply(row.path("costBps").decimalValue()).divide(new BigDecimal("20000"),8,RoundingMode.HALF_UP);
            daily.put("gross",daily.path("gross").decimalValue().add(gain));daily.put("charges",daily.path("charges").decimalValue().add(fee));daily.put("exitQuantity",daily.path("exitQuantity").asInt()+q.intValueExact());
        }
        for(var daily:byDay){var d=(ObjectNode)daily;BigDecimal fee=d.path("charges").decimalValue().setScale(2,RoundingMode.HALF_UP);d.put("charges",fee);d.put("net",d.path("gross").decimalValue().subtract(fee));}
        row.set("realizedByDay",byDay);
        if(exited==entered&&entryTerminal&&allTerminal){
            row.put("exitPrice",exitValue.divide(BigDecimal.valueOf(exited),8,RoundingMode.HALF_UP));row.put("exitTime",last.toString());row.put("charges",charges);row.put("grossPnl",gross);row.put("unrealizedPnl",0);
            if(!row.has("exitReason"))row.put("exitReason","BROKER_STOP_OR_EXIT");event(row,"POSITION_CLOSED","Every entry/exit trade reconciled with Groww; charges remain estimated.");
        }else event(row,row.path("liveExitRequested").asBoolean()?"EXIT_PENDING":entryTerminal?"POSITION_OPEN":"ENTRY_PENDING","Broker-confirmed filled quantity: "+entered+"; remaining: "+(entered-exited)+".");
        if(row.path("brokerCommands").size()>1){var stop=row.path("brokerCommands").get(row.path("brokerCommands").size()-1);
            row.put("protectionStatus",stop.path("detail").path("order_status").asText("AWAITING_BROKER"));}
        row.remove("incident");row.remove("brokerIncident");row.remove("executionError");
    });}
}
