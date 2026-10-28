# Review of the two-application master prompt

The supplied prompt was compared with both repositories on 28 October 2026. The uploaded text ends with "Store" in its database section; no continuation was supplied.

The initial implementation is an explainable NSE cash-equity PAPER workflow. It preserves the existing options dashboard, read-only Groww integration, document library, analysis reports and historical paper backtest. It does not claim that the platform is ready for real-money automation.

## Findings and implemented changes

| Area | Existing implementation | Added behavior |
|---|---|---|
| Application contract | Java already posted to Python, but its provider envelope was rejected | Optional market_data accepted; raw source stored in report detail; compact summaries in responses; compatibility health check |
| Streaming and reports | Browser split literal escape strings; saved responses and excerpts were not read correctly | Chunk-aware SSE parser; saved report response unwrap; source excerpt and provider display |
| Technical analysis | SMA20/50, returns, simple RSI/ATR, relative volume | EMA9/20/50, MACD/signal/histogram, per-session VWAP, prior-bar support/resistance, verified first-15-minute opening range, body/wicks, turnover, opening gap and prior-session metrics |
| News intelligence | Trading-document retrieval only | Bounded permitted JSON-feed collection and manual ingestion; headline deduplication, maintained-universe company mapping, timestamps, freshness and uncalibrated lexicon sentiment |
| Universe and selection | User supplied one symbol | Explicit official NIFTY 50 constituent refresh, snapshot provenance, configurable ranking weights, bounded asynchronous scans, up to five shortlisted candidates and at most one provisional selection |
| Market regime | Per-symbol trend label | NIFTY/sector/breadth checks, high-volatility and scheduled-event gates, explicit UNCERTAIN and no-trade results |
| Decisions | Trend report | Structured versioned entry proposals, exact rule/strategy version, expiry, capital and cost-adjusted sizing, risk/reward, evidence, news IDs and knowledge citations |
| Inter-app API | Local unversioned REST | Additive bearer-protected /api/v1 APIs, correlation headers, idempotency keys, durable decision/event deduplication, scan jobs and outbox acknowledgments |
| Market gateway | Groww-specific request forms | Normalized quote/candle APIs; symbols resolved from instrument CSV; only completed candles; actual broker trade timestamp used for freshness; unavailable adjustments remain UNKNOWN |
| Risk and execution | Synthetic demo entries/exits plus a small historical replay | Independent long-only equity paper intent engine, serialized database risk checks, persistent lifecycle, fresh ask/bid fills, duplicate prevention, limits on capital/risk/open positions/daily entries/consecutive losses/spread/slippage/signal age |
| Emergency control | Manual demo square-off | Durable kill switch, administrator-only reset/policy changes, one-second local monitoring, stop/target/daily-loss/session exits, visible unresolved exits and data incidents |
| Event processing | In-memory request/response flow | Transactional Java outbox/inbox and audit records; retryable versioned position events to Python; AI exit proposals require local validation |
| Persistence | PostgreSQL/SQLite reports in Python; Oracle in Java | PostgreSQL Java profile and vendor-specific Flyway scripts; legacy Oracle profile retained; separate table ownership and temporary test databases |
| UI | Historical analysis and options demo | Separate paper research workspace with token held in page memory, maintained symbol list, market assessment, scan/evaluate, confirmed paper intent, risk status, kill switch, lifecycle and news evidence |

## Conditions that remain explicit

