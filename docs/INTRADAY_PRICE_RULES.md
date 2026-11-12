# Intraday price fallback and paper margin

Implemented 3 November 2026. Applies to managed NSE cash-equity paper entries; real execution remains disabled.

## Selection order

1. Check today's company news, or the available previous seven days as before.
2. If a directional news shortlist exists, keep using those stocks. A news candidate failing technical checks does not automatically switch to another universe.
3. With price fallback enabled, a fresh, successful news collection with no positive or negative candidate permits a candle-based scan of the maintained NIFTY 50 constituents priced **strictly below ₹500** (or a lower configured ceiling).
4. Missing collection, a collection older than 180 seconds, zero configured permitted feeds, provider errors, an expired plan or missing universe blocks fallback. An outage is not treated as neutral news.
5. NIFTY trend, sector basket, breadth, completed candles, EMA/MACD/RSI/VWAP, relative volume, turnover and score checks still apply. Bullish evidence may propose BUY/LONG; bearish evidence may propose SELL/SHORT when paper short eligibility is verified. There may still be no trade.
6. Up to three fallback cards show the source as **Price fallback**, not news. The highest qualifying candidate proceeds to final quote checks. Its stored selection ID binds the symbol and direction, and the executable price is checked again against the ceiling by AI and the Java risk engine.

The price ceiling applies only to fallback. Directional news candidates may cost more than ₹500. This does not add all NSE stocks to the maintained universe.

## Editable paper-testing starting values

| Entry price | Minimum stop distance | Minimum target distance |
|---|---:|---:|
| Below ₹100 | 0.8% | 2.4% |
| ₹100 to below ₹250 | 0.6% | 2.0% |
| ₹250 to below ₹500 | 0.5% | 1.8% |
| ₹500 and above | 0.4% | 1.6% |

These are unvalidated paper-testing assumptions, not optimized or recommended return targets. Percentages refer to entry-price movements, not return on margin. Bands are consecutive with exclusive upper bounds and a final open-ended band. They are editable under **Risk & settings → Intraday price and margin rules → Edit paper rules**. Saving requires the existing separate `TRADING_ADMIN_TOKEN`; the shared connection token cannot change risk configuration. No token is generated or security requirement removed by this update.

Stop distance = max(ATR14 × configured ATR stop multiplier, entry price × band stop %).

Target distance = max(entry price × band target %, stop distance × configured target R + estimated costs × (R+1)).

This preserves the volatility floor and cost-adjusted reward/risk rather than forcing a tight stop solely because a stock is cheap. Both BUY and SELL use mirrored geometry. Decisions show the actual resulting percentages; they can exceed the band minimums. Independent Java validation checks the band floors, geometry and net reward/risk again.

## 5× paper intraday sizing

The requested profile uses an editable leverage ceiling of 5× (supported range 1–5×). It is a **paper margin assumption**. It does not verify Groww's live margin or imply that every low-priced stock is eligible for 5× leverage. Actual broker margin varies by security and must be queried/validated before future real execution.

For cash capital C, leverage L, price P and estimated round-trip cost per share F:

- Notional ceiling = min(C, configured cash allocation cap) × L.
- Margin per share = P/L. Costs are reserved separately.
- Cash risk budget = C × risk-per-trade %. It is **not multiplied by L**.
- Quantity is the minimum whole-share quantity permitted by notional ceiling, available cash for margin plus costs, and cash risk divided by stop loss plus costs.
- Java independently clamps leverage to the saved configuration and AI proposal, checks notional and margin, and checks fresh executable depth, spread, daily limits and remaining daily loss budget.

For example, ₹10,000 capital with 5× leverage has a ₹50,000 notional ceiling. If stop risk permits only ₹8,000 of stock, the order remains ₹8,000. Buying power is a ceiling, not an instruction to use it fully. P&L is price difference × actual filled shares minus estimated costs; it is not multiplied again by leverage.

One active order/position remains a hard invariant. Existing entries keep their original stops, targets and stored margin assumption. New settings apply to future entries. Exits at target/stop or 14:59 IST still need valid executable quotes and depth; no exit fill is fabricated.

## API additions

Trader endpoints (existing Bearer authentication):

- `GET /api/v1/risk/status`: adds `intraday` alongside effective `policy`.
- `GET /api/v1/risk/intraday/defaults`: returns the proposed paper-testing profile without saving it.
- `PUT /api/v1/risk/intraday`: saves the complete rules object, with a separate `X-Admin-Token`.

```json
{
  "fallbackEnabled": true,
  "fallbackMaxPrice": 500,
  "paperLeverage": 5,
  "priceBandsEnabled": true,
  "priceBands": [
    {"belowPrice": 100, "stopLossPercent": 0.8, "targetProfitPercent": 2.4},
    {"belowPrice": 250, "stopLossPercent": 0.6, "targetProfitPercent": 2.0},
    {"belowPrice": 500, "stopLossPercent": 0.5, "targetProfitPercent": 1.8},
    {"belowPrice": null, "stopLossPercent": 0.4, "targetProfitPercent": 1.6}
  ]
}
```

AI policy adds the same fields plus optional `maxPositionValue` (a notional ceiling in the AI contract). Trader's existing policy `maxPositionValue` remains the cash allocation cap before leverage. The adapter converts it to a notional ceiling; this distinction is labelled in the UI.

`POST /api/v1/automation/select` adds `selectionMode: "NEWS" | "PRICE_FALLBACK"`. Fallback requires a saved fresh `newsPlanId` with successful collection metadata and no directional candidates. It cannot supply an arbitrary `candidateSymbols` list. Its response adds a labelled `shortlist` and retains rejection reasons and 50-stock market coverage.

`POST /api/v1/decisions/evaluate` adds `selectionMode` and, for fallback, the stored `selectionId`. The final stock, side and plan must match an approved selection no older than five minutes. Decisions expose `paperLeverage`, `marginRequired`, `positionValue`, `paperMarginAssumption`, `cashRiskBudget`, `priceRiskBand`, `stopLossPercent` and `targetProfitPercent`. Legacy requests default to 1×/ATR/no price fallback unless the adapter supplies the new saved rules.

## Deployment and verification

Rebuild/restart both apps to load backend changes. The requested paper-price-v1 profile is the configured startup default when no intraday override exists in RISK state. Edits saved through the UI persist only under RISK.intraday. Existing overrides take precedence, and other hard limits and records are preserved. TRADING_INTRADAY_PROFILE=legacy restores the older defaults when no saved override exists. Old AI API clients retain conservative 1× defaults unless they supply the new policy fields.

Tests cover strict price boundaries, long and short candle selection, provider failures, stale collection, missing/duplicate market evidence, final selection binding, a live quote crossing ₹500, independent Java risk/band/margin enforcement, unchanged existing positions and administrator authorization. UI tests cover editable bands, source labels, sizing details and existing pending-exit markers.

References: [Groww margin calculator](https://groww.in/calculators/margin-calculator), [Groww margin API](https://groww.in/trade-api/docs/curl/margin), [Groww risk policy](https://groww.in/p/risk-policy).
