package com.optionstrader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionstrader.persistence.PaperTradeEntity;
import com.optionstrader.persistence.PaperTradeRepository;
import com.optionstrader.persistence.TradingSettingsEntity;
import com.optionstrader.persistence.TradingSettingsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingService {
    private static final String DEFAULT_SETTINGS_ID = "DEFAULT";
    private final PaperTradeRepository trades;
    private final TradingSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;
    private final ZoneId zone = ZoneId.of("Asia/Kolkata");

    public TradingService(PaperTradeRepository trades, TradingSettingsRepository settingsRepository, ObjectMapper objectMapper) {
        this.trades = trades;
        this.settingsRepository = settingsRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settings() {
        return settingsMap(loadSettings());
    }

    @Transactional
    public Map<String, Object> update(Map<String, Object> input) {
        BigDecimal capital = decimal(input, "capitalLimit");
        BigDecimal risk = decimal(input, "riskPerTradePercent");
        BigDecimal loss = decimal(input, "dailyLossPercent");
        int maxEntries = integer(input, "maxEntryOrdersPerDay");
        int stopWins = integer(input, "stopAfterProfitableTrades");
        if (capital.signum() <= 0 || risk.signum() <= 0 || risk.compareTo(new BigDecimal("5")) > 0
            || loss.signum() <= 0 || loss.compareTo(new BigDecimal("10")) > 0
            || maxEntries < 1 || maxEntries > 30 || stopWins < 1 || stopWins > 20) {
            throw new IllegalArgumentException("Invalid settings. Capital must be positive; risk <=5%; daily loss <=10%; entries 1–30; profitable stop 1–20.");
        }
        TradingSettingsEntity settings = loadSettings();
        settings.setCapitalLimit(capital);
        settings.setRiskPerTradePercent(risk);
        settings.setDailyLossPercent(loss);
        settings.setMaxEntryOrdersPerDay(maxEntries);
        settings.setStopAfterProfitableTrades(stopWins);
        return settingsMap(settingsRepository.save(settings));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        TradingSettingsEntity settings = loadSettings();
        List<PaperTradeEntity> today = todayTrades();
        long wins = today.stream().filter(t -> "CLOSED".equals(t.getStatus()) && t.getNetPnl() != null && t.getNetPnl().signum() > 0).count();
        BigDecimal pnl = today.stream().map(PaperTradeEntity::getNetPnl).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        long open = today.stream().filter(t -> "OPEN".equals(t.getStatus())).count();
        LocalTime now = LocalTime.now(zone);
        return Map.ofEntries(
            Map.entry("mode", "PAPER"),
            Map.entry("capitalLimit", settings.getCapitalLimit()),
            Map.entry("riskPerTradePercent", settings.getRiskPerTradePercent()),
            Map.entry("dailyLossPercent", settings.getDailyLossPercent()),
            Map.entry("dailyLossLimit", settings.getCapitalLimit().multiply(settings.getDailyLossPercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)),
            Map.entry("maxEntryOrdersPerDay", settings.getMaxEntryOrdersPerDay()),
            Map.entry("entryOrdersUsed", today.size()),
            Map.entry("stopAfterProfitableTrades", settings.getStopAfterProfitableTrades()),
            Map.entry("profitableTrades", wins),
            Map.entry("netPnl", pnl),
            Map.entry("openPositions", open),
            Map.entry("sessionOpen", !now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(14, 58))),
            Map.entry("currentTime", OffsetDateTime.now(zone).toString()),
            Map.entry("entryStart", "09:15"),
            Map.entry("entryCutoff", "14:58"),
            Map.entry("squareOffStart", "14:57"),
            Map.entry("trades", all()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> all() {
        return trades.findAllByOrderByEntryTimeDesc().stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> execute() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            throw new IllegalStateException("Weekend guard: no entries. Official exchange calendar is not configured.");
        }
        if (now.toLocalTime().isBefore(LocalTime.of(9, 15)) || !now.toLocalTime().isBefore(LocalTime.of(14, 58))) {
            throw new IllegalStateException("Outside entry window 09:15–14:58 IST.");
        }
        TradingSettingsEntity settings = loadSettings();
        List<PaperTradeEntity> today = todayTrades();
        long wins = today.stream().filter(t -> "CLOSED".equals(t.getStatus()) && t.getNetPnl() != null && t.getNetPnl().signum() > 0).count();
        BigDecimal pnl = today.stream().map(PaperTradeEntity::getNetPnl).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lossLimit = settings.getCapitalLimit().multiply(settings.getDailyLossPercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        if (today.size() >= settings.getMaxEntryOrdersPerDay()) throw new IllegalStateException("Daily entry limit reached.");
        if (wins >= settings.getStopAfterProfitableTrades()) throw new IllegalStateException("Profitable-trade stop reached.");
        if (pnl.compareTo(lossLimit.negate()) <= 0) throw new IllegalStateException("Daily loss limit reached.");

        String id = "PAPER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        OffsetDateTime entryTime = OffsetDateTime.now(zone);
        PaperTradeEntity trade = new PaperTradeEntity();
        trade.setId(id);
        trade.setSymbol("NIFTY-DEMO");
        trade.setOptionType("CE");
        trade.setQuantity(1);
        trade.setEntryPremium(new BigDecimal("100.00"));
        trade.setStatus("OPEN");
        trade.setEntryReason("DEMO ONLY: synthetic setup. Not a real market signal.");
        trade.setEntryTime(entryTime);
        trade.setEvidenceJson(writeJson(List.of("Synthetic data only", "No broker order sent", "Demo rules passed")));
        trade.setTimelineJson(writeJson(new ArrayList<>(List.of(entryTime + " — paper entry created; no broker order sent"))));
        return toMap(trades.save(trade));
    }

    @Transactional
    public Map<String, Object> recordBacktestTrade(String symbol, String side, int quantity,
                                                    BigDecimal entryPrice, BigDecimal exitPrice,
                                                    OffsetDateTime entryTime, OffsetDateTime exitTime,
                                                    BigDecimal charges, String entryReason,
                                                    String exitReason, List<String> evidence) {
        if (symbol == null || symbol.isBlank() || symbol.length() > 64) {
            throw new IllegalArgumentException("Backtest symbol must contain 1-64 characters.");
        }
        if (!"LONG".equals(side) && !"SHORT".equals(side)) {
            throw new IllegalArgumentException("Backtest side must be LONG or SHORT.");
        }
        if (quantity < 1 || entryPrice == null || exitPrice == null || entryPrice.signum() <= 0
                || exitPrice.signum() <= 0 || entryTime == null || exitTime == null
                || !exitTime.isAfter(entryTime)) {
            throw new IllegalArgumentException("Backtest fill prices, quantity, and chronological timestamps are invalid.");
        }
        BigDecimal safeCharges = charges == null ? BigDecimal.ZERO : charges.max(BigDecimal.ZERO);
        BigDecimal direction = "LONG".equals(side) ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal gross = exitPrice.subtract(entryPrice)
            .multiply(BigDecimal.valueOf(quantity)).multiply(direction).setScale(2, RoundingMode.HALF_UP);

        OffsetDateTime createdAt = OffsetDateTime.now(zone);
        PaperTradeEntity trade = new PaperTradeEntity();
        trade.setId("BACKTEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        trade.setSymbol(symbol);
        trade.setOptionType(side);
        trade.setQuantity(quantity);
        trade.setEntryPremium(entryPrice.setScale(2, RoundingMode.HALF_UP));
        trade.setExitPremium(exitPrice.setScale(2, RoundingMode.HALF_UP));
        trade.setGrossPnl(gross);
        trade.setCharges(safeCharges.setScale(2, RoundingMode.HALF_UP));
        trade.setNetPnl(gross.subtract(safeCharges).setScale(2, RoundingMode.HALF_UP));
        trade.setStatus("CLOSED");
        trade.setEntryReason(entryReason);
        trade.setExitReason(exitReason);
        trade.setEntryTime(entryTime);
        trade.setExitTime(exitTime);
        List<String> storedEvidence = new ArrayList<>();
        storedEvidence.add("BACKTEST");
        if (evidence != null) storedEvidence.addAll(evidence.stream().filter(value -> value != null && !value.isBlank()).toList());
        trade.setEvidenceJson(writeJson(storedEvidence));
        trade.setTimelineJson(writeJson(List.of(
            entryTime + " — historical paper entry simulated; no Groww order sent",
            exitTime + " — historical paper exit simulated: " + exitReason,
            createdAt + " — backtest result saved to the paper journal")));
        return toMap(trades.save(trade));
    }

    @Transactional
    public Map<String, Object> close(String id) {
        PaperTradeEntity trade = trades.findById(id).orElseThrow(() -> new NoSuchElementException("Trade not found"));
        if (!"OPEN".equals(trade.getStatus())) throw new IllegalStateException("Trade already closed.");
        boolean win = Math.abs(id.hashCode()) % 2 == 0;
        BigDecimal exit = new BigDecimal(win ? "112.00" : "92.00");
        BigDecimal gross = exit.subtract(trade.getEntryPremium()).multiply(BigDecimal.valueOf(trade.getQuantity()));
        BigDecimal fees = new BigDecimal("5.00");
        OffsetDateTime exitTime = OffsetDateTime.now(zone);
        trade.setExitPremium(exit);
        trade.setGrossPnl(gross);
        trade.setCharges(fees);
        trade.setNetPnl(gross.subtract(fees));
        trade.setStatus("CLOSED");
        trade.setExitReason(win ? "DEMO TARGET EXIT (synthetic)" : "DEMO EXIT (synthetic loss)");
        trade.setExitTime(exitTime);
        List<String> timeline = readStringList(trade.getTimelineJson());
        timeline.add(exitTime + " — simulated exit filled at " + exit);
        trade.setTimelineJson(writeJson(timeline));
        return toMap(trades.save(trade));
    }

    @Transactional
    public List<Map<String, Object>> squareOff() {
        List<String> ids = trades.findAll().stream().filter(t -> "OPEN".equals(t.getStatus())).map(PaperTradeEntity::getId).toList();
        return ids.stream().map(this::close).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return trades.findById(id).map(this::toMap).orElseThrow(() -> new NoSuchElementException("Trade not found"));
    }

    private TradingSettingsEntity loadSettings() {
        return settingsRepository.findById(DEFAULT_SETTINGS_ID).orElseGet(() -> {
            TradingSettingsEntity settings = new TradingSettingsEntity();
            settings.setId(DEFAULT_SETTINGS_ID);
            settings.setCapitalLimit(new BigDecimal("10000.00"));
            settings.setRiskPerTradePercent(new BigDecimal("1.00"));
            settings.setDailyLossPercent(new BigDecimal("3.00"));
            settings.setMaxEntryOrdersPerDay(5);
            settings.setStopAfterProfitableTrades(3);
            return settingsRepository.save(settings);
        });
    }

    private List<PaperTradeEntity> todayTrades() {
        LocalDate today = LocalDate.now(zone);
        return trades.findAll().stream()
            .filter(t -> !isBacktest(t))
            .filter(t -> t.getEntryTime().atZoneSameInstant(zone).toLocalDate().equals(today))
            .toList();
    }

    private boolean isBacktest(PaperTradeEntity trade) {
        return readStringList(trade.getEvidenceJson()).contains("BACKTEST");
    }

    private Map<String, Object> settingsMap(TradingSettingsEntity s) {
        return Map.of("capitalLimit", s.getCapitalLimit(), "riskPerTradePercent", s.getRiskPerTradePercent(),
            "dailyLossPercent", s.getDailyLossPercent(), "maxEntryOrdersPerDay", s.getMaxEntryOrdersPerDay(),
            "stopAfterProfitableTrades", s.getStopAfterProfitableTrades(), "entryStart", "09:15",
            "entryCutoff", "14:58", "squareOffStart", "14:57", "timezone", "Asia/Kolkata", "mode", "PAPER");
    }

    private Map<String, Object> toMap(PaperTradeEntity t) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", t.getId());
        result.put("symbol", t.getSymbol());
        result.put("optionType", t.getOptionType());
        result.put("side", t.getOptionType());
        result.put("mode", isBacktest(t) ? "BACKTEST" : "PAPER");
        result.put("quantity", t.getQuantity());
        result.put("entryPremium", t.getEntryPremium());
        result.put("exitPremium", t.getExitPremium());
        result.put("grossPnl", t.getGrossPnl());
        result.put("charges", t.getCharges());
        result.put("netPnl", t.getNetPnl());
        result.put("status", t.getStatus());
        result.put("entryReason", t.getEntryReason());
        result.put("exitReason", t.getExitReason());
        result.put("entryTime", t.getEntryTime());
        result.put("exitTime", t.getExitTime());
        result.put("evidence", readStringList(t.getEvidenceJson()));
        result.put("timeline", readStringList(t.getTimelineJson()));
        return result;
    }

    private List<String> readStringList(String json) {
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<String>>() { }));
        } catch (Exception e) {
            throw new IllegalStateException("Stored paper-trade history is invalid.", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize paper-trade history.", e);
        }
    }

    private BigDecimal decimal(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) throw new IllegalArgumentException("Missing setting: " + key);
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid numeric setting: " + key); }
    }

    private int integer(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) throw new IllegalArgumentException("Missing setting: " + key);
        try { return new BigDecimal(String.valueOf(value)).intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Invalid integer setting: " + key); }
    }
}