1. No licensed news provider was supplied. Collection returns NO_CONFIGURED_DATA until an operator configures a permitted feed. Manual ingestion works. Keyword sentiment is not a calibrated prediction and does not verify factual truth.
2. No trained, out-of-sample validated forecasting model was supplied. The existing scikit-learn baseline remains a separate research command. PredictionProvider is an extension boundary; the active provider reports unavailable. There is no fabricated probability of profit.
3. Actual exchange sessions must be configured from an authoritative calendar. Missing dates block entries. Weekdays alone are not treated as verified exchange sessions.
4. Current NIFTY membership is fetched explicitly from NSE Indices. It is not a historical membership dataset and cannot support unbiased constituent backtests by itself.
5. Broad-market breadth and sector inputs are required for new proposals. The initial UI accepts those observations explicitly; it does not invent breadth or sector data when unavailable.
6. Corporate action adjustments are marked UNKNOWN. A reviewed adjusted historical dataset is needed for research spanning corporate actions.
7. Fees are a configurable conservative paper estimate, not a statutory brokerage/tax calculator. Paper fills do not model depth, queue priority, partial fills or market impact.
8. There is no enabled live order adapter. BrokerOrderPort states that limitation. Real order placement, protective-order verification, partial-fill reconciliation, recovery from uncertain broker status and live rollout require a subsequent reviewed implementation and account-level validation. No new code sends a Groww order.
9. Automatic entry is off. The initial workspace requires explicit confirmation of a paper intent. It does not operate an unattended scanning-to-entry scheduler.
10. Position management is long-only HOLD/WAIT/EXIT/NO_CHANGE. REDUCE and protective stop-update execution are future extensions; unsupported actions cannot create trades.
11. Historical source usefulness, calibrated ML, strategy ensembles, options, portfolio optimization and a full historical exchange calendar are future research/data work. The code does not claim these are completed.
12. The existing historical backtest remains a deliberately small one-trade experiment; this update does not establish profitable performance or a statistically validated strategy.
13. Candidate work is bounded and process-local. Its job records persist, but a scan interrupted by a process restart must be resubmitted. It cannot create an automatic entry.

## Data ownership and recovery

Trading AI owns reports plus intelligence_records, intelligence_requests and intelligence_outbox in its configured database. Record kinds separate news, universe snapshots, market assessments, scan jobs, decisions and position assessments. Request scope/key uniqueness prevents conflicting replays. Its outbox stores event version, ID, aggregate, payload and delivery state.

The Java app owns PAPER_INTENTS, PLATFORM_STATE, PLATFORM_OUTBOX, PLATFORM_INBOX and PLATFORM_AUDIT in its own schema, alongside its legacy tables. Unique decision IDs and request keys protect intents. Foreign keys link events to intents. The RISK state row is locked for every financial state change, serializing concurrent checks across app instances. State, fill, audit and queued event are committed in one transaction.

All authenticated Groww reads in this Java process share rolling second/minute budgets, with headroom reserved for risk quotes. Exhausted local budgets return 429/Retry-After without sending another broker request. Risk monitoring has scheduler capacity separate from AI event delivery. Multiple Java processes still require a shared account-level rate coordinator before horizontal scaling.

Timestamps are UTC ISO 8601 for storage and Asia/Kolkata for session decisions. No tables or credentials are shared directly between apps. Restarted monitors resume persistent open/pending positions and outbox retries. An unresolved data outage leaves a visible incident rather than an invented fill.

The migration files are additive; no existing database was migrated during development. PostgreSQL profile selection is explicit. Do not run Oracle and PostgreSQL migrations against the same schema.

Retention is not automatic: preserve intent/audit/inbox/outbox history until an operator has archived it under the application's retention policy. Pending exits and undelivered events must never be deleted by cleanup. Establish a backup and retention policy before unattended operation.

## References used

The local curated market_microstructure, crump_speculation_risk and reading-map notes informed explicit cost/spread gates and evidence limitations; no source documents were modified.

Official broker fields/routes were checked against [Groww live data](https://groww.in/trade-api/docs/curl/live-data), [historical candles](https://groww.in/trade-api/docs/curl/backtesting), [instruments](https://groww.in/trade-api/docs/curl/instruments), and [available margin](https://groww.in/trade-api/docs/curl/margin). The universe refresh uses the constituent link on the [official NIFTY 50 page](https://niftyindices.com/indices/equity/broad-based-indices/nifty--50). Proposed internal /api/v1 routes are application endpoints, not new Groww endpoints.
