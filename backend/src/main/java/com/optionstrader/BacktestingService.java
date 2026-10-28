package com.optionstrader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.groww.GrowwMarketDataService;
import com.optionstrader.groww.GrowwMarketDataService.BacktestData;
import com.optionstrader.groww.GrowwMarketDataService.HistoricalBar;
import com.optionstrader.groww.GrowwMarketDataService.HistoricalRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BacktestingService {
    private static final int SIGNAL_BARS = 50;
    private static final BigDecimal STOP_ATR_MULTIPLIER = BigDecimal.ONE;
    private static final BigDecimal TARGET_ATR_MULTIPLIER = new BigDecimal("2.0");
    private static final Set<String> FAILED_ANALYSIS_STATES = Set.of(
        "CANDLES_REQUIRED", "INVALID_REQUEST", "UNAVAILABLE", "BUSY", "INVALID_CANDLES", "UPSTREAM_ERROR");

    private final GrowwMarketDataService marketData;
    private final AnalysisService analysis;
    private final TradingService trading;
    private final ObjectMapper objectMapper;
    private final BigDecimal chargesPerTrade;

    public BacktestingService(GrowwMarketDataService marketData, AnalysisService analysis,
                              TradingService trading, ObjectMapper objectMapper,
                              @Value("${trading.backtest.charges-per-trade:5.00}") BigDecimal chargesPerTrade) {
        this.marketData = marketData;
        this.analysis = analysis;
        this.trading = trading;
        this.objectMapper = objectMapper;
        this.chargesPerTrade = chargesPerTrade.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> run(JsonNode body) {
        HistoricalRequest request = marketData.parseHistoricalRequest(body);
        JsonNode growwResponse = marketData.fetchHistoricalCandles(request);
        BacktestData data = marketData.buildBacktestData(request, growwResponse);
        Map<String, Object> aiResult = analysis.analyze(objectMapper.valueToTree(data.signalInput().payload()));

        List<HistoricalBar> bars = data.replayBars();
        HistoricalBar signalBar = bars.get(SIGNAL_BARS - 1);
        Map<String, Object> source = source(request, data, signalBar);
        String state = string(aiResult.get("status")).toUpperCase(Locale.ROOT);
        String trend = string(aiResult.get("trend")).toLowerCase(Locale.ROOT);
        boolean analysisFailed = FAILED_ANALYSIS_STATES.contains(state) || aiResult.containsKey("error");
        String side = analysisFailed ? "HOLD" : switch (trend) {
            case "bullish" -> "LONG";
            case "bearish" -> "SHORT";
            default -> "HOLD";
        };

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("action", side.equals("LONG") ? "BUY" : side.equals("SHORT") ? "SELL" : "HOLD");
        decision.put("side", side);
        decision.put("aiTrend", trend.isBlank() ? null : trend);
        decision.put("signalTime", signalBar.timestamp().toString());
        decision.put("analysisReportId", aiResult.get("report_id"));
        decision.put("reason", decisionReason(analysisFailed, trend));
        decision.put("execution", "PAPER_BACKTEST_ONLY");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", aiResult.getOrDefault("status", "ANALYZED"));
        result.put("source", source);
        result.put("analysis", aiResult);
        result.put("decision", decision);
        result.put("tradeAuthorized", false);
        result.put("liveOrderSent", false);

        if (!side.equals("HOLD")) {
            BigDecimal atr = atr(aiResult, bars.subList(0, SIGNAL_BARS));
            if (atr.signum() > 0) {
                SimulatedFill fill;
                try {
                    fill = simulate(side, bars, atr, request.candleInterval());
                } catch (IllegalArgumentException invalidPlan) {
                    result.put("backtest", backtestSummary(data, side, atr, null, null, invalidPlan.getMessage()));
                    result.put("paperOrderPlaced", false);
                    return result;
                }
                String riskError = riskLimitError(fill.entryPrice(), atr);
                if (riskError == null) {
                    List<String> evidence = List.of(
                        "BACKTEST",
                        "Signal derived from Trading AI trend: " + trend,
                        "AI signal used the first " + SIGNAL_BARS + " complete candles; later candles were reserved for replay.",
                        "One price unit; exchange lot size, slippage, taxes, and market impact are not modeled.",
                        "Stop loss: 1 ATR; target: 2 ATR; if both trigger in one candle, stop is applied first.",
                        "AI report ID: " + String.valueOf(aiResult.get("report_id")));
                    Map<String, Object> trade = trading.recordBacktestTrade(
                        request.growwSymbol(), side, 1, fill.entryPrice(), fill.exitPrice(),
                        fill.entryTime(), fill.exitTime(), chargesPerTrade,
                        "AI " + trend + " trend; simulated " + (side.equals("LONG") ? "BUY" : "SELL")
                            + " at the first candle after the signal window.",
                        fill.exitReason(), evidence);
                    Map<String, Object> backtest = backtestSummary(data, side, atr, fill, trade, null);
                    result.put("backtest", backtest);
                    result.put("paperOrderPlaced", true);
                    return result;
                }
                result.put("backtest", backtestSummary(data, side, atr, null, null, riskError));
                result.put("paperOrderPlaced", false);
                return result;
            }
            result.put("backtest", backtestSummary(data, side, BigDecimal.ZERO, null, null,
                "Trading AI did not return a positive ATR and the candle history could not produce one."));
            result.put("paperOrderPlaced", false);
            return result;
        }

        result.put("backtest", backtestSummary(data, side, BigDecimal.ZERO, null, null,
            decisionReason(analysisFailed, trend)));
        result.put("paperOrderPlaced", false);
        return result;
    }

    private Map<String, Object> source(HistoricalRequest request, BacktestData data, HistoricalBar signalBar) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("provider", "GROWW");
        source.put("exchange", request.exchange());
        source.put("segment", request.segment());
        source.put("growwSymbol", request.growwSymbol());
        source.put("candleInterval", request.candleInterval());
        source.put("startTime", request.startTime());
        source.put("endTime", request.endTime());
        source.put("sourceCandleCount", data.signalInput().sourceCandleCount());
        source.put("signalCandleCount", data.signalInput().analysisCandleCount());
        source.put("replayCandleCount", data.replayBars().size() - SIGNAL_BARS);
        source.put("signalCutoff", signalBar.timestamp().toString());
        source.put("futureCandlesSentToAi", false);
        return source;
    }

    private Map<String, Object> backtestSummary(BacktestData data, String side, BigDecimal atr,
                                                 SimulatedFill fill, Map<String, Object> trade,
                                                 String noTradeReason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("state", trade == null ? "NO_TRADE" : "COMPLETED");
        summary.put("side", side);
        summary.put("signalBars", SIGNAL_BARS);
        summary.put("replayBars", data.replayBars().size() - SIGNAL_BARS);
        summary.put("stopLossAtrMultiplier", STOP_ATR_MULTIPLIER);
        summary.put("takeProfitAtrMultiplier", TARGET_ATR_MULTIPLIER);
        summary.put("atr", atr);
        summary.put("charges", chargesPerTrade);
        summary.put("feesAndSlippage", "Fixed charge applied; lot sizing, taxes, slippage, and market impact are excluded.");
        summary.put("noTradeReason", noTradeReason);
        if (fill != null) {
            summary.put("entryPrice", fill.entryPrice());
            summary.put("exitPrice", fill.exitPrice());
            summary.put("entryTime", fill.entryTime());
            summary.put("exitTime", fill.exitTime());
            summary.put("exitReason", fill.exitReason());
            summary.put("trade", trade);
        }
        return summary;
    }

    private String decisionReason(boolean analysisFailed, String trend) {
        if (analysisFailed) return "Trading AI did not return a usable analysis; the backtest fails closed without an order.";
        return switch (trend) {
            case "bullish" -> "Trading AI trend was bullish; the paper backtest maps this to BUY.";
            case "bearish" -> "Trading AI trend was bearish; the paper backtest maps this to SELL.";
            default -> "Trading AI trend was mixed or missing; the paper backtest maps this to HOLD.";
        };
    }

    private BigDecimal atr(Map<String, Object> aiResult, List<HistoricalBar> signalBars) {
        Object indicators = aiResult.get("indicators");
        if (indicators instanceof Map<?, ?> values) {
            BigDecimal reported = decimal(values.get("atr_14"));
            if (reported != null && reported.signum() > 0) return reported;
        }
        if (signalBars.size() < 15) return BigDecimal.ZERO;
        double total = 0;
        int count = 0;
        for (int i = signalBars.size() - 14; i < signalBars.size(); i++) {
            HistoricalBar current = signalBars.get(i);
            double previousClose = signalBars.get(i - 1).close();
            double trueRange = Math.max(current.high() - current.low(),
                Math.max(Math.abs(current.high() - previousClose), Math.abs(current.low() - previousClose)));
            if (Double.isFinite(trueRange) && trueRange > 0) {
                total += trueRange;
                count++;
            }
        }
        return count == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(total / count);
    }

    private String riskLimitError(BigDecimal entry, BigDecimal atr) {
        Map<String, Object> settings = trading.settings();
        BigDecimal capital = decimal(settings.get("capitalLimit"));
        BigDecimal riskPercent = decimal(settings.get("riskPerTradePercent"));
        if (capital == null || riskPercent == null) return "Paper risk settings are unavailable.";
        BigDecimal riskLimit = capital.multiply(riskPercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        if (entry.add(chargesPerTrade).compareTo(capital) > 0) return "One-unit entry value and configured charge exceed the paper capital limit.";
        if (atr.add(chargesPerTrade).compareTo(riskLimit) > 0) return "One-unit stop risk and configured charge exceed the paper risk-per-trade limit.";
        return null;
    }

    private SimulatedFill simulate(String side, List<HistoricalBar> bars, BigDecimal atr, String candleInterval) {
        HistoricalBar entryBar = bars.get(SIGNAL_BARS);
        BigDecimal entry = price(entryBar.open());
        BigDecimal direction = side.equals("LONG") ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal stop = price(entry.subtract(atr.multiply(STOP_ATR_MULTIPLIER).multiply(direction)).doubleValue());
        BigDecimal target = price(entry.add(atr.multiply(TARGET_ATR_MULTIPLIER).multiply(direction)).doubleValue());
        if (stop.signum() <= 0 || target.signum() <= 0) {
            throw new IllegalArgumentException("The AI-derived stop or target price is not positive.");
        }

        for (int i = SIGNAL_BARS; i < bars.size(); i++) {
            HistoricalBar bar = bars.get(i);
            boolean stopHit = side.equals("LONG") ? bar.low() <= stop.doubleValue() : bar.high() >= stop.doubleValue();
            boolean targetHit = side.equals("LONG") ? bar.high() >= target.doubleValue() : bar.low() <= target.doubleValue();
            if (stopHit) {
                BigDecimal exit = gapThroughStop(side, bar, stop) ? price(bar.open()) : stop;
                return new SimulatedFill(entry, exit, entryBar.timestamp(),
                    marketData.candleCloseTime(bar.timestamp(), candleInterval), "STOP_LOSS");
            }
            if (targetHit) {
                BigDecimal exit = gapThroughTarget(side, bar, target) ? price(bar.open()) : target;
                return new SimulatedFill(entry, exit, entryBar.timestamp(),
                    marketData.candleCloseTime(bar.timestamp(), candleInterval), "TAKE_PROFIT");
            }
        }
        HistoricalBar last = bars.get(bars.size() - 1);
        return new SimulatedFill(entry, price(last.close()), entryBar.timestamp(),
            marketData.candleCloseTime(last.timestamp(), candleInterval), "END_OF_TEST");
    }

    private boolean gapThroughStop(String side, HistoricalBar bar, BigDecimal stop) {
        return side.equals("LONG") ? bar.open() < stop.doubleValue() : bar.open() > stop.doubleValue();
    }

    private boolean gapThroughTarget(String side, HistoricalBar bar, BigDecimal target) {
        return side.equals("LONG") ? bar.open() > target.doubleValue() : bar.open() < target.doubleValue();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return BigDecimal.valueOf(number.doubleValue());
        try { return value == null ? null : new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private BigDecimal price(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record SimulatedFill(BigDecimal entryPrice, BigDecimal exitPrice,
                                 OffsetDateTime entryTime, OffsetDateTime exitTime, String exitReason) { }
}
