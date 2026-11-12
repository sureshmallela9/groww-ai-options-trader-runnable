package com.optionstrader.groww;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

/** Transport-only observation: never retries requests, stores credentials, or changes the response body. */
@Component
public class GrowwApiAuditInterceptor implements ClientHttpRequestInterceptor {
    private final GrowwApiAuditStore store;private final ObjectMapper json;private final Clock clock;
    public GrowwApiAuditInterceptor(GrowwApiAuditStore store,ObjectMapper json,Clock clock){this.store=store;this.json=json;this.clock=clock;}
    static String category(String path){
        if(path.startsWith("/v1/token/"))return "AUTH";
        if(path.contains("/instruments/"))return "INSTRUMENTS";
        if(path.startsWith("/v1/historical/"))return "CANDLES";
        if(path.startsWith("/v1/live-data/")||path.startsWith("/v1/option-chain/"))return "MARKET_DATA";
        if(path.startsWith("/v1/margins/"))return "MARGIN";
        if(path.startsWith("/v1/order/"))return "ORDERS";
        if(path.startsWith("/v1/positions/")||path.startsWith("/v1/holdings/"))return "ACCOUNT";
        return "OTHER";
    }
    static String purpose(String path){
        if(path.endsWith("/order/create"))return "Place order";if(path.endsWith("/order/modify"))return "Modify order";if(path.endsWith("/order/cancel"))return "Cancel order";
        if(path.contains("/order/trades/"))return "Confirm trade fills";if(path.contains("/order/"))return "Read order status";
        if(path.contains("/margins/detail/orders"))return "Check required margin";if(path.contains("/margins/"))return "Check available funds";
        return switch(category(path)){case "AUTH"->"Authenticate";case "INSTRUMENTS"->"Load instruments";case "CANDLES"->"Fetch candle history";case "MARKET_DATA"->"Read market data";case "ACCOUNT"->"Read account positions or holdings";default->"Other Groww request";};
    }
    static String endpoint(String path){
        // Known static routes and validated dynamic IDs only; unknown URI contents are never copied.
        if(Set.of("/v1/token/api/access","/instruments/instrument.csv","/v1/order/create","/v1/order/modify","/v1/order/cancel","/v1/order/list",
            "/v1/margins/detail/user","/v1/margins/detail/orders","/v1/positions/user","/v1/holdings/user","/v1/historical/candles","/v1/historical/expiries","/v1/historical/contracts",
            "/v1/live-data/quote","/v1/live-data/ltp","/v1/live-data/ohlc","/v1/option-chain/exchange/NSE/underlying/NIFTY").contains(path))return path;
        for(String prefix:List.of("/v1/order/status/reference/","/v1/order/status/","/v1/order/detail/","/v1/order/trades/"))if(path.startsWith(prefix))return prefix+"{id}";
        if(path.startsWith("/v1/option-chain/"))return "/v1/option-chain/{instrument}";
        if(path.startsWith("/v1/live-data/"))return "/v1/live-data/{instrument}";
        return "/{other-groww-endpoint}";
    }
    private String correlation(){
        var attributes=RequestContextHolder.getRequestAttributes();
        if(attributes instanceof ServletRequestAttributes servlet){Object value=servlet.getRequest().getAttribute("correlationId");
            if(value!=null)try{return "CORR-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8))).substring(0,20);}catch(Exception ignored){}
        }
        return UUID.randomUUID().toString();
    }
    ObjectNode requestSummary(URI uri,byte[] body){
        var result=json.createObjectNode();String path=uri.getPath();if(category(path).equals("AUTH"))return result;
        if(uri.getRawQuery()!=null)for(String pair:uri.getRawQuery().split("&")){
            try{String[] split=pair.split("=",2);safeValue(result,URLDecoder.decode(split[0],StandardCharsets.UTF_8),split.length==2?URLDecoder.decode(split[1],StandardCharsets.UTF_8):"");}catch(IllegalArgumentException invalid){}
        }
        if(body.length>0&&body.length<=65536)try{
            JsonNode root=json.readTree(body);if(root!=null&&root.isArray()){result.put("items",root.size());root=root.isEmpty()?null:root.get(0);}
            if(root!=null&&root.isObject())root.fields().forEachRemaining(field->{
                if(field.getValue().isValueNode())safeValue(result,field.getKey(),field.getValue().asText());
            });
        }catch(IOException invalid){}
        for(String prefix:List.of("/v1/order/status/reference/","/v1/order/status/","/v1/order/detail/","/v1/order/trades/"))if(path.startsWith(prefix)){
            String id=path.substring(prefix.length());if(id.matches("[A-Za-z0-9_-]{1,100}"))result.put(prefix.contains("reference")?"order_reference_id":"groww_order_id",id);break;
        }
        return result;
    }
    private void safeValue(ObjectNode output,String key,String value){
        if(Set.of("trading_symbol","groww_symbol").contains(key)&&value.matches("[A-Z0-9&._-]{1,80}"))output.put(key,value);
        else if(key.equals("exchange_symbols")){
            String[] symbols=value.split(",");if(symbols.length<=50&&Arrays.stream(symbols).allMatch(s->s.matches("[A-Z0-9&._-]{1,90}"))){var list=output.putArray("symbols");for(String symbol:symbols)list.add(symbol);}
        }
        else if(Set.of("exchange","segment","product","order_type","transaction_type","validity").contains(key)&&value.matches("[A-Z_]{1,20}"))output.put(key,value);
        else if(Set.of("quantity","price","trigger_price","page","page_size","interval_in_minutes").contains(key)&&value.matches("\\d{1,10}(\\.\\d{1,8})?"))output.put(key,value);
        else if(Set.of("groww_order_id","order_reference_id").contains(key)&&value.matches("[A-Za-z0-9_-]{1,100}"))output.put(key,value);
        else if(Set.of("start_time","end_time","expiry_date").contains(key)&&value.matches("[0-9TZ:+ .-]{1,35}"))output.put(key,value);
        else if(key.equals("interval")&&value.matches("[0-9]{1,3}(minute|hour|day|week|month)"))output.put(key,value);
    }
    @Override public ClientHttpResponse intercept(HttpRequest request,byte[] body,ClientHttpRequestExecution execution)throws IOException{
        long start=System.nanoTime();String path=request.getURI().getPath();
        ObjectNode record=json.createObjectNode().put("startedAt",clock.instant().toString()).put("method",request.getMethod().name()).put("endpoint",endpoint(path))
            .put("category",category(path)).put("purpose",purpose(path)).put("correlationId",correlation());
        record.set("request",requestSummary(request.getURI(),body));
        ClientHttpResponse response=null;
        try{response=execution.execute(request,body);return new AuditedResponse(response,record,start,!Set.of("AUTH","INSTRUMENTS").contains(category(path)));}
        catch(IOException|RuntimeException error){if(response!=null)try{response.close();}catch(RuntimeException ignored){}record.put("outcome","TRANSPORT_ERROR").put("errorType",error.getClass().getSimpleName()).put("durationMs",elapsed(start));store.append(record);throw error;}
    }
    private long elapsed(long start){return Math.max(0,(System.nanoTime()-start)/1_000_000);}
    private class AuditedResponse implements ClientHttpResponse {
        final ClientHttpResponse response;final ObjectNode record;final long start;final boolean capture;final ByteArrayOutputStream prefix=new ByteArrayOutputStream();final AtomicBoolean finished=new AtomicBoolean();
        InputStream body;String readError;
        AuditedResponse(ClientHttpResponse response,ObjectNode record,long start,boolean capture)throws IOException{
            this.response=response;this.record=record;this.start=start;this.capture=capture;record.put("httpStatus",response.getStatusCode().value());
            String retry=response.getHeaders().getFirst("Retry-After");if(retry!=null)try{
                long seconds=retry.matches("\\d{1,6}")?Long.parseLong(retry):Math.max(0,Duration.between(clock.instant(),ZonedDateTime.parse(retry,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds());
                if(seconds<=86400)record.put("retryAfterSeconds",seconds);
            }catch(Exception ignored){}
        }
        public HttpStatusCode getStatusCode()throws IOException{return response.getStatusCode();}
        public String getStatusText()throws IOException{return response.getStatusText();}
        public HttpHeaders getHeaders(){return response.getHeaders();}
        public InputStream getBody()throws IOException{
            if(body==null){InputStream original;try{original=response.getBody();}catch(IOException error){readError=error.getClass().getSimpleName();throw error;}body=new InputStream(){
                public int read()throws IOException{try{int b=original.read();if(capture&&b>=0&&prefix.size()<8192)prefix.write(b);return b;}catch(IOException error){readError=error.getClass().getSimpleName();throw error;}}
                public int read(byte[] b,int off,int len)throws IOException{try{int n=original.read(b,off,len);if(capture&&n>0&&prefix.size()<8192)prefix.write(b,off,Math.min(n,8192-prefix.size()));return n;}catch(IOException error){readError=error.getClass().getSimpleName();throw error;}}
                public int available()throws IOException{return original.available();}
                public void close()throws IOException{original.close();}
            };}return body;
        }
        public void close(){try{response.close();}finally{if(finished.compareAndSet(false,true)){
            String envelope="";
            if(capture)try(var parser=json.getFactory().createParser(prefix.toByteArray())){
                if(parser.nextToken()==JsonToken.START_OBJECT)while(parser.nextToken()!=null){
                    if(parser.currentToken()==JsonToken.END_OBJECT)break;String field=parser.currentName();parser.nextToken();
                    if("status".equals(field)&&parser.currentToken()==JsonToken.VALUE_STRING){String status=parser.getText();if(Set.of("SUCCESS","FAILURE").contains(status)){envelope=status;record.put("brokerStatus",status);}}
                    else if("error".equals(field)&&parser.currentToken()==JsonToken.START_OBJECT){
                        while(parser.nextToken()!=JsonToken.END_OBJECT&&parser.currentToken()!=null){String key=parser.currentName();parser.nextToken();if("code".equals(key)&&parser.currentToken().isScalarValue()&&parser.getText().matches("(?:GA\\d{3}|\\d{3,6})"))record.put("brokerCode",parser.getText());else parser.skipChildren();}
                    }else parser.skipChildren();
                }
            }catch(IOException invalidOrLarge){}
            int status=record.path("httpStatus").asInt();String outcome=readError!=null?"TRANSPORT_ERROR":status<200||status>=300?"HTTP_ERROR":envelope.equals("FAILURE")?"BROKER_FAILURE":envelope.equals("SUCCESS")?"SUCCESS":"HTTP_SUCCESS";
            record.put("outcome",outcome).put("durationMs",elapsed(start));if(readError!=null)record.put("errorType",readError);store.append(record);
        }}}
    }
}
