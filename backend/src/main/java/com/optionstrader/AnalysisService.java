package com.optionstrader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.groww.GrowwMarketDataService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

@Service
public class AnalysisService {
    private final ObjectProvider<ChatClient.Builder> provider;
    private final GrowwMarketDataService marketData;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String model;
    private final Duration timeout;
    private final AtomicLong lastTotalLatencyMs = new AtomicLong(-1);
    private final AtomicLong lastFirstContentMs = new AtomicLong(-1);
    private volatile Instant lastCompletedAt;

    public AnalysisService(ObjectProvider<ChatClient.Builder> provider,
                           GrowwMarketDataService marketData,
                           ObjectMapper objectMapper,
                           @Value("${trading.ai.enabled:true}") boolean enabled,
                           @Value("${spring.ai.ollama.chat.options.model:qwen2.5:7b}") String model,
                           @Value("${trading.ai.timeout:45s}") Duration timeout) {
        this.provider = provider;
        this.marketData = marketData;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.model = model;
        this.timeout = timeout;
    }

    public Map<String, Object> analyze() {
        String timestamp = OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).toString();
        Map<String, Object> market = marketData.analysisSnapshot();
        if (!enabled) return fallback(timestamp, "Spring AI is disabled by configuration.", market);
        long started = System.nanoTime();
        try {
            ChatClient.Builder builder = provider.getIfAvailable();
            if (builder == null) return fallback(timestamp, "Spring AI ChatClient is unavailable.", market);
            String marketJson = objectMapper.writeValueAsString(market);
            AtomicLong firstContentAt = new AtomicLong(-1);
            List<String> chunks = builder.build()
                .prompt()
                .system(systemPrompt())
                .user(userPrompt(marketJson))
                .stream()
                .content()
                .timeout(timeout)
                .doOnNext(chunk -> firstContentAt.compareAndSet(-1, System.nanoTime()))
                .collectList()
                .block(timeout.plusSeconds(2));
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            long firstMs = firstContentAt.get() < 0 ? -1 : (firstContentAt.get() - started) / 1_000_000;
            updateLatency(elapsed, firstMs);
            String result = chunks == null ? "" : String.join("", chunks);
            Map<String, Object> response = base(timestamp, market);
            response.put("mode", "SPRING_AI_OLLAMA");
            response.put("status", "NO_TRADE");
            response.put("analysis", result.isBlank() ? "No analysis content returned." : result);
            response.put("tradeAuthorized", false);
            response.put("totalLatencyMs", elapsed);
            response.put("firstContentMs", firstMs < 0 ? null : firstMs);
            response.put("note", "AI uses the latest available Groww market and option-chain snapshot; it cannot authorize orders.");
            return response;
        } catch (Exception e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            updateLatency(elapsed, -1);
            return fallback(timestamp, "AI unavailable or timed out (" + e.getClass().getSimpleName() + ").", market, elapsed);
        }
    }

    public SseEmitter streamAnalysis() {
        SseEmitter emitter = new SseEmitter(timeout.plusSeconds(15).toMillis());
        AtomicReference<Disposable> active = new AtomicReference<>();
        emitter.onTimeout(() -> disposeAndComplete(active, emitter));
        emitter.onCompletion(() -> dispose(active));
        Thread.startVirtualThread(() -> {
            long started = System.nanoTime();
            String timestamp = OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).toString();
            Map<String, Object> market = marketData.analysisSnapshot();
            if (!enabled) {
                send(emitter, "meta", Map.of("mode", "RULE_BASED_FALLBACK", "model", model, "liveData", Boolean.TRUE.equals(market.get("liveData"))));
                send(emitter, "delta", "AI is disabled. No trade is authorized.");
                send(emitter, "done", Map.of("totalLatencyMs", 0, "firstContentMs", -1, "tradeAuthorized", false));
                emitter.complete();
                return;
            }
            ChatClient.Builder builder = provider.getIfAvailable();
            if (builder == null) {
                send(emitter, "meta", Map.of("mode", "RULE_BASED_FALLBACK", "model", model, "liveData", Boolean.TRUE.equals(market.get("liveData"))));
                send(emitter, "delta", "Spring AI ChatClient is unavailable. No trade is authorized.");
                emitter.complete();
                return;
            }
            send(emitter, "meta", Map.of("mode", "SPRING_AI_OLLAMA", "model", model, "liveData", Boolean.TRUE.equals(market.get("liveData"))));
            try {
                String marketJson = objectMapper.writeValueAsString(market);
                AtomicLong firstContentAt = new AtomicLong(-1);
                Disposable disposable = builder.build()
                    .prompt()
                    .system(systemPrompt())
                    .user(userPrompt(marketJson))
                    .stream()
                    .content()
                    .timeout(timeout)
                    .doOnNext(chunk -> firstContentAt.compareAndSet(-1, System.nanoTime()))
                    .subscribe(
                        chunk -> {
                            if (!send(emitter, "delta", chunk)) dispose(active);
                        },
                        error -> {
                            long elapsed = (System.nanoTime() - started) / 1_000_000;
                            long firstMs = firstContentAt.get() < 0 ? -1 : (firstContentAt.get() - started) / 1_000_000;
                            updateLatency(elapsed, firstMs);
                            send(emitter, "error", "Ollama stream failed or timed out (" + error.getClass().getSimpleName() + ").");
                            send(emitter, "done", latency(elapsed, firstMs));
                            emitter.complete();
                        },
                        () -> {
                            long elapsed = (System.nanoTime() - started) / 1_000_000;
                            long firstMs = firstContentAt.get() < 0 ? -1 : (firstContentAt.get() - started) / 1_000_000;
                            updateLatency(elapsed, firstMs);
                            send(emitter, "done", latency(elapsed, firstMs));
                            emitter.complete();
                        });
                active.set(disposable);
            } catch (Exception e) {
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                updateLatency(elapsed, -1);
                send(emitter, "error", "AI request failed (" + e.getClass().getSimpleName() + ").");
                send(emitter, "done", latency(elapsed, -1));
                emitter.complete();
            }
        });
        return emitter;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("model", model);
        status.put("provider", "Ollama via Spring AI");
        status.put("ollamaBaseUrl", System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434"));
        status.put("timeoutSeconds", timeout.toSeconds());
        status.put("lastTotalLatencyMs", lastTotalLatencyMs.get() < 0 ? null : lastTotalLatencyMs.get());
        status.put("lastFirstContentMs", lastFirstContentMs.get() < 0 ? null : lastFirstContentMs.get());
        status.put("lastCompletedAt", lastCompletedAt == null ? null : lastCompletedAt.toString());
        status.put("readyForLiveTrading", false);
        return status;
    }

    private String systemPrompt() {
        return "You analyze market data for a PAPER-trading application. Use only the supplied Groww snapshot; it may be unavailable or stale. Never invent prices, news, contracts, or indicators. Return concise JSON with market_bias (BULLISH, BEARISH, or NEUTRAL), reasoning, and trade_authorized=false. Always set trade_authorized to false. No live order or financial instruction is authorized.";
    }

    private String userPrompt(String marketJson) {
        return "Analyze this current market and nearest NIFTY option-chain snapshot. If liveData is not true, return NEUTRAL and explain that current data is unavailable. Snapshot: " + marketJson;
    }

    private void updateLatency(long elapsed, long firstContent) {
        lastTotalLatencyMs.set(elapsed);
        lastFirstContentMs.set(firstContent);
        lastCompletedAt = Instant.now();
    }

    private Map<String, Object> latency(long elapsed, long firstContent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalLatencyMs", elapsed);
        result.put("firstContentMs", firstContent);
        result.put("tradeAuthorized", false);
        return result;
    }

    private boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private void disposeAndComplete(AtomicReference<Disposable> active, SseEmitter emitter) {
        dispose(active);
        emitter.complete();
    }

    private void dispose(AtomicReference<Disposable> active) {
        Disposable current = active.getAndSet(null);
        if (current != null && !current.isDisposed()) current.dispose();
    }

    private Map<String, Object> fallback(String timestamp, String note, Map<String, Object> market) {
        return fallback(timestamp, note, market, null);
    }

    private Map<String, Object> fallback(String timestamp, String note, Map<String, Object> market, Long elapsed) {
        Map<String, Object> response = base(timestamp, market);
        response.put("mode", "RULE_BASED_FALLBACK");
        response.put("status", "NO_TRADE");
        response.put("analysis", "No live market analysis was produced. No trade is authorized.");
        response.put("tradeAuthorized", false);
        response.put("totalLatencyMs", elapsed);
        response.put("firstContentMs", null);
        response.put("note", note);
        return response;
    }

    private Map<String, Object> base(String timestamp, Map<String, Object> market) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", model);
        response.put("timestamp", timestamp);
        response.put("liveData", Boolean.TRUE.equals(market.get("liveData")));
        response.put("marketData", market);
        return response;
    }
}