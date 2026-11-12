package com.optionstrader.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.optionstrader.groww.GrowwApiClient;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Broker transport only. Durable execution and authorization live in LiveExecutionService. */
@Service
public class GrowwOrderAdapter {
    private final GrowwApiClient client;
    private final ObjectMapper json;
    public GrowwOrderAdapter(GrowwApiClient client,ObjectMapper json){this.client=client;this.json=json;}
    public JsonNode create(JsonNode body){return client.post("/v1/order/create",body);}
    public JsonNode modify(JsonNode body){return client.post("/v1/order/modify",body);}
    public JsonNode cancel(String id){return client.post("/v1/order/cancel",Map.of("segment","CASH","groww_order_id",safe(id)));}
    public JsonNode detail(String id){return client.get("/v1/order/detail/"+safe(id),Map.of("segment","CASH"));}
    public JsonNode byReference(String reference){return client.get("/v1/order/status/reference/"+safe(reference),Map.of("segment","CASH"));}
    public JsonNode funds(){return client.get("/v1/margins/detail/user",Map.of());}
    public JsonNode positions(){return client.get("/v1/positions/user",Map.of("segment","CASH"));}
    public JsonNode margin(JsonNode request){
        var body=json.createObjectNode();for(String field:java.util.List.of("trading_symbol","transaction_type","quantity","price","order_type","product","exchange"))if(request.has(field))body.set(field,request.get(field));
        return client.post("/v1/margins/detail/orders?segment=CASH",json.createArrayNode().add(body));
    }
    public JsonNode orders(){
        var all=json.createArrayNode();
        for(int page=0;page<100;page++){
            var list=client.get("/v1/order/list",Map.of("segment","CASH","page",String.valueOf(page),"page_size","100")).path("order_list");
            if(!list.isArray())throw new IllegalStateException("Broker order list is unavailable.");
            list.forEach(all::add);if(list.size()<100)return all;
        }
        throw new IllegalStateException("Broker order list was truncated; entries are blocked.");
    }
    public JsonNode trades(String id){
        var all=json.createArrayNode();
        for(int page=0;page<100;page++){
            var rows=client.get("/v1/order/trades/"+safe(id),Map.of("segment","CASH","page",String.valueOf(page),"page_size","50")).path("trade_list");
            if(!rows.isArray())throw new IllegalStateException("Broker trade list is unavailable.");
            rows.forEach(all::add);if(rows.size()<50)return all;
        }
        throw new IllegalStateException("Broker trade list was truncated; reconciliation is required.");
    }
    public static boolean terminal(String status){return java.util.Set.of("EXECUTED","COMPLETED","DELIVERY_AWAITED","CANCELLED","REJECTED","FAILED").contains(status);}
    private static String safe(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid broker identifier.");return value;}
}
