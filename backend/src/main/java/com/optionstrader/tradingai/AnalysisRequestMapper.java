package com.optionstrader.tradingai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AnalysisRequestMapper {
    private static final Pattern INTERVAL = Pattern.compile(
        "(?i)^\\s*([0-9]+)\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)\\s*$");
    private static final Set<String> REQUEST_FIELDS = Set.of(
        "symbol", "interval", "analysis_prompt", "knowledge_query", "bars", "market_data");
    private static final Set<String> CANDLE_FIELDS = Set.of(
        "timestamp", "open", "high", "low", "close", "volume");

    public Map<String, Object> map(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object containing symbol, interval, and bars.");
        }
        rejectExtraFields(request, REQUEST_FIELDS, "request");

        String symbol = requiredText(request, "symbol").trim();
        if (symbol.length() > 32) {
            throw new IllegalArgumentException("symbol must be 1-32 characters.");
        }
        String rawInterval = requiredText(request, "interval");
        if (rawInterval.length() > 24) {
            throw new IllegalArgumentException("interval must be 1-24 characters.");
        }
        String interval = normalizeInterval(rawInterval);

        JsonNode barsNode = request.path("bars");
        if (!barsNode.isArray() || barsNode.size() < 50 || barsNode.size() > 5000) {
            throw new IllegalArgumentException("bars must contain 50-5,000 completed OHLCV candles.");
        }

        List<Map<String, Object>> bars = new ArrayList<>(barsNode.size());
        Set<Instant> timestamps = new HashSet<>();
        for (int index = 0; index < barsNode.size(); index++) {
            JsonNode bar = barsNode.get(index);
            if (!bar.isObject()) {
                throw new IllegalArgumentException("bars[" + index + "] must be a JSON object.");
            }
            bars.add(mapBar(bar, index, timestamps));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("interval", interval);
        payload.put("bars", bars);
        JsonNode marketData = request.get("market_data");
        if (marketData != null && !marketData.isNull()) {
            if (!marketData.isObject()) {
                throw new IllegalArgumentException("market_data must be a JSON object.");
            }
            payload.put("market_data", marketData.deepCopy());
        }
        copyOptionalText(request, payload, "analysis_prompt", 1500);
        copyOptionalText(request, payload, "knowledge_query", 500);
        return payload;
    }

    private Map<String, Object> mapBar(JsonNode bar, int index, Set<Instant> timestamps) {
        rejectExtraFields(bar, CANDLE_FIELDS, "bars[" + index + "]");
        String timestamp = requiredText(bar, "timestamp");
        Instant instant = timestampAsUtcInstant(timestamp, index);
        if (!timestamps.add(instant)) {
            throw new IllegalArgumentException("bars[" + index + "].timestamp duplicates another candle timestamp.");
        }

        double open = positiveNumber(bar, "open", index);
        double high = positiveNumber(bar, "high", index);
        double low = positiveNumber(bar, "low", index);
        double close = positiveNumber(bar, "close", index);
        nonNegativeNumber(bar, "volume", index);
        if (high < Math.max(open, close) || low > Math.min(open, close) || low > high) {
            throw new IllegalArgumentException("bars[" + index + "] has inconsistent OHLC values.");
        }

        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("timestamp", timestamp);
        mapped.put("open", bar.path("open").numberValue());
        mapped.put("high", bar.path("high").numberValue());
        mapped.put("low", bar.path("low").numberValue());
        mapped.put("close", bar.path("close").numberValue());
        mapped.put("volume", bar.path("volume").numberValue());
        return mapped;
    }

    private void rejectExtraFields(JsonNode node, Set<String> allowed, String objectName) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(objectName + " contains unsupported field '" + field + "'.");
            }
        });
    }

    private Instant timestampAsUtcInstant(String value, int index) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // The Trading AI API treats timezone-free ISO-8601 date-times as UTC.
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Try the timezone-free form using the API's documented UTC interpretation.
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("bars[" + index + "].timestamp must be an ISO 8601 date/time.");
        }
    }

    private String normalizeInterval(String value) {
        Matcher matcher = INTERVAL.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("interval must be a positive seconds, minutes, hours, or days value (for example 5s, 5m, 1h, or 3d).");
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("interval is too large.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("interval must be positive.");
        }

        String unit = matcher.group(2).toLowerCase();
        char suffix = switch (unit.charAt(0)) {
            case 's' -> 's';
            case 'm' -> 'm';
            case 'h' -> 'h';
            case 'd' -> 'd';
            default -> throw new IllegalArgumentException("Unsupported interval unit.");
        };
        return amount + String.valueOf(suffix);
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.asText();
    }

    private double positiveNumber(JsonNode bar, String field, int index) {
        double value = finiteNumber(bar, field, index);
        if (value <= 0) {
            throw new IllegalArgumentException("bars[" + index + "]." + field + " must be positive.");
        }
        return value;
    }

    private void nonNegativeNumber(JsonNode bar, String field, int index) {
        double value = finiteNumber(bar, field, index);
        if (value < 0) {
            throw new IllegalArgumentException("bars[" + index + "]." + field + " must be non-negative.");
        }
    }

    private double finiteNumber(JsonNode bar, String field, int index) {
        JsonNode value = bar.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("bars[" + index + "]." + field + " must be a finite number.");
        }
        return value.doubleValue();
    }

    private void copyOptionalText(JsonNode request, Map<String, Object> payload, String field, int maxLength) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual() || value.asText().length() > maxLength) {
            throw new IllegalArgumentException(field + " must be text of at most " + maxLength + " characters.");
        }
        payload.put(field, value.asText());
    }
}
