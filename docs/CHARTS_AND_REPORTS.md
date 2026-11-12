# Market charts and profit/loss reports

## Market charts

Overview and Automation use the same candlestick renderer. The Overview chart occupies a full row. Prices appear on the right; volume bars and IST times appear below. Hover a candle for its open, high, low, close and volume. The price tag on the dotted line is the last displayed candle's close, not a simulated execution or a prediction.

The initial view is today's IST session. Use the trading-date picker, Previous day, Next day or Today to review available history. A day without candles remains empty. Zoom, horizontal dragging, Older/Newer and keyboard arrows change only the display window. Display timeframes are 5m, 15m, 30m, 1h and 1d; higher frames aggregate the five-minute source without combining trading days. Dashed candle borders identify incomplete groups. These controls do not reduce the candle history supplied to AI.

Completed BUY fills have blue upward triangles and SELL fills have purple downward triangles. Amber diamonds identify pending exits without a completed fill. An exit request is never presented as a completed sale. Fill colors differ from green/red candle direction.

### Buy / sell balance

This is an outstanding-order quantity ratio, not executed buying/selling volume, a count of investors, or the probability of a price move.

When Groww supplies both `total_buy_quantity` and `total_sell_quantity`:

```
buyPercent = 100 * total_buy_quantity / (total_buy_quantity + total_sell_quantity)
sellPercent = 100 - buyPercent
```

Percentages are rounded to two decimals. If full-market totals are missing, both supplied sides of visible depth may be summed; that result is explicitly labelled **visible depth only**. Missing or zero combined quantities do not become a fabricated 50/50 ratio. The details disclosure shows up to five supplied bid/ask levels.

Snapshots include receipt time, last trade time when available, basis and session/staleness information. A closed/unverified session or a receipt older than 15 seconds is marked stale. The order book is the latest received snapshot even when reviewing an earlier chart day; it is not a historical order book.

The visible Overview chart refreshes with the existing five-second status cycle. Its chart endpoint reuses quote snapshots younger than four seconds and may request an updated Groww quote otherwise, subject to existing rate limits. Automation's shortlist endpoint only reads the quote cache; loading that screen does not itself initiate additional broker requests. Network delays and rate limits can extend actual intervals. Chart changes do not modify trading rules or place orders.

Market depth is included as `marketDepth` in authenticated `GET /api/v1/auto/chart?symbol=...` and each stock in `GET /api/v1/auto/shortlist`. Field `basis` is `TOTAL_ORDER_QUANTITY` or `VISIBLE_DEPTH`; consumers must check `available`, `stale` and timestamps before presenting it.

Groww field reference: https://groww.in/trade-api/docs/curl/live-data

## Date-range profit and loss

Open **Reports → Profit & loss · date range**. Presets include today, last 10/30 days, last 10 months, last year and this year. Custom dates and daily/monthly/yearly grouping are available. Day ranges are inclusive and use IST. Month/year presets are rolling calendar ranges, with partial first and last buckets explicitly dated.

Profit, loss, net realized P&L and recorded charges come from saved managed trades. PAPER and REAL have separate ledgers. The report's ledger selector does not change execution mode or start trading. Earlier broker-account history is not automatically imported.

- Paper results are recognized on the recorded completed exit date.
- Real partial exits use `realizedByDay` for their actual realization dates. A final total is not counted a second time.
- Pending exits and rejected/unfilled orders do not become realized results.
- Missing results or charges are labelled incomplete; known subtotals do not imply missing values were zero.
- Current fresh open P&L is shown separately for ranges ending today. Historical unrealized P&L is not reconstructed.
- Recorded estimated charges remain identified as estimates.
- Empty periods mean no recorded managed exits, not proof of no broker-account activity.

Use **Matching trades** to review the included entries and exits, and **Download summary CSV** for grouped results. Load or Refresh reports obtains a snapshot; it does not poll the entire ledger every five seconds.

### API

```
GET /api/v1/reports/profit-loss?from=2026-10-28&to=2026-11-06&groupBy=DAY&mode=PAPER
Authorization: Bearer <existing internal token>
```

`from` and `to` are required `YYYY-MM-DD` dates. `groupBy` defaults to `DAY`; supported values are `DAY`, `MONTH`, `YEAR`. `mode` accepts `PAPER` or `REAL`, defaulting to the currently selected execution mode. Future, reversed, invalid and excessively large ranges are rejected; long ranges should use monthly/yearly grouping.

Response fields include `mode`, `from`, `to`, `groupBy`, `timezone`, `asOf`, `scope`, `complete`, `totals`, `recordCoverage`, `groups`, `trades` and `currentOpen`. Each grouped total includes `profit`, `loss`, `net`, `gross`, `charges`, `realizedEvents`, `realizedTrades`, missing-value counts and `estimatedCharges`. A trade can contribute to more than one period through partial exits, so summing per-period distinct-trade counts may exceed the whole-range distinct count.

No new database migration, quota change or Trading AI configuration is required. Start the updated apps using your usual method, then refresh the browser to load the versioned assets.
