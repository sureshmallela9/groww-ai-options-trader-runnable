package com.optionstrader.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "PAPER_TRADES", schema = "GROWW_TRADER")
public class PaperTradeEntity {
    @Id @Column(name = "TRADE_ID", length = 40, nullable = false) private String id;
    @Column(name = "SYMBOL", length = 64, nullable = false) private String symbol;
    @Column(name = "OPTION_TYPE", length = 8, nullable = false) private String optionType;
    @Column(name = "QUANTITY", nullable = false) private Integer quantity;
    @Column(name = "ENTRY_PREMIUM", precision = 18, scale = 2, nullable = false) private BigDecimal entryPremium;
    @Column(name = "EXIT_PREMIUM", precision = 18, scale = 2) private BigDecimal exitPremium;
    @Column(name = "GROSS_PNL", precision = 18, scale = 2) private BigDecimal grossPnl;
    @Column(name = "CHARGES", precision = 18, scale = 2) private BigDecimal charges;
    @Column(name = "NET_PNL", precision = 18, scale = 2) private BigDecimal netPnl;
    @Column(name = "STATUS", length = 16, nullable = false) private String status;
    @Column(name = "ENTRY_REASON", length = 1000, nullable = false) private String entryReason;
    @Column(name = "EXIT_REASON", length = 1000) private String exitReason;
    @Column(name = "ENTRY_TIME", nullable = false) private OffsetDateTime entryTime;
    @Column(name = "EXIT_TIME") private OffsetDateTime exitTime;
    @Lob @Column(name = "EVIDENCE_JSON", nullable = false, columnDefinition = "CLOB") private String evidenceJson;
    @Lob @Column(name = "TIMELINE_JSON", nullable = false, columnDefinition = "CLOB") private String timelineJson;

    public PaperTradeEntity() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getOptionType() { return optionType; }
    public void setOptionType(String optionType) { this.optionType = optionType; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getEntryPremium() { return entryPremium; }
    public void setEntryPremium(BigDecimal entryPremium) { this.entryPremium = entryPremium; }
    public BigDecimal getExitPremium() { return exitPremium; }
    public void setExitPremium(BigDecimal exitPremium) { this.exitPremium = exitPremium; }
    public BigDecimal getGrossPnl() { return grossPnl; }
    public void setGrossPnl(BigDecimal grossPnl) { this.grossPnl = grossPnl; }
    public BigDecimal getCharges() { return charges; }
    public void setCharges(BigDecimal charges) { this.charges = charges; }
    public BigDecimal getNetPnl() { return netPnl; }
    public void setNetPnl(BigDecimal netPnl) { this.netPnl = netPnl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEntryReason() { return entryReason; }
    public void setEntryReason(String entryReason) { this.entryReason = entryReason; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
    public OffsetDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(OffsetDateTime entryTime) { this.entryTime = entryTime; }
    public OffsetDateTime getExitTime() { return exitTime; }
    public void setExitTime(OffsetDateTime exitTime) { this.exitTime = exitTime; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getTimelineJson() { return timelineJson; }
    public void setTimelineJson(String timelineJson) { this.timelineJson = timelineJson; }
}