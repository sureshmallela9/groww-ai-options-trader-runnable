package com.optionstrader;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {
    private final ObjectProvider<ChatClient.Builder> provider;
    private final boolean enabled;
    private final String model;

    public AnalysisService(ObjectProvider<ChatClient.Builder> provider,
                           @Value("${trading.ai.enabled:true}") boolean enabled,
                           @Value("${spring.ai.ollama.chat.options.model:qwen2.5:7b}") String model) {
        this.provider = provider;
        this.enabled = enabled;
        this.model = model;
    }

    public Map<String, Object> analyze() {
        String fallback = "Market data is unavailable. No live market feed or verified news source is connected; no trade is authorized.";
        String timestamp = OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).toString();
        if (enabled) {
            try {
                ChatClient.Builder builder = provider.getIfAvailable();
                if (builder == null) {
                    return fallback(timestamp, fallback, "Spring AI ChatClient is unavailable.");
                }
                String result = builder.build()
                    .prompt()
                    .system("You are the analysis assistant inside an Indian options paper-trading application. No live prices, option-chain data, or verified news have been supplied. Never invent them. Return concise JSON with exactly these fields: market_bias (NEUTRAL), reasoning (string), trade_authorized (false). Never give a live trade recommendation. The Java risk engine, not you, controls orders.")
                    .user("Analyze the current market using only the evidence actually provided. There is no market data available in this request. Return JSON only.")
                    .call()
                    .content();
                return Map.of(
                    "mode", "SPRING_AI_OLLAMA",
                    "model", model,
                    "timestamp", timestamp,
                    "status", "NO_TRADE",
                    "analysis", Objects.toString(result, fallback),
                    "liveData", false,
                    "tradeAuthorized", false,
                    "note", "AI text is informational only; no live market data or broker execution is connected."
                );
            } catch (Exception e) {
                return fallback(timestamp, fallback, "AI unavailable: " + e.getClass().getSimpleName());
            }
        }
        return fallback(timestamp, fallback, "Spring AI is disabled by configuration.");
    }

    private Map<String, Object> fallback(String timestamp, String text, String note) {
        return Map.of(
            "mode", "RULE_BASED_FALLBACK",
            "model", model,
            "timestamp", timestamp,
            "status", "NO_TRADE",
            "analysis", text,
            "liveData", false,
            "tradeAuthorized", false,
            "note", note
        );
    }

    public Map<String, Object> status() {
        return Map.of(
            "enabled", enabled,
            "model", model,
            "provider", "Ollama via Spring AI",
            "ollamaBaseUrl", System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434"),
            "readyForLiveTrading", false
        );
    }
}
