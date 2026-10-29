package com.optionstrader.groww;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GrowwMarketDataService {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter GROWW_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final Set<String> CANDLE_INTERVALS = Set.of(
        "1minute", "2minute", "3minute", "5minute", "10minute", "15minute", "30minute",
        "1hour", "4hour", "1day", "1week", "1month");
    private static final int MAX_AI_BARS = 5000;
    private final GrowwApiClient api;
    private final GrowwApiProperties properties;
    private final AtomicReference<Map<String, Object>> latest = new AtomicReference<>(unavailable("WAITING_FOR_CREDENTIALS", "Configure Groww API credentials to load live market data."));
    private volatile Instant retryAfter = Instant.EPOCH;
    private volatile Instant optionRetryAfter = Instant.EPOCH;
    private final AtomicReference<Map<String, Object>> latestOptionChain = new AtomicReference<>(Map.of("optionChainState", "WAITING_FOR_GROWW"));

    public GrowwMarketDataService(GrowwApiClient api, GrowwApiProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${groww.api.poll-interval:1000}")
    public void refresh() {
        if (!properties.isEnabled() || !properties.hasCredentials()) {
            latest.set(unavailable(properties.isEnabled() ? "WAITING_FOR_CREDENTIALS" : "DISABLED",
                properties.isEnabled() ? "Configure Groww API credentials to load live market data." : "Groww integration is disabled."));
            return;
        }
        if (Instant.now().isBefore(retryAfter)) return;
        long started = System.nanoTime();
        try {
            JsonNode payload = api.get("/v1/live-data/ltp", Map.of(
                "segment", "CASH",
                "exchange_symbols", "NSE_NIFTY,NSE_BANKNIFTY"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nifty", quote(payload.path("NSE_NIFTY"), "NIFTY"));
            data.put("bankNifty", quote(payload.path("NSE_BANKNIFTY"), "BANKNIFTY"));
            data.put("state", "LIVE");
            data.put("connected", true);
            data.put("source", "Groww API");
            data.put("liveData", true);
            data.put("fetchedAt", Instant.now().toString());
            data.put("requestLatencyMs", (System.nanoTime() - started) / 1_000_000);
            data.put("note", "Read-only market data. Broker order placement is disabled.");
            latest.set(Collections.unmodifiableMap(data));
            retryAfter = Instant.EPOCH;
        } catch (Exception e) {
            retryAfter = Instant.now().plusSeconds(30);
            String reason = e instanceof RestClientResponseException responseException
                ? "Groww request failed with HTTP " + responseException.getStatusCode().value() + "."
                : "Groww request failed (" + e.getClass().getSimpleName() + ").";
            latest.set(unavailable("ERROR", reason));
        }
    }

    public Map<String, Object> overview() {
        return latest.get();
    }

    public Map<String, Object> analysisSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>(latest.get());
        snapshot.putAll(latestOptionChain.get());
        return Collections.unmodifiableMap(snapshot);
    }

    public JsonNode userProfile() {
        return api.get("/v1/user/detail", Map.of());
    }

    public JsonNode holdings() {
        return api.get("/v1/holdings/user", Map.of());
    }

    public JsonNode positions(String segment) {
        if (segment == null || segment.isBlank()) return api.get("/v1/positions/user", Map.of());
        return api.get("/v1/positions/user", Map.of("segment", normalizeSegment(segment)));
    }

    public JsonNode position(String tradingSymbol, String segment) {
        String symbol = requiredSymbol(tradingSymbol, "trading_symbol", 64);
        return api.get("/v1/positions/trading-symbol", Map.of(
            "trading_symbol", symbol, "segment", normalizeSegment(segment)));
    }
    public String instrumentsCsv() {
        return api.downloadInstrumentsCsv();
    }

    public JsonNode quote(String exchange, String segment, String tradingSymbol) {
        String normalizedExchange = normalizeExchange(exchange);
        String normalizedSegment = normalizeSegment(segment);
        String symbol = requiredSymbol(tradingSymbol, "trading_symbol", 64);
        return api.get("/v1/live-data/quote", Map.of(
            "exchange", normalizedExchange, "segment", normalizedSegment, "trading_symbol", symbol));
    }

    public JsonNode ltp(String segment, String exchangeSymbols) {
        String normalizedSegment = normalizeSegment(segment);
        return api.get("/v1/live-data/ltp", Map.of(
            "segment", normalizedSegment, "exchange_symbols", normalizeExchangeSymbols(exchangeSymbols)));
    }

    public JsonNode ohlc(String segment, String exchangeSymbols) {
        String normalizedSegment = normalizeSegment(segment);
        return api.get("/v1/live-data/ohlc", Map.of(
            "segment", normalizedSegment, "exchange_symbols", normalizeExchangeSymbols(exchangeSymbols)));
    }

    public JsonNode optionChain(String underlying, LocalDate expiry) {
        String normalized = requiredSymbol(underlying, "underlying", 20).toUpperCase(Locale.ROOT);
        if (expiry == null) throw new IllegalArgumentException("Expiry date is required.");
        return api.get("/v1/option-chain/exchange/NSE/underlying/" + normalized,
            Map.of("expiry_date", expiry.toString()));
    }

    public JsonNode greeks(String exchange, String underlying, String tradingSymbol, LocalDate expiry) {
        String normalizedExchange = normalizeExchange(exchange);
        String normalizedUnderlying = requiredSymbol(underlying, "underlying", 20).toUpperCase(Locale.ROOT);
        String contract = requiredSymbol(tradingSymbol, "trading_symbol", 64);
        if (expiry == null) throw new IllegalArgumentException("Expiry date is required.");
        String path = "/v1/live-data/greeks/exchange/" + normalizedExchange
            + "/underlying/" + normalizedUnderlying + "/trading_symbol/" + contract
            + "/expiry/" + expiry;
        return api.get(path, Map.of());
    }

    public JsonNode expiries(String exchange, String underlying, Integer year, Integer month) {
        String normalizedExchange = normalizeExchange(exchange);
        String symbol = requiredSymbol(underlying, "underlying_symbol", 20).toUpperCase(Locale.ROOT);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("exchange", normalizedExchange);
        query.put("underlying_symbol", symbol);
        if (year != null) {
            if (year < 2020 || year > LocalDate.now(INDIA).getYear()) {
                throw new IllegalArgumentException("year must be between 2020 and the current year.");
            }
            query.put("year", year.toString());
        }
        if (month != null) {
            if (month < 1 || month > 12) throw new IllegalArgumentException("month must be 1-12.");
            query.put("month", month.toString());
        }
        return api.get("/v1/historical/expiries", query);
    }

    public JsonNode contracts(String exchange, String underlying, LocalDate expiry) {
        String normalizedExchange = normalizeExchange(exchange);
        String symbol = requiredSymbol(underlying, "underlying_symbol", 20).toUpperCase(Locale.ROOT);
        if (expiry == null) throw new IllegalArgumentException("expiry_date is required.");
        return api.get("/v1/historical/contracts", Map.of(
            "exchange", normalizedExchange, "underlying_symbol", symbol, "expiry_date", expiry.toString()));
    }

    public HistoricalRequest parseHistoricalRequest(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Request body must be an object with exchange, segment, groww_symbol, start_time, end_time, and candle_interval.");
        }
        Set<String> allowed = Set.of("exchange", "segment", "groww_symbol", "start_time", "end_time",
            "candle_interval", "analysis_prompt", "knowledge_query");
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("Unsupported historical request field '" + field + "'.");
        });
        String exchange = normalizeExchange(text(body, "exchange"));
        String segment = normalizeSegment(text(body, "segment"));
        String symbol = requiredSymbol(text(body, "groww_symbol"), "groww_symbol", 80);
        String start = text(body, "start_time");
        String end = text(body, "end_time");
        LocalDateTime from = parseGrowwTime(start, "start_time");
        LocalDateTime to = parseGrowwTime(end, "end_time");
        if (!to.isAfter(from)) throw new IllegalArgumentException("end_time must be after start_time.");
        String interval = text(body, "candle_interval").toLowerCase(Locale.ROOT);
        if (!CANDLE_INTERVALS.contains(interval)) {
            throw new IllegalArgumentException("candle_interval must be one of " + String.join(", ", CANDLE_INTERVALS) + ".");
        }
        long maxDays = maximumRangeDays(interval);
        if (java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(maxDays)) > 0) {
            throw new IllegalArgumentException(interval + " supports a maximum date range of " + maxDays + " days per Groww request.");
        }        String prompt = optionalText(body, "analysis_prompt", 1500);
        String knowledgeQuery = optionalText(body, "knowledge_query", 500);
        return new HistoricalRequest(exchange, segment, symbol, start, end, interval, prompt, knowledgeQuery);
    }

    public JsonNode fetchHistoricalCandles(HistoricalRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("exchange", request.exchange());
        query.put("segment", request.segment());
        query.put("groww_symbol", request.growwSymbol());
        query.put("start_time", request.startTime());
        query.put("end_time", request.endTime());
        query.put("candle_interval", request.candleInterval());
        return api.getEnvelope("/v1/historical/candles", query);
    }

    public AnalysisInput buildAnalysisInput(HistoricalRequest request, JsonNode growwEnvelope) {
        return buildAnalysisInput(request, growwEnvelope, MAX_AI_BARS, false, false);
    }

    public AnalysisInput buildAvailableAnalysisInput(HistoricalRequest request, JsonNode growwEnvelope) {
        return buildAnalysisInput(request, growwEnvelope, MAX_AI_BARS, false, true);
    }

    public BacktestData buildBacktestData(HistoricalRequest request, JsonNode growwEnvelope) {
        AnalysisInput full = buildAnalysisInput(request, growwEnvelope, MAX_AI_BARS, false, false);
        if (full.analysisCandleCount() <= 50) {
            throw new IllegalArgumentException("A paper backtest needs at least 51 complete candles: 50 for the AI signal and at least one later candle for simulated execution.");
        }

        AnalysisInput signal = buildAnalysisInput(request, growwEnvelope, 50, true, false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> normalizedBars = (List<Map<String, Object>>) full.payload().get("bars");
        List<HistoricalBar> replayBars = normalizedBars.stream().map(this::toHistoricalBar).toList();
        return new BacktestData(signal, replayBars);
    }

    public OffsetDateTime candleCloseTime(OffsetDateTime candleStart, String candleInterval) {
        LocalDateTime close = candleEnd(candleStart.atZoneSameInstant(INDIA).toLocalDateTime(), candleInterval);
        return close.atZone(INDIA).toOffsetDateTime();
    }

    private AnalysisInput buildAnalysisInput(HistoricalRequest request, JsonNode growwEnvelope,
                                             int maxAnalysisBars, boolean limitSourceResponse, boolean allowEmpty) {
        JsonNode candles = growwEnvelope.path("payload").path("candles");
        if (!candles.isArray()) throw new IllegalStateException("Groww historical response did not contain a candles array.");
        if (candles.size() > MAX_AI_BARS) {
            throw new IllegalArgumentException("Groww returned more than 5,000 candles. Narrow the requested date range.");
        }

        TreeMap<LocalDateTime, Map<String, Object>> sorted = new TreeMap<>();
        Set<LocalDateTime> seen = new HashSet<>();
        LocalDateTime now = LocalDateTime.now(INDIA);
        LocalDateTime requestedStart = parseGrowwTime(request.startTime(), "start_time");
        LocalDateTime requestedEnd = parseGrowwTime(request.endTime(), "end_time");
        for (JsonNode candle : candles) {
            if (!candle.isArray() || candle.size() < 6) continue;
            LocalDateTime timestamp = candleTime(candle.get(0));
            if (timestamp == null || timestamp.isBefore(requestedStart) || timestamp.isAfter(requestedEnd)
                || seen.contains(timestamp) || candleEnd(timestamp, request.candleInterval()).isAfter(now)) continue;
            Double open = finitePrice(candle.get(1));
            Double high = finitePrice(candle.get(2));
            Double low = finitePrice(candle.get(3));
            Double close = finitePrice(candle.get(4));
            Double volume = finitePriceOrZero(candle.get(5));
            if (open == null || high == null || low == null || close == null || volume == null || volume < 0) continue;
            if (high < Math.max(open, close) || low > Math.min(open, close) || low > high) continue;
            seen.add(timestamp);

            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("timestamp", timestamp.atZone(INDIA).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            bar.put("open", open);
            bar.put("high", high);
            bar.put("low", low);
            bar.put("close", close);
            bar.put("volume", volume);
            sorted.put(timestamp, bar);
        }
        if (sorted.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException("No completed, valid candles are available (Groww returned "
                + candles.size() + " raw rows). Check the symbol, segment and dates, or wait for the first candle to close.");
        }

        List<Map<String, Object>> allBars = new ArrayList<>(sorted.values());
        List<Map<String, Object>> bars = allBars.size() > maxAnalysisBars
            ? new ArrayList<>(allBars.subList(0, maxAnalysisBars)) : allBars;
        String interval = aiInterval(request.candleInterval());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", request.growwSymbol());
        payload.put("interval", interval);
        payload.put("bars", bars);
        if (request.analysisPrompt() != null) payload.put("analysis_prompt", request.analysisPrompt());
        if (request.knowledgeQuery() != null) payload.put("knowledge_query", request.knowledgeQuery());
        Map<String, Object> marketData = new LinkedHashMap<>();
        marketData.put("provider", "GROWW");
        marketData.put("endpoint", "/v1/historical/candles");
        Map<String, Object> growwRequest = new LinkedHashMap<>();
        growwRequest.put("exchange", request.exchange());
        growwRequest.put("segment", request.segment());
        growwRequest.put("groww_symbol", request.growwSymbol());
        growwRequest.put("start_time", request.startTime());
        growwRequest.put("end_time", request.endTime());
        growwRequest.put("candle_interval", request.candleInterval());
        JsonNode responseForAi = growwEnvelope;
        if (limitSourceResponse) {
            String firstTimestamp = String.valueOf(bars.get(0).get("timestamp"));
            String cutoffTimestamp = String.valueOf(bars.get(bars.size() - 1).get("timestamp"));
            String firstGrowwTime = growwTime(firstTimestamp);
            String cutoffGrowwTime = growwTime(cutoffTimestamp);
            growwRequest.put("start_time", firstGrowwTime);
            growwRequest.put("end_time", cutoffGrowwTime);
            responseForAi = responseThrough(growwEnvelope,
                OffsetDateTime.parse(firstTimestamp).toLocalDateTime(),
                OffsetDateTime.parse(cutoffTimestamp).toLocalDateTime(), firstGrowwTime, cutoffGrowwTime,
                ((Number) bars.get(bars.size() - 1).get("close")).doubleValue());
        }
        marketData.put("request", growwRequest);
        marketData.put("response", responseForAi);
        payload.put("market_data", marketData);
        return new AnalysisInput(payload, candles.size(), bars.size(), interval);
    }

    private JsonNode responseThrough(JsonNode envelope, LocalDateTime start, LocalDateTime cutoff,
                                     String startTime, String endTime, double closingPrice) {
        if (!(envelope.deepCopy() instanceof ObjectNode copy)) return envelope;
        JsonNode rawPayload = copy.get("payload");
        if (!(rawPayload instanceof ObjectNode payload)) return copy;
        JsonNode rawCandles = payload.get("candles");
        if (!(rawCandles instanceof ArrayNode source)) return copy;
        ArrayNode filtered = source.arrayNode();
        for (JsonNode candle : source) {
            if (candle.isArray() && candle.size() > 0) {
                LocalDateTime timestamp = candleTime(candle.get(0));
                if (timestamp != null && !timestamp.isBefore(start) && !timestamp.isAfter(cutoff)) {
                    filtered.add(candle.deepCopy());
                }
            }
        }
        payload.set("candles", filtered);
        payload.put("start_time", startTime);
        payload.put("end_time", endTime);
        payload.put("closing_price", closingPrice);
        return copy;
    }

    private String growwTime(String isoTimestamp) {
        return OffsetDateTime.parse(isoTimestamp).toLocalDateTime().format(GROWW_TIME);
    }

    private HistoricalBar toHistoricalBar(Map<String, Object> bar) {
        return new HistoricalBar(
            OffsetDateTime.parse(String.valueOf(bar.get("timestamp"))),
            ((Number) bar.get("open")).doubleValue(),
            ((Number) bar.get("high")).doubleValue(),
            ((Number) bar.get("low")).doubleValue(),
            ((Number) bar.get("close")).doubleValue(),
            ((Number) bar.get("volume")).doubleValue());
    }

    @Scheduled(fixedDelayString = "${groww.api.option-chain-poll-interval:30000}", initialDelayString = "${groww.api.option-chain-initial-delay:5000}")
    public void refreshOptionChain() {
        if (!properties.isEnabled() || !properties.hasCredentials()) {
            latestOptionChain.set(Map.of("optionChainState", properties.isEnabled() ? "WAITING_FOR_CREDENTIALS" : "DISABLED"));
            return;
        }
        if (Instant.now().isBefore(optionRetryAfter)) return;
        try {
            LocalDate today = LocalDate.now(INDIA);
            List<String> expiries = expiryValues(today.getYear(), today.getMonthValue());
            if (expiries.stream().noneMatch(date -> LocalDate.parse(date).compareTo(today) >= 0)) {
                LocalDate nextMonth = today.plusMonths(1);
                expiries = expiryValues(nextMonth.getYear(), nextMonth.getMonthValue());
            }
            LocalDate expiry = expiries.stream().map(LocalDate::parse).filter(date -> !date.isBefore(today)).min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("No upcoming NIFTY expiry was returned."));
            JsonNode chain = optionChain("NIFTY", expiry);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("optionChainState", "LIVE");
            value.put("optionChainExpiry", expiry.toString());
            value.put("optionChainFetchedAt", Instant.now().toString());
            value.put("optionChain", compactOptionChain(chain));
            latestOptionChain.set(Collections.unmodifiableMap(value));
            optionRetryAfter = Instant.EPOCH;
        } catch (Exception e) {
            optionRetryAfter = Instant.now().plusSeconds(30);
            latestOptionChain.set(Map.of(
                "optionChainState", "UNAVAILABLE",
                "optionChainNote", "Option-chain refresh failed (" + e.getClass().getSimpleName() + ")."));
        }
    }

    private List<String> expiryValues(int year, int month) {
        JsonNode response = expiries("NSE", "NIFTY", year, month);
        List<String> result = new ArrayList<>();
        JsonNode values = response.path("expiries");
        if (values.isArray()) values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private Map<String, Object> compactOptionChain(JsonNode chain) {
        BigDecimal underlying = decimalOrNull(chain.path("underlying_ltp"));
        Map<String, Object> result = new LinkedHashMap<>();
        if (underlying != null) result.put("underlyingLtp", underlying);
        JsonNode strikes = chain.path("strikes");
        List<Strike> available = new ArrayList<>();
        Iterator<String> names = strikes.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            try { available.add(new Strike(new BigDecimal(name), name)); }
            catch (NumberFormatException ignored) { }
        }
        available.sort(Comparator.comparing(strike -> underlying == null ? BigDecimal.ZERO : strike.price().subtract(underlying).abs()));
        Map<String, Object> selected = new LinkedHashMap<>();
        for (Strike strike : available.stream().limit(5).toList()) {
            JsonNode legs = strikes.path(strike.key());
            Map<String, Object> pair = new LinkedHashMap<>();
            addLeg(pair, "CE", legs.path("CE"));
            addLeg(pair, "PE", legs.path("PE"));
            selected.put(strike.key(), pair);
        }
        result.put("nearestStrikes", selected);
        result.put("strikeCount", available.size());
        return result;
    }

    private void addLeg(Map<String, Object> target, String type, JsonNode leg) {
        if (leg == null || leg.isMissingNode() || leg.isNull()) return;
        Map<String, Object> value = new LinkedHashMap<>();
        putIfPresent(value, "symbol", leg.path("trading_symbol"));
        putIfPresent(value, "ltp", leg.path("ltp"));
        putIfPresent(value, "openInterest", leg.path("open_interest"));
        putIfPresent(value, "volume", leg.path("volume"));
        JsonNode greeks = leg.path("greeks");
        if (greeks.isObject()) {
            Map<String, Object> g = new LinkedHashMap<>();
            for (String key : List.of("delta", "gamma", "theta", "vega", "rho", "iv")) putIfPresent(g, key, greeks.path(key));
            value.put("greeks", g);
        }
        target.put(type, value);
    }

    private void putIfPresent(Map<String, Object> target, String key, JsonNode node) {
        if (node != null && !node.isMissingNode() && !node.isNull()) target.put(key, node.isNumber() ? node.numberValue() : node.asText());
    }

    private Map<String, Object> quote(JsonNode node, String symbol) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("price", decimalOrNull(node));
        result.put("currency", "INR");
        return Collections.unmodifiableMap(result);
    }

    private BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) return null;
        return node.decimalValue();
    }

    private String normalizeExchangeSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) throw new IllegalArgumentException("exchange_symbols is required.");
        List<String> values = List.of(symbols.split(",")).stream().map(String::trim).filter(s -> !s.isBlank()).toList();
        if (values.isEmpty() || values.size() > 50 || values.stream().anyMatch(s -> !s.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("exchange_symbols must contain 1-50 valid instrument symbols.");
        }
        return String.join(",", values);
    }

    private String normalizeExchange(String exchange) {
        String value = requiredSymbol(exchange, "exchange", 4).toUpperCase(Locale.ROOT);
        if (!value.equals("NSE") && !value.equals("BSE")) throw new IllegalArgumentException("exchange must be NSE or BSE.");
        return value;
    }

    private String normalizeSegment(String segment) {
        String value = requiredSymbol(segment, "segment", 8).toUpperCase(Locale.ROOT);
        if (!value.equals("CASH") && !value.equals("FNO")) throw new IllegalArgumentException("segment must be CASH or FNO.");
        return value;
    }

    private String requiredSymbol(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength
                || !value.trim().matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " valid characters.");
        }
        return value.trim();
    }

    private String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.asText().trim();
    }

    private String optionalText(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > maxLength) {
            throw new IllegalArgumentException(field + " must be text of at most " + maxLength + " characters.");
        }
        return value.asText();
    }

    private LocalDateTime parseGrowwTime(String value, String field) {
        try {
            return LocalDateTime.parse(value, GROWW_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must use yyyy-MM-dd HH:mm:ss in India local market time.");
        }
    }

    private LocalDateTime candleTime(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) {
            long epoch = value.longValue();
            Instant instant = Math.abs(epoch) > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            return LocalDateTime.ofInstant(instant, INDIA);
        }
        if (!value.isTextual()) return null;
        String timestamp = value.asText();
        try {
            return OffsetDateTime.parse(timestamp).atZoneSameInstant(INDIA).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(timestamp, GROWW_TIME);
                } catch (DateTimeParseException invalid) {
                    return null;
                }
            }
        }
    }

    private Double finitePrice(JsonNode value) {
        if (value == null || !value.isNumber()) return null;
        double number = value.doubleValue();
        return Double.isFinite(number) && number > 0 ? number : null;
    }

    private Double finitePriceOrZero(JsonNode value) {
        if (value == null || !value.isNumber()) return null;
        double number = value.doubleValue();
        return Double.isFinite(number) && number >= 0 ? number : null;
    }

    private LocalDateTime candleEnd(LocalDateTime start, String interval) {
        return switch (interval) {
            case "1minute" -> start.plusMinutes(1);
            case "2minute" -> start.plusMinutes(2);
            case "3minute" -> start.plusMinutes(3);
            case "5minute" -> start.plusMinutes(5);
            case "10minute" -> start.plusMinutes(10);
            case "15minute" -> start.plusMinutes(15);
            case "30minute" -> start.plusMinutes(30);
            case "1hour" -> start.plusHours(1);
            case "4hour" -> start.plusHours(4);
            case "1day" -> start.plusDays(1);
            case "1week" -> start.plusWeeks(1);
            case "1month" -> start.plusMonths(1);
            default -> throw new IllegalArgumentException("Unsupported candle interval.");
        };
    }

    private long maximumRangeDays(String interval) {
        return switch (interval) {
            case "1minute", "2minute", "3minute", "5minute" -> 30;
            case "10minute", "15minute", "30minute" -> 90;
            case "1hour", "4hour", "1day", "1week", "1month" -> 180;
            default -> throw new IllegalArgumentException("Unsupported candle interval.");
        };
    }
    private String aiInterval(String interval) {
        return switch (interval) {
            case "1minute" -> "1m";
            case "2minute" -> "2m";
            case "3minute" -> "3m";
            case "5minute" -> "5m";
            case "10minute" -> "10m";
            case "15minute" -> "15m";
            case "30minute" -> "30m";
            case "1hour" -> "1h";
            case "4hour" -> "4h";
            case "1day" -> "1d";
            case "1week" -> "7d";
            case "1month" -> "30d";
            default -> throw new IllegalArgumentException("Unsupported candle interval.");
        };
    }

    private static Map<String, Object> unavailable(String state, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("connected", false);
        result.put("source", "none");
        result.put("liveData", false);
        result.put("fetchedAt", null);
        result.put("requestLatencyMs", null);
        result.put("nifty", null);
        result.put("bankNifty", null);
        result.put("note", message);
        return Collections.unmodifiableMap(result);
    }

    public record HistoricalRequest(
        String exchange, String segment, String growwSymbol, String startTime,
        String endTime, String candleInterval, String analysisPrompt, String knowledgeQuery) { }

    public record AnalysisInput(
        Map<String, Object> payload, int sourceCandleCount, int analysisCandleCount, String aiInterval) { }

    public record BacktestData(AnalysisInput signalInput, List<HistoricalBar> replayBars) { }

    public record HistoricalBar(
        OffsetDateTime timestamp, double open, double high, double low, double close, double volume) { }

    private record Strike(BigDecimal price, String key) { }
}
