package com.optionstrader.groww;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.*;
import org.springframework.mock.http.client.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GrowwApiAuditTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-12T05:00:00Z"),ZoneOffset.UTC);
    GrowwApiAuditStore store;GrowwApiAuditInterceptor audit;
    @BeforeEach void setup(){store=new GrowwApiAuditStore(json,clock,directory.toString());audit=new GrowwApiAuditInterceptor(store,json,clock);}
    GrowwApiAuditStore.Query query(int limit,String cursor){return store.query("2026-11-12","2026-11-12","ALL","ALL","",limit,cursor,null);}
    JsonNode report(){return store.report(query(200,null));}
    ObjectNode event(String symbol,String outcome){var node=json.createObjectNode().put("startedAt",clock.instant().toString()).put("endpoint","/v1/live-data/quote").put("method","GET").put("category","MARKET_DATA").put("outcome",outcome).put("durationMs",10);node.putObject("request").put("trading_symbol",symbol);return node;}

    @Test void recordsActualHttpAttemptsAndPreservesLargeResponseBytes(){
        var builder=RestClient.builder().baseUrl("https://broker.invalid").requestInterceptor(audit);
        var server=MockRestServiceServer.bindTo(builder).build();var client=builder.build();
        String payload="{\"status\":\"SUCCESS\",\"payload\":\""+"x".repeat(20000)+"\"}";
        server.expect(requestTo("https://broker.invalid/v1/historical/candles?trading_symbol=ITC&interval=5minute"))
            .andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
        assertThat(client.get().uri("/v1/historical/candles?trading_symbol=ITC&interval=5minute").retrieve().body(String.class)).isEqualTo(payload);
        server.verify();var result=report();assertThat(result.at("/totals/calls").asInt()).isEqualTo(1);
        assertThat(result.at("/records/0/outcome").asText()).isEqualTo("SUCCESS");assertThat(result.at("/records/0/category").asText()).isEqualTo("CANDLES");
        assertThat(result.at("/records/0/request/interval").asText()).isEqualTo("5minute");
        assertThat(result.toString()).doesNotContain("x".repeat(100));
    }
    @Test void neverPersistsCredentialsChecksumTokensOrAuthResponse(){
        var builder=RestClient.builder().baseUrl("https://broker.invalid").requestInterceptor(audit);var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://broker.invalid/v1/token/api/access?api_key=synthetic-secret"))
            .andRespond(withSuccess("{\"status\":\"SUCCESS\",\"payload\":{\"token\":\"synthetic-response-secret\"}}",MediaType.APPLICATION_JSON));
        builder.build().post().uri("/v1/token/api/access?api_key=synthetic-secret").header("Authorization","synthetic-header-secret")
            .contentType(MediaType.APPLICATION_JSON).body(Map.of("checksum","synthetic-checksum-secret","api_key","synthetic-key-secret")).retrieve().body(String.class);
        server.verify();String saved=store.export(query(200,null)).toString();
        assertThat(saved).doesNotContain("synthetic-","checksum","api_key","Authorization","token/api/access?");
        assertThat(report().at("/records/0/request").isEmpty()).isTrue();assertThat(report().at("/records/0/outcome").asText()).isEqualTo("HTTP_SUCCESS");
        try(var paths=Files.list(directory)){for(Path file:paths.toList())assertThat(Files.readString(file)).doesNotContain("synthetic-");}catch(IOException error){throw new UncheckedIOException(error);}
    }
    @Test void brokerFailuresAndRateLimitsAreDistinctFromHttpSuccess(){
        var builder=RestClient.builder().baseUrl("https://broker.invalid").requestInterceptor(audit);var server=MockRestServiceServer.bindTo(builder).build();var client=builder.build();
        server.expect(requestTo("https://broker.invalid/v1/order/create")).andRespond(withSuccess("{\"status\":\"FAILURE\",\"error\":{\"code\":\"GA005\",\"message\":\"secret-raw-message\"}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://broker.invalid/v1/live-data/quote")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","12").body("private-raw-error"));
        client.post().uri("/v1/order/create").contentType(MediaType.APPLICATION_JSON).body(Map.of("trading_symbol","ITC","quantity",5,"transaction_type","BUY","secret","request-secret")).retrieve().body(String.class);
        assertThatThrownBy(()->client.get().uri("/v1/live-data/quote").retrieve().body(String.class)).isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        server.verify();var result=report();assertThat(result.at("/totals/failed").asInt()).isEqualTo(2);assertThat(result.at("/totals/rateLimited").asInt()).isEqualTo(1);
        assertThat(result.toString()).contains("BROKER_FAILURE","HTTP_ERROR","GA005","retryAfterSeconds").doesNotContain("secret","private-raw-error");
        assertThat(store.report(store.query("2026-11-12","2026-11-12","ORDERS","BROKER_FAILURE","ITC",10,null,null)).at("/totals/calls").asInt()).isEqualTo(1);
    }
    @Test void transportFailureIsRecordedOnceWithoutRetryOrRawExceptionMessage(){
        var calls=new AtomicInteger();var request=new MockClientHttpRequest(HttpMethod.GET,URI.create("https://broker.invalid/v1/live-data/quote"));
        assertThatThrownBy(()->audit.intercept(request,new byte[0],(r,b)->{calls.incrementAndGet();throw new IOException("secret-error-token");})).isInstanceOf(IOException.class);
        assertThat(calls.get()).isEqualTo(1);assertThat(report().at("/records/0/outcome").asText()).isEqualTo("TRANSPORT_ERROR");
        assertThat(report().toString()).doesNotContain("secret-error-token");
    }
    @Test void failedBodyReadsAndRepeatedCloseRecordOnlyOneCall()throws Exception{
        var request=new MockClientHttpRequest(HttpMethod.GET,URI.create("https://broker.invalid/v1/order/status/1234"));
        var source=new MockClientHttpResponse(new InputStream(){public int read()throws IOException{throw new IOException("secret-read-detail");}},HttpStatus.OK);
        var response=audit.intercept(request,new byte[0],(r,b)->source);
        assertThatThrownBy(()->response.getBody().read()).isInstanceOf(IOException.class);response.close();response.close();
        assertThat(report().at("/totals/calls").asInt()).isEqualTo(1);assertThat(report().at("/records/0/request/groww_order_id").asText()).isEqualTo("1234");
        assertThat(report().at("/records/0/endpoint").asText()).endsWith("/{id}");assertThat(report().at("/records/0/outcome").asText()).isEqualTo("TRANSPORT_ERROR");
        assertThat(report().toString()).doesNotContain("secret-read-detail");
    }
    @Test void sanitizedRequestFieldsAndCsvCannotExposeArbitraryInputs(){
        var summary=audit.requestSummary(URI.create("https://broker.invalid/v1/live-data/quote?trading_symbol=M%26M&exchange_symbols=NSE_ITC,BSE_ITC&access_token=secret&foo=secret"),"{\"secret\":\"body-secret\",\"quantity\":7}".getBytes());
        assertThat(summary.path("trading_symbol").asText()).isEqualTo("M&M");assertThat(summary.path("quantity").asText()).isEqualTo("7");assertThat(summary.toString()).doesNotContain("secret");
        assertThat(audit.requestSummary(URI.create("https://broker.invalid/unknown/secret"),"null".getBytes()).isEmpty()).isTrue();assertThat(GrowwApiAuditInterceptor.endpoint("/unknown/secret")).isEqualTo("/{other-groww-endpoint}");
        assertThat(GrowwApiAuditStore.cell("=1\n+2")).startsWith("\"'");assertThat(GrowwApiAuditStore.cell("a\"b")).isEqualTo("\"a\"\"b\"");
    }
    @Test void allRecordsSurviveRestartAndCursorPagesNeverDuplicateOrDropEqualTimestampCalls(){
        for(int i=0;i<7;i++)store.append(event(i%2==0?"ITC":"OTHER","SUCCESS"));
        store=new GrowwApiAuditStore(json,clock,directory.toString());var ids=new HashSet<String>();String cursor=null;int pages=0;
        do{var page=store.report(query(2,cursor));assertThat(page.at("/totals/calls").asInt()).isEqualTo(7);for(var row:page.path("records"))assertThat(ids.add(row.path("id").asText())).isTrue();pages++;cursor=page.has("nextCursor")?page.path("nextCursor").asText():null;}while(cursor!=null);
        assertThat(ids).hasSize(7);assertThat(pages).isEqualTo(4);assertThat(store.export(query(2,null)).path("rowCount").asInt()).isEqualTo(7);
        assertThat(store.report(store.query("2026-11-12","2026-11-12","ALL","ALL","ITC",10,null,null)).at("/totals/calls").asInt()).isEqualTo(4);
    }
    @Test void istDatesAndSnapshotCutoffAreAccurate(){
        var beforeMidnight=json.createObjectNode().put("startedAt","2026-11-11T18:29:59Z").put("outcome","SUCCESS");store.append(beforeMidnight);
        store.append(event("ITC","SUCCESS"));
        assertThat(store.report(store.query("2026-11-11","2026-11-11","ALL","ALL","",10,null,null)).at("/totals/calls").asInt()).isEqualTo(1);
        assertThat(report().at("/totals/calls").asInt()).isEqualTo(1);
        assertThat(store.report(store.query("2026-11-12","2026-11-12","ALL","ALL","",10,null,"2026-11-12T04:59:59Z")).at("/totals/calls").asInt()).isZero();
    }
    @Test void diskFailureCannotTurnBrokerSuccessIntoOrderRetry()throws Exception{
        Path invalid=directory.resolve("not-directory");Files.writeString(invalid,"occupied");var broken=new GrowwApiAuditStore(json,clock,invalid.toString());var interceptor=new GrowwApiAuditInterceptor(broken,json,clock);
        var response=interceptor.intercept(new MockClientHttpRequest(HttpMethod.GET,URI.create("https://broker.invalid/v1/live-data/quote")),new byte[0],(r,b)->new MockClientHttpResponse("{\"status\":\"SUCCESS\"}".getBytes(),HttpStatus.OK));
        assertThat(new String(response.getBody().readAllBytes())).contains("SUCCESS");assertThatCode(response::close).doesNotThrowAnyException();
        var report=broken.report(broken.query("2026-11-12","2026-11-12","ALL","ALL","",10,null,null));assertThat(report.path("complete").asBoolean()).isFalse();assertThat(report.at("/coverage/unrecordedCallsSinceStart").asInt()).isEqualTo(1);
        assertThatThrownBy(()->broken.export(broken.query("2026-11-12","2026-11-12","ALL","ALL","",10,null,null))).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidFiltersAndMalformedRecordsCannotBecomeMisleadingCompleteExports()throws Exception{
        for(String date:List.of("../secret","2026-02-30","bad"))assertThatThrownBy(()->store.query(date,"2026-11-12","ALL","ALL","",10,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.query("2026-11-12","2026-11-13","ALL","ALL","",10,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->query(0,null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->query(10,"invalid-cursor")).isInstanceOf(IllegalArgumentException.class);
        store.append(event("ITC","SUCCESS"));Files.writeString(directory.resolve("2026-11-12.jsonl"),"invalid JSON\n",StandardOpenOption.APPEND);
        assertThat(report().path("malformedRecords").asInt()).isEqualTo(1);assertThat(report().path("complete").asBoolean()).isFalse();assertThatThrownBy(()->store.export(query(10,null))).isInstanceOf(IllegalStateException.class);
    }
    @Test void instrumentsUseSameAuditWithoutSavingCsvContents(){
        var builder=RestClient.builder().baseUrl("https://broker.invalid").requestInterceptor(audit);var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://broker.invalid/instruments/instrument.csv")).andRespond(withSuccess("symbol,name\nITC,private-csv-content",MediaType.parseMediaType("text/csv")));
        assertThat(builder.build().get().uri("/instruments/instrument.csv").retrieve().body(String.class)).contains("private-csv-content");server.verify();
        assertThat(report().at("/records/0/category").asText()).isEqualTo("INSTRUMENTS");assertThat(report().toString()).doesNotContain("private-csv-content");
    }
}
