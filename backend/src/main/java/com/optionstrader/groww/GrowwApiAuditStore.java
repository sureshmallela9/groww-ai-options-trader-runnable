package com.optionstrader.groww;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Compact daily transport audit, separate from the trading database and its transactions. */
@Service
public class GrowwApiAuditStore {
    public static final Set<String> CATEGORIES=Set.of("AUTH","MARKET_DATA","CANDLES","ORDERS","MARGIN","ACCOUNT","INSTRUMENTS","OTHER");
    public static final Set<String> OUTCOMES=Set.of("SUCCESS","HTTP_SUCCESS","BROKER_FAILURE","HTTP_ERROR","TRANSPORT_ERROR");
    private static final ZoneId INDIA=ZoneId.of("Asia/Kolkata");
    private static final Comparator<ObjectNode> ORDER=Comparator.comparing((ObjectNode n)->Instant.parse(n.path("completedAt").asText())).thenComparing(n->n.path("id").asText());
    private final ObjectMapper json;private final Clock clock;private final Path directory;private final Object writeLock=new Object();
    private final AtomicLong writeFailures=new AtomicLong();private volatile String lastWriteError,recordingSince;
    public GrowwApiAuditStore(ObjectMapper json,Clock clock,@Value("${trading.groww-audit.directory:data/groww-api-audit}")String directory){
        this.json=json;this.clock=clock;this.directory=Path.of(directory).toAbsolutePath().normalize();
        try{
            Files.createDirectories(this.directory);Path meta=this.directory.resolve("recording.json");
            if(!Files.exists(meta))try{Files.writeString(meta,json.createObjectNode().put("recordingStartedAt",clock.instant().toString()).toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException concurrent){}
            recordingSince=Instant.parse(json.readTree(Files.readString(meta)).path("recordingStartedAt").asText()).toString();
        }catch(Exception error){lastWriteError=error.getClass().getSimpleName();}
    }
    /** Never retry/throw into an order request when the audit disk is unavailable. */
    public void append(ObjectNode event){
        try{synchronized(writeLock){
            ObjectNode row=event.deepCopy();row.put("id",UUID.randomUUID().toString());
            row.put("completedAt",clock.instant().toString());
            String day=Instant.parse(row.path("startedAt").asText()).atZone(INDIA).toLocalDate().toString();row.put("day",day);
            Files.createDirectories(directory);Files.writeString(directory.resolve(day+".jsonl"),json.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }}catch(Exception error){writeFailures.incrementAndGet();lastWriteError=error.getClass().getSimpleName();}
    }
    public record Query(LocalDate from,LocalDate to,String category,String outcome,String symbol,int limit,String cursor,Instant snapshot){}
    public Query query(String from,String to,String category,String outcome,String symbol,int limit,String cursor,String snapshot){
        LocalDate start=day(from),end=day(to),today=clock.instant().atZone(INDIA).toLocalDate();
        if(start.isAfter(end)||end.isAfter(today)||ChronoUnit.DAYS.between(start,end)>365)throw new IllegalArgumentException("Choose an ordered date range of at most 366 days ending today or earlier.");
        if(category==null)category="ALL";if(outcome==null)outcome="ALL";if(symbol==null)symbol="";
        if(!category.equals("ALL")&&!CATEGORIES.contains(category))throw new IllegalArgumentException("Choose a valid call category.");
        if(!outcome.equals("ALL")&&!OUTCOMES.contains(outcome))throw new IllegalArgumentException("Choose a valid result.");
        if(!symbol.isEmpty()&&!symbol.matches("[A-Z0-9&._-]{1,80}"))throw new IllegalArgumentException("Use a valid uppercase stock symbol.");
        if(limit<1||limit>500)throw new IllegalArgumentException("Page size must be 1–500 calls.");
        Instant at;try{at=snapshot==null||snapshot.isBlank()?clock.instant():Instant.parse(snapshot);}catch(Exception bad){throw new IllegalArgumentException("Invalid report snapshot.");}
        if(at.isAfter(clock.instant().plusSeconds(1)))throw new IllegalArgumentException("Snapshot cannot be in the future.");
        if(cursor!=null&&!cursor.isBlank())anchor(cursor);
        return new Query(start,end,category,outcome,symbol,limit,cursor,at);
    }
    static LocalDate day(String value){try{if(value==null||!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();return LocalDate.parse(value);}catch(Exception bad){throw new IllegalArgumentException("Use valid dates in YYYY-MM-DD format.");}}
    private ObjectNode anchor(String cursor){
        try{
            if(cursor.length()>200)throw new IllegalArgumentException();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=2)throw new IllegalArgumentException();Instant.parse(parts[0]);UUID.fromString(parts[1]);
            return json.createObjectNode().put("completedAt",parts[0]).put("id",parts[1]);
        }catch(Exception bad){throw new IllegalArgumentException("Invalid audit page cursor.");}
    }
    private String cursor(ObjectNode row){return Base64.getUrlEncoder().withoutPadding().encodeToString((row.path("completedAt").asText()+"|"+row.path("id").asText()).getBytes(StandardCharsets.UTF_8));}
    private boolean matches(ObjectNode row,Query q){
        if(Instant.parse(row.path("completedAt").asText()).isAfter(q.snapshot()))return false;
        if(!q.category().equals("ALL")&&!q.category().equals(row.path("category").asText()))return false;
        if(!q.outcome().equals("ALL")&&!q.outcome().equals(row.path("outcome").asText()))return false;
        if(!q.symbol().isEmpty()){
            var summary=row.path("request");boolean found=q.symbol().equals(summary.path("trading_symbol").asText())||q.symbol().equals(summary.path("groww_symbol").asText())||("NSE-"+q.symbol()).equals(summary.path("groww_symbol").asText());
            for(var item:summary.path("symbols"))if(q.symbol().equals(item.asText())||("NSE_"+q.symbol()).equals(item.asText())||("BSE_"+q.symbol()).equals(item.asText()))found=true;
            if(!found)return false;
        }
        return true;
    }
    /** Snapshot each file length under a short write lock; report parsing never blocks order audit appends. */
    private int scan(Query query,Consumer<ObjectNode> use){
        int bad=0;
        for(LocalDate day=query.from();!day.isAfter(query.to());day=day.plusDays(1)){
            Path file=directory.resolve(day+".jsonl");long size;
            try{synchronized(writeLock){if(!Files.exists(file))continue;size=Files.size(file);}}
            catch(IOException e){throw new IllegalStateException("Audit storage cannot be read; the report is incomplete.");}
            try(var raw=Files.newInputStream(file);var limited=new InputStream(){long left=size;public int read()throws IOException{if(left<=0)return -1;int b=raw.read();if(b>=0)left--;return b;}public int read(byte[] b,int off,int len)throws IOException{if(left<=0)return -1;int n=raw.read(b,off,(int)Math.min(left,len));if(n>0)left-=n;return n;}};
                var lines=new BufferedReader(new InputStreamReader(limited,StandardCharsets.UTF_8))){
                String line;while((line=lines.readLine())!=null){
                    ObjectNode row;try{row=(ObjectNode)json.readTree(line);Instant.parse(row.path("completedAt").asText());UUID.fromString(row.path("id").asText());if(!day.toString().equals(row.path("day").asText()))throw new IllegalArgumentException();}
                    catch(Exception invalid){bad++;continue;}
                    if(matches(row,query))use.accept(row);
                }
            }catch(IOException e){throw new IllegalStateException("Audit storage cannot be read; the report is incomplete.");}
        }
        return bad;
    }
    private ObjectNode coverage(){
        var value=json.createObjectNode();if(recordingSince!=null)value.put("recordingStartedAt",recordingSince);
        value.put("unrecordedCallsSinceStart",writeFailures.get()).put("recordingHealthy",lastWriteError==null);
        if(lastWriteError!=null)value.put("lastStorageErrorType",lastWriteError);
        value.put("scope","OUTGOING_GROWW_HTTP_ATTEMPTS").put("historicalBackfillAvailable",false);
        return value;
    }
    public ObjectNode report(Query query){
        var heap=new PriorityQueue<ObjectNode>(ORDER);var totals=json.createObjectNode();var categories=json.createObjectNode();
        long[] counts=new long[6];ObjectNode before=query.cursor()==null||query.cursor().isBlank()?null:anchor(query.cursor());
        int bad=scan(query,row->{
            counts[0]++;String outcome=row.path("outcome").asText();if(Set.of("SUCCESS","HTTP_SUCCESS").contains(outcome))counts[1]++;else counts[2]++;
            if(row.path("httpStatus").asInt()==429)counts[3]++;long duration=row.path("durationMs").asLong();counts[4]+=duration;counts[5]=Math.max(counts[5],duration);
            String category=row.path("category").asText();categories.put(category,categories.path(category).asLong()+1);
            if(before!=null&&ORDER.compare(row,before)>=0)return;
            heap.add(row);if(heap.size()>query.limit()+1)heap.poll();
        });
        // Keep the newest page, not the oldest entries encountered during the scan.
        var rows=new ArrayList<>(heap);rows.sort(ORDER.reversed());boolean more=rows.size()>query.limit();if(more)rows.removeLast();
        totals.put("calls",counts[0]).put("successful",counts[1]).put("failed",counts[2]).put("rateLimited",counts[3]).put("averageMs",counts[0]==0?0:(double)counts[4]/counts[0]).put("maxMs",counts[5]);
        var result=json.createObjectNode().put("from",query.from().toString()).put("to",query.to().toString()).put("snapshotAt",query.snapshot().toString()).put("timezone","Asia/Kolkata")
            .put("hasMore",more).put("malformedRecords",bad).put("complete",bad==0&&writeFailures.get()==0&&lastWriteError==null);
        result.set("records",json.valueToTree(rows));result.set("totals",totals);result.set("categories",categories);result.set("coverage",coverage());
        if(more)result.put("nextCursor",cursor(rows.getLast()));return result;
    }
    public ObjectNode export(Query query){
        var rows=new ArrayList<ObjectNode>();int bad=scan(query,row->{if(rows.size()>=50000)throw new IllegalArgumentException("More than 50,000 matching calls. Narrow the dates or filters before exporting.");rows.add(row);});
        if(bad>0||writeFailures.get()>0||lastWriteError!=null)throw new IllegalStateException("Audit recording is incomplete; review the report warning before exporting.");
        rows.sort(ORDER.reversed());var csv=new StringBuilder("\uFEFF");
        csv.append("Started UTC,Completed UTC,Day IST,Method,Endpoint,Category,Purpose,Result,HTTP status,Duration ms,Broker code,Retry after seconds,Safe request,Call ID,Correlation ID\r\n");
        for(var row:rows){
            var cells=List.of(row.path("startedAt").asText(),row.path("completedAt").asText(),row.path("day").asText(),row.path("method").asText(),row.path("endpoint").asText(),row.path("category").asText(),row.path("purpose").asText(),row.path("outcome").asText(),row.path("httpStatus").asText(""),row.path("durationMs").asText(),row.path("brokerCode").asText(""),row.path("retryAfterSeconds").asText(""),row.path("request").toString(),row.path("id").asText(),row.path("correlationId").asText());
            csv.append(cells.stream().map(GrowwApiAuditStore::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return json.createObjectNode().put("fileName","groww-api-audit-"+query.from()+"-to-"+query.to()+".csv").put("rowCount",rows.size()).put("csv",csv.toString()).put("snapshotAt",query.snapshot().toString());
    }
    static String cell(String value){if(!value.isEmpty()&&"=+-@\t\r\n".indexOf(value.charAt(0))>=0)value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
}
