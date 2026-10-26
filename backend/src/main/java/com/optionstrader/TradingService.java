package com.optionstrader;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TradingService {
  private BigDecimal capital = new BigDecimal("10000");
  private BigDecimal riskPct = new BigDecimal("1.0");
  private BigDecimal lossPct = new BigDecimal("3.0");
  private int maxEntries = 5, stopWins = 3;
  private final List<Map<String,Object>> trades = new CopyOnWriteArrayList<>();
  private final ZoneId zone = ZoneId.of("Asia/Kolkata");

  public synchronized Map<String,Object> settings() {
    return Map.of("capitalLimit",capital,"riskPerTradePercent",riskPct,"dailyLossPercent",lossPct,
      "maxEntryOrdersPerDay",maxEntries,"stopAfterProfitableTrades",stopWins,
      "entryStart","09:15","entryCutoff","14:58","squareOffStart","14:57","timezone","Asia/Kolkata","mode","PAPER");
  }
  public synchronized Map<String,Object> update(Map<String,Object> x) {
    BigDecimal c = new BigDecimal(String.valueOf(x.get("capitalLimit")));
    BigDecimal r = new BigDecimal(String.valueOf(x.get("riskPerTradePercent")));
    BigDecimal l = new BigDecimal(String.valueOf(x.get("dailyLossPercent")));
    int m = Integer.parseInt(String.valueOf(x.get("maxEntryOrdersPerDay")));
    int w = Integer.parseInt(String.valueOf(x.get("stopAfterProfitableTrades")));
    if(c.signum()<=0 || r.signum()<=0 || r.compareTo(new BigDecimal("5"))>0 || l.signum()<=0 || l.compareTo(new BigDecimal("10"))>0 || m<1 || m>30 || w<1 || w>20)
      throw new IllegalArgumentException("Invalid settings. Capital must be positive; risk <=5%; daily loss <=10%; entries 1–30; profitable stop 1–20.");
    capital=c; riskPct=r; lossPct=l; maxEntries=m; stopWins=w; return settings();
  }
  public synchronized Map<String,Object> dashboard() {
    List<Map<String,Object>> today = todayTrades();
    long wins=today.stream().filter(t->"CLOSED".equals(t.get("status")) && ((BigDecimal)t.get("netPnl")).signum()>0).count();
    BigDecimal pnl=today.stream().map(t->(BigDecimal)t.get("netPnl")).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
    long open=today.stream().filter(t->"OPEN".equals(t.get("status"))).count();
    LocalTime now=LocalTime.now(zone);
    return Map.of("mode","PAPER","capitalLimit",capital,"riskPerTradePercent",riskPct,"dailyLossPercent",lossPct,
      "dailyLossLimit",capital.multiply(lossPct).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP),
      "maxEntryOrdersPerDay",maxEntries,"entryOrdersUsed",today.size(),"stopAfterProfitableTrades",stopWins,"profitableTrades",wins,
      "netPnl",pnl,"openPositions",open,"sessionOpen",!now.isBefore(LocalTime.of(9,15))&&now.isBefore(LocalTime.of(14,58)),
      "currentTime",OffsetDateTime.now(zone).toString(),"entryStart","09:15","entryCutoff","14:58","squareOffStart","14:57","trades",all());
  }
  public List<Map<String,Object>> all(){return trades.stream().sorted((a,b)->((String)b.get("entryTime")).compareTo((String)a.get("entryTime"))).toList();}
  public synchronized Map<String,Object> execute(){
    ZonedDateTime z=ZonedDateTime.now(zone);
    if(z.getDayOfWeek()==DayOfWeek.SATURDAY||z.getDayOfWeek()==DayOfWeek.SUNDAY) throw new IllegalStateException("Weekend guard: no entries. Official exchange calendar is not configured.");
    if(z.toLocalTime().isBefore(LocalTime.of(9,15))||!z.toLocalTime().isBefore(LocalTime.of(14,58))) throw new IllegalStateException("Outside entry window 09:15–14:58 IST.");
    List<Map<String,Object>> today=todayTrades();
    long wins=today.stream().filter(t->"CLOSED".equals(t.get("status"))&&((BigDecimal)t.get("netPnl")).signum()>0).count();
    BigDecimal pnl=today.stream().map(t->(BigDecimal)t.get("netPnl")).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
    BigDecimal limit=capital.multiply(lossPct).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);
    if(today.size()>=maxEntries) throw new IllegalStateException("Daily entry limit reached.");
    if(wins>=stopWins) throw new IllegalStateException("Profitable-trade stop reached.");
    if(pnl.compareTo(limit.negate())<=0) throw new IllegalStateException("Daily loss limit reached.");
    String id="PAPER-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
    OffsetDateTime now=OffsetDateTime.now(zone);
    Map<String,Object> t=new LinkedHashMap<>();
    t.put("id",id);t.put("symbol","NIFTY-DEMO");t.put("optionType","CE");t.put("quantity",1);
    t.put("entryPremium",new BigDecimal("100.00"));t.put("exitPremium",null);t.put("grossPnl",null);t.put("charges",null);t.put("netPnl",null);
    t.put("status","OPEN");t.put("entryReason","DEMO ONLY: synthetic setup. Not a real market signal.");
    t.put("exitReason",null);t.put("entryTime",now.toString());t.put("exitTime",null);
    t.put("evidence",List.of("Synthetic data only","No live market feed","Demo rules passed"));
    t.put("timeline",new ArrayList<>(List.of(now+" — paper entry created; no broker order sent")));
    trades.add(t);return t;
  }
  public synchronized Map<String,Object> close(String id){
    Map<String,Object> t=trades.stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow(()->new NoSuchElementException("Trade not found"));
    if(!"OPEN".equals(t.get("status")))throw new IllegalStateException("Trade already closed.");
    boolean win=Math.abs(id.hashCode())%2==0;
    BigDecimal entry=(BigDecimal)t.get("entryPremium"), exit=new BigDecimal(win?"112.00":"92.00");
    BigDecimal gross=exit.subtract(entry).multiply(BigDecimal.valueOf((Integer)t.get("quantity")));
    BigDecimal fees=new BigDecimal("5.00"), net=gross.subtract(fees);
    OffsetDateTime now=OffsetDateTime.now(zone);
    t.put("exitPremium",exit);t.put("grossPnl",gross);t.put("charges",fees);t.put("netPnl",net);t.put("status","CLOSED");
    t.put("exitReason",win?"DEMO TARGET EXIT (synthetic)":"DEMO EXIT (synthetic loss)");t.put("exitTime",now.toString());
    ((List<String>)t.get("timeline")).add(now+" — simulated exit filled at "+exit);
    return t;
  }
  public synchronized List<Map<String,Object>> squareOff(){
    List<String> ids=trades.stream().filter(t->"OPEN".equals(t.get("status"))).map(t->(String)t.get("id")).toList();
    return ids.stream().map(this::close).toList();
  }
  public Map<String,Object> get(String id){return trades.stream().filter(t->id.equals(t.get("id"))).findFirst().orElseThrow(()->new NoSuchElementException("Trade not found"));}
  private List<Map<String,Object>> todayTrades(){
    LocalDate today=LocalDate.now(zone);
    return trades.stream().filter(t->LocalDate.parse(((String)t.get("entryTime")).substring(0,10)).equals(today)).toList();
  }
}
