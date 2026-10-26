package com.optionstrader.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "TRADING_SETTINGS", schema = "GROWW_TRADER")
public class TradingSettingsEntity {
    @Id @Column(name = "SETTING_ID", length = 20, nullable = false) private String id;
    @Column(name = "CAPITAL_LIMIT", precision = 18, scale = 2, nullable = false) private BigDecimal capitalLimit;
    @Column(name = "RISK_PER_TRADE_PCT", precision = 5, scale = 2, nullable = false) private BigDecimal riskPerTradePercent;
    @Column(name = "DAILY_LOSS_PCT", precision = 5, scale = 2, nullable = false) private BigDecimal dailyLossPercent;
    @Column(name = "MAX_ENTRY_ORDERS", nullable = false) private Integer maxEntryOrdersPerDay;
    @Column(name = "STOP_PROFITABLE_TRADES", nullable = false) private Integer stopAfterProfitableTrades;

    public TradingSettingsEntity() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public BigDecimal getCapitalLimit() { return capitalLimit; }
    public void setCapitalLimit(BigDecimal value) { this.capitalLimit = value; }
    public BigDecimal getRiskPerTradePercent() { return riskPerTradePercent; }
    public void setRiskPerTradePercent(BigDecimal value) { this.riskPerTradePercent = value; }
    public BigDecimal getDailyLossPercent() { return dailyLossPercent; }
    public void setDailyLossPercent(BigDecimal value) { this.dailyLossPercent = value; }
    public Integer getMaxEntryOrdersPerDay() { return maxEntryOrdersPerDay; }
    public void setMaxEntryOrdersPerDay(Integer value) { this.maxEntryOrdersPerDay = value; }
    public Integer getStopAfterProfitableTrades() { return stopAfterProfitableTrades; }
    public void setStopAfterProfitableTrades(Integer value) { this.stopAfterProfitableTrades = value; }
}