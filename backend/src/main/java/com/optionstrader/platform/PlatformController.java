package com.optionstrader.platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.optionstrader.tradingai.TradingAiClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class PlatformController {
    private final PaperPlatformService paper;private final NormalizedMarketGateway market;
    private final SessionCalendar calendar;private final TradingAiClient ai;private final Clock clock;
    private final String admin,internal;
    public PlatformController(PaperPlatformService paper,NormalizedMarketGateway market,SessionCalendar calendar,
            TradingAiClient ai,Clock clock,@Value("${trading.admin-token:}")String admin,
            @Value("${trading.internal-token:}")String internal){
        this.paper=paper;this.market=market;this.calendar=calendar;this.ai=ai;this.clock=clock;
        this.admin=admin;this.internal=internal;
    }
    private void admin(String supplied){
        if(admin.isBlank()||admin.equals(internal)||supplied==null||!MessageDigest.isEqual(
            admin.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A separate administrator token is required.");
    }
    @GetMapping("/health") public Object health(){return Map.of("status","UP","apiVersion","v1","mode","PAPER","liveExecutionEnabled",false);}
    @GetMapping("/market-data/quote") public Object quote(@RequestParam String symbol,@RequestParam(defaultValue="NSE")String exchange){
        if(!"NSE".equals(exchange))throw new IllegalArgumentException("Only NSE is supported initially.");
        return market.quoteResponse(symbol,calendar,clock);
    }
    @PostMapping("/market-data/candles/query") public Object candles(@RequestBody JsonNode body){
        if(!"NSE".equals(body.path("exchange").asText("NSE"))||!"5m".equals(body.path("interval").asText("5m")))
            throw new IllegalArgumentException("The initial normalized gateway supports NSE five-minute candles.");
        return market.candles(body.path("symbol").asText(),body.path("lookbackCandles").asInt(100),clock);
    }
    @GetMapping("/account/funds") public Object funds(){return market.funds();}
    @GetMapping("/account/positions") public Object positions(){return market.positions();}
    @GetMapping("/research/universe") public Object universe(){return ai.internalGet("/api/v1/universe");}
    @PostMapping("/research/universe/refresh") public Object refreshUniverse(){return ai.internalPost("/api/v1/universe/refresh",Map.of());}
    @PostMapping("/research/news/analyze") public Object news(@RequestBody JsonNode body){return ai.internalPost("/api/v1/news/analyze",body);}
    @PostMapping("/research/news/collect") public Object collect(){return ai.internalPost("/api/v1/news/collect",Map.of());}
    @PostMapping("/research/market") public Object market(@RequestBody JsonNode body){return ai.internalPost("/api/v1/analysis/market",body);}
    @PostMapping("/research/scan") public Object scan(@RequestBody JsonNode body){return ai.internalPost("/api/v1/candidates/scan",body);}
    @GetMapping("/research/jobs/{id}") public Object job(@PathVariable String id){return ai.internalGet("/api/v1/jobs/"+safeId(id));}
    @PostMapping("/research/evaluate") public Object evaluate(@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false)String key){
        return ai.internalPost("/api/v1/decisions/evaluate",body,key);
    }
    @GetMapping("/decisions/{id}") public Object decision(@PathVariable String id){return ai.internalGet("/api/v1/decisions/"+safeId(id));}
    @PostMapping("/trade-intents") public Object intent(@RequestBody PaperPlatformService.Intent body,
            @RequestHeader(value="Idempotency-Key",required=false)String key){return paper.submit(body,key);}
    @GetMapping("/trade-intents") public Object intents(){return Map.of("intents",paper.intents());}
    @GetMapping("/trade-intents/{id}") public Object intent(@PathVariable String id){return paper.intent(id);}
    @GetMapping("/orders/{id}") public Object order(@PathVariable String id){return paper.intent(id);}
    @GetMapping("/paper/positions") public Object paperPositions(){return Map.of("positions",paper.intents().stream()
        .filter(value->"POSITION_OPEN".equals(value.path("state").asText())||"EXIT_PENDING".equals(value.path("state").asText())).toList());}
    @PostMapping("/paper/positions/{id}/refresh") public Object refresh(@PathVariable String id){return paper.refreshPosition(id);}
    @PostMapping("/paper/positions/{id}/close") public Object close(@PathVariable String id){return paper.requestExit(id);}
    @GetMapping("/risk/status") public Object risk(){return paper.status();}
    @PostMapping("/risk/kill-switch") public Object kill(@RequestBody JsonNode body,
            @RequestHeader(value="X-Admin-Token",required=false)String supplied){
        if(!body.path("enabled").isBoolean())throw new IllegalArgumentException("enabled must be boolean.");
        if(!body.path("enabled").asBoolean())admin(supplied);
        return paper.kill(body.path("enabled").asBoolean());
    }
    @PutMapping("/risk/policy") public Object policy(@RequestBody PaperPlatformService.Policy body,
            @RequestHeader(value="X-Admin-Token",required=false)String supplied){admin(supplied);return paper.updatePolicy(body);}
    private String safeId(String id){
        if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid resource identifier.");
        return id;
    }
}
