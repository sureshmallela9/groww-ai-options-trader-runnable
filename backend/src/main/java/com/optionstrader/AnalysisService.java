package com.optionstrader;

import com.fasterxml.jackson.databind.JsonNode;
import com.optionstrader.tradingai.AnalysisRequestMapper;
import com.optionstrader.tradingai.TradingAiClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AnalysisService {
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_AUTOMATIC_RETRY_DELAY = Duration.ofSeconds(5);

    private final AnalysisRequestMapper requestMapper;
    private final TradingAiClient tradingAi;
    private final boolean enabled;
    private final Duration timeout;
    private final int maxInFlightAnalyses;
    private final Semaphore inFlightAnalyses;
    private final AtomicLong lastLatencyMs = new AtomicLong(-1);
    private volatile Instant lastCompletedAt;

    public AnalysisService(
            AnalysisRequestMapper requestMapper,
            TradingAiClient tradingAi,
            @Value("${trading.ai.enabled:true}") boolean enabled,
            @Value("${trading.ai.timeout:20s}") Duration timeout,
            @Value("${trading.ai.max-in-flight:12}") int configuredMaxInFlight) {
        this.requestMapper = requestMapper;
        this.tradingAi = tradingAi;
        this.enabled = enabled;
        this.timeout = timeout;
        this.maxInFlightAnalyses = Math.max(1, Math.min(32, configuredMaxInFlight));
        this.inFlightAnalyses = new Semaphore(this.maxInFlightAnalyses);
    }

    public Map<String, Object> analyze(JsonNode request) {
        if (request == null || request.isNull()) {
            return candlesRequired();
        }

        final Map<String, Object> payload;
        try {
            payload = requestMapper.map(request);
        } catch (IllegalArgumentException e) {
            return requestRejected(e.getMessage());
        }
        if (!enabled || !tradingAi.enabled()) {
            return serviceUnavailable("Trading AI integration is disabled.");
        }
        if (!inFlightAnalyses.tryAcquire()) {
            return busy();
        }

        long started = System.nanoTime();
        try {
            Map<String, Object> response = new LinkedHashMap<>(analyzeWithBoundedRetry(payload));
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            updateLatency(elapsed);
            response.putIfAbsent("provider", "Local Trading AI API");
            response.putIfAbsent("mode", "LOCAL_TRADING_AI");
            response.put("tradeAuthorized", false);
            response.put("totalLatencyMs", elapsed);
            return response;
        } catch (RestClientResponseException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            updateLatency(elapsed);
            return upstreamError(e, elapsed);
        } catch (ResourceAccessException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            updateLatency(elapsed);
            return serviceUnavailable("Trading AI timed out or is unavailable. A timed-out request may already have saved a report; check Saved reports before retrying.", elapsed);
        } catch (RuntimeException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            updateLatency(elapsed);
            return serviceUnavailable("Trading AI request failed (" + e.getClass().getSimpleName() + ").", elapsed);
        } finally {
            inFlightAnalyses.release();
        }
    }

    private Map<String, Object> analyzeWithBoundedRetry(Map<String, Object> payload) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tradingAi.analyze(payload);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 503 || attempt >= 1) {
                    throw e;
                }
                Duration delay = retryDelay(e);
                if (delay.compareTo(MAX_AUTOMATIC_RETRY_DELAY) > 0) {
                    throw e;
                }
                try {
                    if (!delay.isZero()) Thread.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ResourceAccessException("Interrupted while waiting to retry Trading AI.", new IOException("Retry delay was interrupted.", interrupted));
                }
            }
        }
    }

    private Duration retryDelay(RestClientResponseException e) {
        String value = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return DEFAULT_RETRY_DELAY;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? DEFAULT_RETRY_DELAY : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException invalidDate) {
                return DEFAULT_RETRY_DELAY;
            }
        }
    }

    public SseEmitter streamAnalysis(JsonNode request) {
        SseEmitter emitter = new SseEmitter(timeout.plusSeconds(10).toMillis());
        AtomicReference<Thread> worker = new AtomicReference<>();
        emitter.onTimeout(() -> {
            Thread running = worker.get();
            if (running != null) running.interrupt();
            emitter.complete();
        });
        emitter.onCompletion(() -> {
            Thread running = worker.get();
            if (running != null && running.isAlive()) running.interrupt();
        });

        Thread running = Thread.startVirtualThread(() -> {
            long started = System.nanoTime();
            send(emitter, "meta", Map.of("provider", "Local Trading AI API", "tradeAuthorized", false));
            Map<String, Object> result = analyze(request);
            send(emitter, "report", result);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            send(emitter, "done", Map.of("totalLatencyMs", elapsed, "tradeAuthorized", false));
            emitter.complete();
        });
        worker.set(running);
        return emitter;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled && tradingAi.enabled());
        status.put("provider", "Local Trading AI API");
        status.put("baseUrl", tradingAi.baseUrl());
        status.put("timeoutSeconds", timeout.toSeconds());
        status.put("candlesAvailable", false);
        status.put("candleSource", "This project currently has no completed OHLCV candle history.");
        status.put("lastTotalLatencyMs", lastLatencyMs.get() < 0 ? null : lastLatencyMs.get());
        status.put("lastCompletedAt", lastCompletedAt == null ? null : lastCompletedAt.toString());
        status.put("readyForLiveTrading", false);
        status.put("maxInFlightAnalyses", maxInFlightAnalyses);

        if (!enabled || !tradingAi.enabled()) {
            status.put("connected", false);
            status.put("ready", false);
            status.put("health", null);
            status.put("state", "DISABLED");
            return status;
        }
        try {
            Object health = tradingAi.health();
            status.put("connected", true);
            status.put("ready", healthReady(health));
            status.put("health", health);
            status.put("state", healthReady(health) ? "READY" : "NOT_READY");
            if (health instanceof Map<?, ?> values) {
                status.put("knowledge", values.get("knowledge"));
                status.put("reportStorage", values.get("report_storage"));
                status.put("analysisWorkers", values.get("analysis_workers"));
            }
        } catch (Exception e) {
            status.put("connected", false);
            status.put("ready", false);
            status.put("health", null);
            status.put("state", "UNAVAILABLE");
            status.put("error", "Trading AI health check failed (" + e.getClass().getSimpleName() + ").");
        }
        return status;
    }

    private boolean healthReady(Object health) {
        if (health instanceof Map<?, ?> values && values.get("status") != null) {
            String state = values.get("status").toString().trim().toLowerCase(Locale.ROOT);
            return state.equals("ok") || state.equals("ready") || state.equals("healthy")
                || state.equals("up") || state.equals("success");
        }
        return true;
    }

    public Object reports() {
        try {
            return tradingAi.reports();
        } catch (RestClientResponseException e) {
            return reportError(e);
        } catch (Exception e) {
            return Map.of("status", "UNAVAILABLE", "error", "Trading AI reports could not be loaded.");
        }
    }

    public Object report(long reportId) {
        if (reportId <= 0) {
            throw new IllegalArgumentException("report_id must be positive.");
        }
        try {
            return tradingAi.report(reportId);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new NoSuchElementException("Trading AI report " + reportId + " was not found.");
            }
            return reportError(e);
        } catch (Exception e) {
            return Map.of("status", "UNAVAILABLE", "error", "Trading AI report could not be loaded.");
        }
    }

    private Map<String, Object> busy() {
        Map<String, Object> response = serviceUnavailable("Trading AI is at its in-flight request limit; retry after the indicated delay.");
        response.put("status", "BUSY");
        response.put("retryAfter", "2");
        return response;
    }

    private Map<String, Object> candlesRequired() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "CANDLES_REQUIRED");
        response.put("provider", "Local Trading AI API");
        response.put("analysis", "This project has no completed OHLCV candle history to analyze. Submit 50-5,000 actual completed candles; quote ticks and option-chain snapshots are not candles.");
        response.put("tradeAuthorized", false);
        response.put("totalLatencyMs", 0);
        return response;
    }

    private Map<String, Object> requestRejected(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "INVALID_REQUEST");
        response.put("provider", "Local Trading AI API");
        response.put("analysis", message);
        response.put("tradeAuthorized", false);
        return response;
    }

    private Map<String, Object> serviceUnavailable(String message) {
        return serviceUnavailable(message, null);
    }

    private Map<String, Object> serviceUnavailable(String message, Long elapsed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UNAVAILABLE");
        response.put("provider", "Local Trading AI API");
        response.put("analysis", message);
        response.put("tradeAuthorized", false);
        response.put("totalLatencyMs", elapsed);
        return response;
    }

    private Map<String, Object> upstreamError(RestClientResponseException e, long elapsed) {
        int statusCode = e.getStatusCode().value();
        Map<String, Object> response = serviceUnavailable(
            statusCode == 503 ? "Trading AI is busy or unavailable. Retry after the indicated delay." :
                statusCode == 422 ? "Trading AI rejected the candle data (HTTP 422). Check the request and completed OHLCV values." :
                    "Trading AI returned HTTP " + statusCode + ".",
            elapsed);
        response.put("status", statusCode == 422 ? "INVALID_CANDLES" : statusCode == 503 ? "BUSY" : "UPSTREAM_ERROR");
        if (statusCode == 503) {
            String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) response.put("retryAfter", retryAfter);
        }
        return response;
    }

    private Object reportError(RestClientResponseException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", e.getStatusCode().value() == 503 ? "BUSY" : "UPSTREAM_ERROR");
        error.put("error", "Trading AI reports request returned HTTP " + e.getStatusCode().value() + ".");
        if (e.getResponseHeaders() != null) {
            String retryAfter = e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) error.put("retryAfter", retryAfter);
        }
        return error;
    }

    private void updateLatency(long elapsed) {
        lastLatencyMs.set(elapsed);
        lastCompletedAt = Instant.now();
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException ignored) {
            // The browser disconnected; analysis has no effect on Groww or paper trades.
        }
    }
}
