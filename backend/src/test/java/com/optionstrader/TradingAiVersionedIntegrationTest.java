package com.optionstrader;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.tradingai.TradingAiClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@EnabledIfEnvironmentVariable(named="TRADING_AI_CONTRACT_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class TradingAiVersionedIntegrationTest {
    @Test void automaticSelectionHttpContractFailsClosedWithoutAMaintainedUniverse(){
        var request=json.createObjectNode();
        var bar=json.createObjectNode().put("timestamp",Instant.now().minusSeconds(600).toString())
            .put("open",100).put("high",103).put("low",99).put("close",101).put("volume",100000);
        request.putArray("niftyBars").add(bar);
        request.putArray("snapshots").addObject().put("symbol","TEST").putArray("bars").add(bar);
        JsonNode response=json.valueToTree(client().internalPost("/api/v1/automation/select",request));
        assertThat(response.path("status").asText()).isEqualTo("NO_TRADE");
        assertThat(response.path("selected").isNull()).isTrue();
        assertThat(response.path("executionAllowed").asBoolean()).isFalse();
    }
    final ObjectMapper json=new ObjectMapper();
    TradingAiClient client(){
        String url=System.getenv("TRADING_AI_CONTRACT_URL");
        return new TradingAiClient(RestClient.builder().baseUrl(url)
            .defaultHeader("Authorization","Bearer "+System.getenv("INTERNAL_API_TOKEN")).build(),url,true);
    }
    @Test void versionedNewsAndPositionEventsAreAuthenticatedAndDurablyDeduplicated(){
        var client=client();var news=json.createObjectNode();var item=news.putArray("articles").addObject();
        item.put("headline","Synthetic company reports strong profit");
        item.put("source","synthetic-contract-fixture");item.put("publishedAt",Instant.now().minusSeconds(60).toString());
        JsonNode first=json.valueToTree(client.internalPost("/api/v1/news/analyze",news));
        JsonNode second=json.valueToTree(client.internalPost("/api/v1/news/analyze",news));
        assertThat(first.at("/articles/0/newsId").asText()).startsWith("NEWS-");
        assertThat(second.at("/articles/0/duplicate").asBoolean()).isTrue();
        var event=json.createObjectNode();
        event.put("eventId",UUID.randomUUID().toString());event.put("positionId","POS-CONTRACT");
        event.put("symbol","TEST");event.put("quantity",1);event.put("averageEntryPrice",100);
        event.put("lastPrice",101);event.put("stopLoss",99);event.put("targetPrice",103);
        event.put("status","OPEN");event.put("eventTime",Instant.now().toString());
        Object accepted=client.internalPost("/api/v1/positions/events",event);
        assertThat(json.<JsonNode>valueToTree(client.internalPost("/api/v1/positions/events",event))).isEqualTo(json.<JsonNode>valueToTree(accepted));
        event.put("quantity",2);
        assertThatThrownBy(()->client.internalPost("/api/v1/positions/events",event))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(409));
    }
    @Test void decisionRoundTripReturnsNoTradeForUnverifiedUniverseAndReplaysTheKey(){
        var payload=json.createObjectNode();payload.put("symbol","TEST");payload.put("mode","PAPER");
        var bars=payload.putArray("bars");
        Instant base=Instant.ofEpochSecond(Instant.now().getEpochSecond()/300*300-30000);
        for(int i=0;i<100;i++){
            var row=bars.addObject();row.put("timestamp",base.plusSeconds(i*300L).toString());
            row.put("open",100+i);row.put("high",103+i);row.put("low",99+i);row.put("close",101+i);row.put("volume",100000);
        }
        var quote=payload.putObject("quote");quote.put("lastPrice",200);quote.put("bid",199.99);quote.put("ask",200);
        quote.put("timestamp",Instant.now().toString());quote.put("sessionOpen",true);
        quote.put("calendarVerified",true);quote.put("instrumentVerified",true);
        var market=payload.putObject("market");market.set("niftyBars",bars.deepCopy());market.set("sectorBars",bars.deepCopy());
        market.put("advancers",40);market.put("decliners",10);market.put("observedAt",Instant.now().toString());
        var client=client();String key="contract-"+UUID.randomUUID();
        JsonNode result=json.valueToTree(client.internalPost("/api/v1/decisions/evaluate",payload,key));
        assertThat(result.path("action").asText()).isEqualTo("NO_TRADE");
        assertThat(result.path("requestedQuantity").asInt()).isZero();
        assertThat(result.path("executionAllowed").asBoolean()).isFalse();
        assertThat(result.at("/analysis/indicators/ema_9").isNumber()).isTrue();
        assertThat(json.<JsonNode>valueToTree(client.internalPost("/api/v1/decisions/evaluate",payload,key))).isEqualTo(result);
        assertThat(json.<JsonNode>valueToTree(client.internalGet("/api/v1/decisions/"+result.path("decisionId").asText()))).isEqualTo(result);
        var anonymous=new TradingAiClient(RestClient.builder().baseUrl(System.getenv("TRADING_AI_CONTRACT_URL")).build(),"unused",true);
        assertThatThrownBy(()->anonymous.internalGet("/api/v1/universe"))
            .isInstanceOfSatisfying(RestClientResponseException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(401));
    }
}
