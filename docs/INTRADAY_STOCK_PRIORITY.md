# Intraday-first automatic stock selection

Intraday is an order product, not a separate share class. The same NSE equity can be traded as delivery or intraday if the broker supports the requested product and direction. Automatic selection now requires a successful, fresh **Groww MIS preflight** before a candidate can reach the final decision. No delivery/CNC fallback is used.

## Flow

1. Keep the existing news research, capital/share-price limits and two history readers: NIFTY 50 context plus a bounded batch from the maintained broader stock registry.
2. For up to three loaded news candidates, check NSE equity instrument permissions for both entry and eventual exit, then request a one-share `MIS` `LIMIT` margin estimate for the intended BUY or SELL direction. No order or funds reservation is created.
3. Send the checked evidence with the full candle selection request. AI retains its market, sector, EMA/MACD/RSI/VWAP, volume, turnover, price, capital and score requirements. Unknown, stale, rejected or unavailable MIS evidence cannot qualify a stock.
4. If a technically qualified candle fallback needs its first MIS check, use any remaining part of the three-probe cycle budget, then send the results back to AI for one further selection pass. A failed news preflight does not keep a suitable broader intraday candidate permanently behind it. Among passing intraday candidates, existing news priority and rule ranking remain.
5. Java independently checks that the selected stock and direction have fresh passing preflight evidence before requesting a final decision. Real order sizing still verifies exact-quantity Groww margin, available funds, costs and all independent risk limits. The one-position invariant and existing stop/target/deadline exits are unchanged.

Checks are cached for at most 60 seconds; temporary provider failures are retried after at most 15 seconds. A direction change or price move over 10% invalidates reuse. There are at most three new preflights and two selection requests per cycle. These extra bounded calls can make a cycle longer than the nominal five-second scheduler interval. No trade is forced when checks are unavailable.

## What the UI shows

The AI Analysis comparison adds **Intraday / MIS**: preflight passed, check pending, intraday rejected or check unavailable. Shortlist cards include the reason and the selected-stock panel shows the check time. Automation reports the intraday-check processing stage; Risk & settings explains the stock priority. Historical comparisons without this evidence remain labelled “Not checked.”

A successful one-share preflight is an indicative suitability check, not a broker guarantee of execution or 5× leverage. Its reference price is the latest completed candle close, not a claimed fresh executable quote. Configured 3×/5× ceilings, affordable quantity, per-trade/daily risk percentages, liquidity and capital-based share-price bands still apply. Final live checks use fresh quotes, exact quantity and broker funds. Paper entries use the same broker-backed suitability preflight but remain simulated.

## Protocol and audit

The automatic Java request always sets `requireIntraday: true` and supplies bounded `intradayChecks` evidence for the stock/direction. The analysis API accepts this typed evidence and never calls an order API. Compatibility/research callers omitting the flag retain their prior analysis contract; this does not bypass Java's automatic-entry check or live execution controls.

Margin calls are read-only broker calculations through the existing Groww client and appear in the new **Groww API audit** report. No credentials, account balances or raw broker errors are copied into candidate-check reasons. The instrument CSV permissions alone are not treated as proof of intraday support, because the published instrument format has no dedicated MIS eligibility column.

Official references: [Groww instruments](https://groww.in/trade-api/docs/curl/instruments) and [Groww margin calculations](https://groww.in/trade-api/docs/curl/margin).

The update does not start automation or switch execution mode. Both apps must use the updated versions on their next start.
