package com.optionstrader.groww;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GrowwMarketDataService {
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

    @Scheduled(fixedDelayString = "${groww.api.option-chain-poll-interval:30000}", initialDelayString = "${groww.api.option-chain-initial-delay:5000}")
    public void refreshOptionChain() {
        if (!properties.isEnabled() || !properties.hasCredentials()) {
            latestOptionChain.set(Map.of("optionChainState", properties.isEnabled() ? "WAITING_FOR_CREDENTIALS" : "DISABLED"));
            return;
        }
        if (Instant.now().isBefore(optionRetryAfter)) return;
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            List<String> expiries = expiryDates(today.getYear(), today.getMonthValue());
            if (expiries.stream().noneMatch(date -> LocalDate.parse(date).compareTo(today) >= 0)) {
                LocalDate nextMonth = today.plusMonths(1);
                expiries = expiryDates(nextMonth.getYear(), nextMonth.getMonthValue());
            }
            LocalDate expiry = expiries.stream().map(LocalDate::parse).filter(date -> !date.isBefore(today)).min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("No upcoming NIFTY expiry was returned."));
            JsonNode chain = api.get("/v1/option-chain/exchange/NSE/underlying/NIFTY", Map.of("expiry_date", expiry.toString()));
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
    public Map<String, Object> optionChain(String underlying, LocalDate expiry) {
        String normalized = underlying == null ? "" : underlying.trim().toUpperCase();
        if (!normalized.equals("NIFTY") && !normalized.equals("BANKNIFTY")) {
            throw new IllegalArgumentException("Underlying must be NIFTY or BANKNIFTY.");
        }
        if (expiry == null) throw new IllegalArgumentException("Expiry date is required.");
        JsonNode chain = api.get("/v1/option-chain/exchange/NSE/underlying/" + normalized,
            Map.of("expiry_date", expiry.toString()));
        return Map.of("state", "LIVE", "source", "Groww API", "underlying", normalized,
            "expiry", expiry.toString(), "fetchedAt", Instant.now().toString(), "data", chain);
    }

    private List<String> expiryDates(int year, int month) {
        JsonNode response = api.get("/v1/historical/expiries", Map.of(
            "exchange", "NSE", "underlying_symbol", "NIFTY", "year", Integer.toString(year), "month", Integer.toString(month)));
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

    private record Strike(BigDecimal price, String key) { }
}