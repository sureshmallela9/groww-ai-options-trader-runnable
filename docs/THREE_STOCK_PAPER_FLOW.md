# Three news candidates → one directional paper position

Trading AI ranks up to three maintained-universe stocks from permitted news: relevant today news first, available previous-seven-day news only when today has none. Positive and negative stories can produce BUY/LONG and SELL/SHORT candidates respectively. The ranking is a keyword/reliability/recency heuristic. If fewer than three stocks have evidence, the list stays smaller; no stock is invented.

The trader sends `newsPlanId` and an explicit `candidateSymbols` array (up to three unique symbols) to `POST /api/v1/automation/select`. The 50 maintained stock histories and NIFTY remain market context for breadth and derived industry confirmation. Only the requested news candidates can qualify. The response identifies their symbols, `candidateCount`, `marketContextCoverage`, and at most one `selected` stock with `side` and `positionSide`. NO_TRADE is a valid completed result.

The selected stock, side, completed candles and fresh quote go to `POST /api/v1/decisions/evaluate`. A SELL entry requires explicit `allowPaperShorts: true`, verified instrument sell eligibility and bearish stock/NIFTY/sector confirmation. BUY entries require bullish confirmation. Both require volume, liquidity, whole-share sizing, freshness and cost-adjusted risk/reward checks. The app rejects a final response that changes the selected stock or side.

The response's `expectedPrices` is a conditional ATR risk plan containing entry, stop and target. `isGuaranteedForecast` is false. There is no trained price-prediction model or probability of profit implied by these prices or rule scores. A BUY enters at the ask and exits at the bid; a simulated short sells at the bid and buys to cover at the ask. Short profit is entry minus cover price, less estimated costs. Paper shorts reserve full notional capital with no leverage; broker margin, borrowing availability and real short-order execution are not modeled.

## Continuous position feedback

One active order/position/exit remains the limit. Once a position opens, keep that stock until its fixed target/stop, daily limits, manual/emergency exit or 14:59 IST deadline. Do not switch stock or widen a stop because of a new AI opinion.

The local risk monitor runs on its existing one-second configured delay. Fresh position snapshots are queued to Trading AI about every five seconds; the outbox checks every three seconds. Failed calls use backoff. At most one pending periodic snapshot per position is retained, while entry and closure events remain separate. A stale quote, unavailable service or insufficient depth can delay evaluation/fills; the app never fabricates a price or confirmed exit.

`POST /api/v1/positions/events` receives existing position fields plus `positionSide` (`LONG`/`SHORT`), `executableExitPrice` and `quoteTime`. The response includes `HOLD`, `WAIT`, `EXIT` or `NO_CHANGE`; an EXIT adds `orderAction` (`SELL` for a long, `BUY` to cover a short), the unchanged price plan, timestamps and reason. Gross P&L in that response excludes costs; the app's portfolio P&L includes configured estimated charges and remains authoritative. Local validation owns fills. AI outages do not disable target/stop or deadline monitoring.

The AI Analysis screen shows the shortlisted candidates, their direction/rejection reasons, conditional entry prices when qualified, and the active position's latest AI response. Positions and graph markers identify short SELL entries and BUY covers correctly. All execution remains simulated paper trading; the real-mode route remains disabled.

## Additive contract example

Selection request adds `"candidateSymbols": ["RELIANCE", "TCS", "INFY"]` and a fresh stored `newsPlanId` to the existing NIFTY/snapshots/policy body. `policy.allowPaperShorts` enables paper-only short consideration. Decision requests add `"side": "SELL"` for a short. Existing BUY callers remain compatible. Only a qualified BUY/SELL ENTRY proposal can enter the independent paper risk engine; a position-event EXIT response cannot open a new position.
