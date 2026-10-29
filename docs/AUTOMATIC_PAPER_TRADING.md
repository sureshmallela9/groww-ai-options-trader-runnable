# Automatic paper trading and chart markers

## Start and stop

Restart both apps, refresh `http://localhost:1111/` with Ctrl+F5, and use **Automatic paper trading**. Connect with the same INTERNAL_API_TOKEN used by both apps. Select Paper simulation, confirm automatic simulated entries, and choose **Start / apply paper auto mode**. The detailed paper workspace has the same panel and shares its connection token with it.

The process runs on the Java server until **Stop new entries** is selected, even if the browser is closed. A Java restart pauses automatic entries; the existing independent position risk monitor still manages open paper positions after restart. **Stop and exit paper positions** stops entries first, then requests fresh-quote simulated exits. Missing quotes or depth leave EXIT_PENDING, with no invented sell fill.

The scheduled-event checkbox is operator-provided risk context, not an automatic news-calendar feed. Once connected, changing it saves the flag immediately without starting a stopped run. It blocks data selection and is checked again immediately before an automatic intent. Use the kill switch for an immediate persistent entry block and exit request.

## Data and selection

The server checks every five seconds when idle. A single dedicated worker performs collection and selection; scans cannot overlap. Broker delays and limits can make a scan take longer. Histories are cached by completed five-minute interval, and one final attempt per interval is recorded durably. Unchanged data does not repeatedly place orders. An ambiguous final decision timeout is not retried within that candle.

The workflow loads the official maintained NIFTY 50 universe, 100 completed five-minute candles for NIFTY, and 100 for every constituent. Missing, invalid, incomplete or stale coverage blocks selection. Prior-session closes and current-session completed closes derive the advancing/declining counts. This is NIFTY 50 breadth, not all-exchange breadth or live tick breadth.

Industry confirmation uses a labelled **derived equal-weight research basket** of at least two maintained constituents with the same industry. Prices are normalized to 100 at the first shared close; each timestamp uses the arithmetic mean of normalized constituent OHLC values. Only shared observed timestamps are used. The volume field sums source share volumes; it is not comparable to turnover or an exchange-index volume. Averaged high/low values are bounds from component candles, not an observed intrabar high/low of a traded instrument. Selection uses the derived closing-price trend; the NIFTY volatility check uses actual NIFTY candles. This basket is not an official sector index and is not tradable. Single-member, unclassified, stale or insufficiently overlapping groups cannot provide sector confirmation.

Python ranks eligible stocks by technical alignment and relative volume, with deterministic symbol ordering for ties, and returns at most one provisional candidate. The final decision obtains a fresh quote and runs the existing strategy checks. Java reloads the saved decision, obtains another current quote, and independently validates eligibility, timestamps, spread, executable depth, slippage, cost-adjusted stop/target geometry, capital, risk, daily limits, session calendar and kill switch. A result may remain NO_TRADE. There is no fallback that forces an order when the best candidate fails final checks.

Ordinary informational analysis still accepts one completed candle. Automatic paper entries retain the strategy's 100-bar warm-up, using earlier sessions where available. This initial automatic strategy supports long-only NSE cash equities, not option contracts or short selling. Scores are rule-based and uncalibrated; no profitability claim is made.

## Configured rules

Both the dashboard settings and the independent paper risk policy apply. Effective capital, risk-per-trade, daily-loss percentage and entry-count limits use the stricter value. The dashboard profitable-trade count is also enforced. The independent policy's position, spread, depth, freshness, consecutive-loss and session restrictions remain active. Administrator authentication remains required to update that independent policy or reset the kill switch.

Auto mode admits only one open or unresolved paper position at a time, even if manual policy permits more. Stop/new settings/kill checks are enforced on the server. A database control-row lock linearizes Stop with entry; the paper risk row serializes fills. Automatic intents use a durable idempotency key for the run and candle. Rejected and completed attempts retain decision evidence and audit events.

## Positions, chart and P&L

Green BUY dots show filled paper entry prices; red SELL dots show reconciled simulated exits. Pending or rejected operations do not generate sell dots. Text labels supplement the colours. Chart markers share the selected stock's price/time axes; they are not overlaid on the unrelated NIFTY overview chart. Older fills outside the last 100-candle window remain in position details.

The sticky P&L bar and position table show today's realized net P&L, open mark-to-market P&L and their sum. Open positions are valued at available fresh executable bid quotes; fills use ask for entry and bid for exit. P&L includes configured estimated costs, not broker-confirmed taxes or charges. Quote incidents/staleness are labelled; a UI refresh timestamp does not establish quote freshness. Legacy demo/historical-backtest records remain separately labelled and are not mixed into the automatic paper totals.

## APIs

All routes below require `Authorization: Bearer <INTERNAL_API_TOKEN>`.

| Method | Route | Contract |
|---|---|---|
| POST | Python `/api/v1/automation/select` | `niftyBars`, 50 unique `snapshots` with symbol/bars, optional policy and scheduledEventRisk. Returns selected candidate or NO_TRADE, breadth, candidates, evidence and executionAllowed=false. |
| GET | Java `/api/v1/auto/status` | `automation`, `portfolio`, `risk`. |
| POST | Java `/api/v1/auto/start` | `{ "mode":"PAPER", "confirmed":true, "scheduledEventRisk":false }`. Starting an already-owned run applies the event flag without clearing its attempt ledger. |
| POST | Java `/api/v1/auto/stop` | Stops future entries; continues independent risk monitoring. |
| POST | Java `/api/v1/auto/event-risk` | `{ "enabled":true }` updates the event-risk gate without starting a run. |
| GET | Java `/api/v1/auto/chart?symbol=...` | Sourced candles, filled BUY/SELL markers and outside-window count. |

Real-mode requests return HTTP 409. The live broker-order adapter, broker-side order reconciliation and live activation are still unimplemented; choosing an execution mode must not silently turn a paper fill into a live order. The current order engine is paper only. The existing BrokerOrderPort is an extension boundary, not a live adapter.

Groww's [official quote documentation](https://groww.in/trade-api/docs/curl/live-data) defines the bid/ask, depth, volume and epoch-millisecond trade timestamp used for revalidation. Provider account entitlements, API credentials and the locally verified exchange calendar must be configured for an actual-data paper run. No broker order endpoint is invoked by this feature.

## Verification

Run Python unit tests, the isolated Java/Python integration verifier, and Node browser regressions. They use synthetic candles, mocked Groww responses and temporary databases. They verify selection, rule rejection, Stop/event-risk races, duplicate prevention, P&L accounting, marker semantics and live-mode rejection; they do not establish market profitability. The operator's running applications are not restarted by these tests.
