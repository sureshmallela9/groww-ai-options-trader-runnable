package com.optionstrader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.groww.GrowwAuthService;
import com.optionstrader.groww.GrowwMarketDataService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final TradingService trading;
    private final AnalysisService analysis;
    private final BacktestingService backtesting;
    private final GrowwAuthService growwAuth;
    private final GrowwMarketDataService marketData;
    private final ObjectMapper objectMapper;

    public ApiController(TradingService trading, AnalysisService analysis, BacktestingService backtesting,
                         GrowwAuthService growwAuth, GrowwMarketDataService marketData,
                         ObjectMapper objectMapper) {
        this.trading = trading;
        this.analysis = analysis;
        this.backtesting = backtesting;
        this.growwAuth = growwAuth;
        this.marketData = marketData;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "mode", "PAPER", "liveExecutionEnabled", false);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return trading.dashboard(); }

    @GetMapping("/settings")
    public Map<String, Object> settings() { return trading.settings(); }

    @PutMapping("/settings")
    public Map<String, Object> update(@RequestBody Map<String, Object> body) { return trading.update(body); }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody(required = false) JsonNode body) {
        return analysis.analyze(body);
    }

    @PostMapping(value = "/analyze/stream", produces = "text/event-stream")
    public SseEmitter analyzeStream(@RequestBody(required = false) JsonNode body) {
        return analysis.streamAnalysis(body);
    }

    @GetMapping("/ai/status")
    public Map<String, Object> aiStatus() {
        Map<String, Object> status = new LinkedHashMap<>(analysis.status());
        Map<String, Object> groww = growwAuth.status();
        boolean growwConfigured = Boolean.TRUE.equals(groww.get("enabled"))
            && Boolean.TRUE.equals(groww.get("credentialsConfigured"));
        status.put("candlesAvailable", growwConfigured);
        status.put("candleSource", growwConfigured
            ? "Groww historical candles can be fetched on demand; validity depends on Groww entitlement and the selected date range."
            : "Configure Groww API credentials to fetch historical candles on demand.");
        return status;
    }

    @GetMapping("/ai/reports")
    public Object aiReports(@RequestParam(defaultValue = "analysis") String kind) {
        if (!"analysis".equalsIgnoreCase(kind)) {
            throw new IllegalArgumentException("Only analysis reports are available.");
        }
        return analysis.reports();
    }

    @GetMapping("/ai/reports/{reportId}")
    public Object aiReport(@PathVariable long reportId) { return analysis.report(reportId); }

    @GetMapping("/groww/status")
    public Map<String, Object> growwStatus() { return growwAuth.status(); }

    @GetMapping("/groww/user/profile")
    public JsonNode growwUserProfile() { return marketData.userProfile(); }

    @GetMapping("/groww/portfolio/holdings")
    public JsonNode growwHoldings() { return marketData.holdings(); }

    @GetMapping("/groww/portfolio/positions")
    public JsonNode growwPositions(@RequestParam(required = false) String segment) {
        return marketData.positions(segment);
    }

    @GetMapping("/groww/portfolio/position")
    public JsonNode growwPosition(@RequestParam("trading_symbol") String tradingSymbol, @RequestParam String segment) {
        return marketData.position(tradingSymbol, segment);
    }
    @GetMapping(value = "/groww/instruments.csv", produces = "text/csv")
    public ResponseEntity<String> growwInstruments() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(marketData.instrumentsCsv());
    }

    @GetMapping("/market/overview")
    public Map<String, Object> marketOverview() { return marketData.overview(); }

    @GetMapping("/market/quote")
    public JsonNode quote(@RequestParam String exchange, @RequestParam String segment,
                          @RequestParam("trading_symbol") String tradingSymbol) {
        return marketData.quote(exchange, segment, tradingSymbol);
    }

    @GetMapping("/market/ltp")
    public JsonNode ltp(@RequestParam String segment, @RequestParam("exchange_symbols") String exchangeSymbols) {
        return marketData.ltp(segment, exchangeSymbols);
    }

    @GetMapping("/market/ohlc")
    public JsonNode ohlc(@RequestParam String segment, @RequestParam("exchange_symbols") String exchangeSymbols) {
        return marketData.ohlc(segment, exchangeSymbols);
    }

    @GetMapping("/market/expiries")
    public JsonNode expiries(@RequestParam String exchange, @RequestParam("underlying_symbol") String underlying,
                             @RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month) {
        return marketData.expiries(exchange, underlying, year, month);
    }

    @GetMapping("/market/contracts")
    public JsonNode contracts(@RequestParam String exchange, @RequestParam("underlying_symbol") String underlying,
                              @RequestParam("expiry_date") LocalDate expiry) {
        return marketData.contracts(exchange, underlying, expiry);
    }

    @GetMapping("/market/option-chain")
    public JsonNode optionChain(@RequestParam String underlying, @RequestParam LocalDate expiry) {
        return marketData.optionChain(underlying, expiry);
    }

    @GetMapping("/market/greeks")
    public JsonNode greeks(@RequestParam String exchange, @RequestParam String underlying,
                           @RequestParam("trading_symbol") String tradingSymbol, @RequestParam LocalDate expiry) {
        return marketData.greeks(exchange, underlying, tradingSymbol, expiry);
    }

    @PostMapping("/market/historical-candles")
    public JsonNode historicalCandles(@RequestBody(required = false) JsonNode body) {
        GrowwMarketDataService.HistoricalRequest request = marketData.parseHistoricalRequest(body);
        return marketData.fetchHistoricalCandles(request);
    }

    @PostMapping("/backtesting/analyze")
    public Map<String, Object> backtestingAnalyze(@RequestBody(required = false) JsonNode body) {
        GrowwMarketDataService.HistoricalRequest request = marketData.parseHistoricalRequest(body);
        JsonNode growwResponse = marketData.fetchHistoricalCandles(request);
        GrowwMarketDataService.AnalysisInput input = marketData.buildAnalysisInput(request, growwResponse);
        Map<String, Object> aiResult = analysis.analyze(objectMapper.valueToTree(input.payload()));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("provider", "GROWW");
        source.put("exchange", request.exchange());
        source.put("segment", request.segment());
        source.put("growwSymbol", request.growwSymbol());
        source.put("candleInterval", request.candleInterval());
        source.put("startTime", request.startTime());
        source.put("endTime", request.endTime());
        source.put("sourceCandleCount", input.sourceCandleCount());
        source.put("analysisCandleCount", input.analysisCandleCount());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", aiResult.getOrDefault("status", "ANALYZED"));
        result.put("source", source);
        result.put("analysis", aiResult);
        result.put("tradeAuthorized", false);
        return result;
    }

    @PostMapping("/backtesting/run")
    public Map<String, Object> backtestingRun(@RequestBody(required = false) JsonNode body) {
        return backtesting.run(body);
    }

    @GetMapping("/trades")
    public List<Map<String, Object>> trades() { return trading.all(); }

    @GetMapping("/trades/{id}")
    public Map<String, Object> trade(@PathVariable String id) { return trading.get(id); }

    @PostMapping("/paper/execute")
    public Map<String, Object> execute() { return trading.execute(); }

    @PostMapping("/paper/close/{id}")
    public Map<String, Object> close(@PathVariable String id) { return trading.close(id); }

    @PostMapping("/paper/square-off")
    public Map<String, Object> squareOff() { return Map.of("closed", trading.squareOff()); }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> upstreamFailure(RestClientResponseException e) {
        int upstreamStatus = e.getStatusCode().value();
        HttpStatusCode responseStatus = switch (upstreamStatus) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 404 -> HttpStatus.NOT_FOUND;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "An upstream API request failed.");
        body.put("upstreamStatus", upstreamStatus);
        if (e.getResponseHeaders() != null) {
            String retryAfter = e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) body.put("retryAfter", retryAfter);
        }
        return ResponseEntity.status(responseStatus).body(body);
    }

    @ExceptionHandler(com.optionstrader.groww.GrowwReadBudget.LimitExceeded.class)
    public ResponseEntity<Map<String,Object>> localBrokerBudget(com.optionstrader.groww.GrowwReadBudget.LimitExceeded limit) {
        return ResponseEntity.status(429).header("Retry-After",Integer.toString(limit.retryAfter()))
            .body(Map.of("error",limit.getMessage(),"retryAfter",limit.retryAfter()));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> upstreamTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "An upstream API timed out or could not be reached."));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> bad(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) { return Map.of("error", e.getMessage()); }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(NoSuchElementException e) { return Map.of("error", e.getMessage()); }
}
