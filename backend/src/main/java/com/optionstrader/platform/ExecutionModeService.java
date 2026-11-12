package com.optionstrader.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** UI-selected execution destination. Switching never starts automation. */
@Service
public class ExecutionModeService {
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;
    private final GrowwOrderAdapter broker;private final PublicIpProbe ip;private final Clock clock;private final boolean capability;
    public ExecutionModeService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,GrowwOrderAdapter broker,PublicIpProbe ip,Clock clock,
            @Value("${trading.live-trading-enabled:false}")boolean capability){this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);this.broker=broker;this.ip=ip;this.clock=clock;this.capability=capability;}
    ObjectNode risk(boolean lock){try{return (ObjectNode)json.readTree(db.queryForObject("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='RISK'"+(lock?" FOR UPDATE":""),String.class));}catch(Exception e){throw new IllegalStateException("Execution settings are unavailable.",e);}}
    public String mode(){return risk(false).path("executionMode").asText("PAPER");}
    public boolean enabled(){return capability&&"REAL".equals(mode());}
    public Map<String,Object> status(){return Map.of("mode",mode(),"realAvailable",capability,"liveExecutionEnabled",enabled(),"requiresExplicitStart",true);}
    public Map<String,Object> preflight(){
        if(!capability)throw new ResponseStatusException(HttpStatus.CONFLICT,"Real execution is disabled in this installation.");
        String current=ip.current();broker.funds();assertBrokerFlat();
        return Map.of("ready",true,"publicIp",current,"checkedAt",clock.instant().toString(),"whitelistVerified",false,
            "message","Account reads succeeded. Confirm this public IP is registered on Groww; only Groww can confirm order permission.");
    }
    public void assertBrokerFlat(){
        var positions=broker.positions().path("positions");
        if(!positions.isArray())throw new IllegalStateException("Broker positions are unavailable.");
        for(var row:positions)if(!row.has("quantity")||!row.path("quantity").isIntegralNumber()||row.path("quantity").asInt()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"An existing broker position must be resolved before starting a new managed real trade.");
        for(var order:broker.orders())if(!GrowwOrderAdapter.terminal(order.path("order_status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"An existing broker order must be resolved before starting a new managed real trade.");
    }
    public String verifyIp(){
        var settings=risk(false);String expected=settings.path("realPublicIp").asText();
        if(!capability||!"REAL".equals(settings.path("executionMode").asText())||expected.isBlank()||!expected.equals(ip.current()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Real execution is not selected or the public IP differs from the registered setup. Entries are blocked.");
        return expected;
    }
    public Map<String,Object> select(JsonNode body){
        String chosen=body.path("mode").asText();if(!Set.of("PAPER","REAL").contains(chosen))throw new IllegalArgumentException("Choose PAPER or REAL.");
        String publicIp="";
        if("REAL".equals(chosen)){
            if(!body.path("confirmed").asBoolean()||!body.path("ipRegistered").asBoolean())throw new IllegalArgumentException("Confirm real-money execution and the registered static IP in the UI.");
            var checked=preflight();publicIp=checked.get("publicIp").toString();
            if(!publicIp.equals(body.path("publicIp").asText()))throw new IllegalArgumentException("The public IP changed; run the connection check again.");
        }
        final String address=publicIp;
        tx.executeWithoutResult(t->{
            var automatic=db.queryForList("SELECT PAYLOAD_JSON FROM PLATFORM_STATE WHERE STATE_ID='AUTO' FOR UPDATE",String.class);
            if(!automatic.isEmpty())try{if(json.readTree(automatic.getFirst()).path("enabled").asBoolean())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Automation restarted while connection checks were running. Stop it before changing mode.");
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid automation state.");}
            var settings=risk(true);
            for(String payload:db.queryForList("SELECT PAYLOAD_JSON FROM PAPER_INTENTS",String.class))try{
                var row=json.readTree(payload);if(active(row))throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for every active or unresolved order and position to close before changing mode.");
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid execution record.");}
            settings.put("executionMode",chosen);if(!address.isBlank())settings.put("realPublicIp",address);
            settings.put("modeChangedAt",clock.instant().toString());
            db.update("UPDATE PLATFORM_STATE SET PAYLOAD_JSON=? WHERE STATE_ID='RISK'",settings.toString());
        });
        return status();
    }
    public static boolean active(JsonNode row){return !Set.of("REJECTED","CANCELLED","POSITION_CLOSED").contains(row.path("state").asText());}
}
