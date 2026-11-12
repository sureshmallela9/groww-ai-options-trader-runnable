# Analysis context and historical replay

This phase adds data-quality findings, derived higher-timeframe context, descriptive market conditions, confirmation/invalidation points and a separate historical research replay. It does not train or activate a prediction model, implement options pricing/selection, or change independent order-approval rules.

## Analysis API

`POST /analyze` retains its existing request and response fields. An optional `analysis_options` object controls the new context:

```json
{
  "mode": "historical",
  "session_mode": "nse_regular",
  "context_intervals": ["15m", "1h"],
  "as_of": "2026-01-20T10:00:00+00:00"
}
```

Place this object under `analysis_options` alongside `symbol`, `interval`, `bars` and optional `analysis_prompt`. Omit `as_of` when not needed. A historical cutoff must include a timezone. In `live` mode the API uses its server clock and rejects a supplied `as_of`. Optional `max_candle_age_seconds` overrides the default timestamp-age threshold. `context_intervals` accepts up to three normalized seconds/minutes/hours/days intervals; unsupported derivations are reported as unavailable, not invented.

The default mode is historical. Without an explicit cutoff, supplied bars are treated as caller-declared completed history; this is not independent verification of completion or live freshness. With a cutoff or live mode, bars that have not closed at that time are excluded before indicator calculation. No completed bars yields HTTP 422. Existing validation rejects duplicate timestamps, non-finite values and inconsistent OHLC geometry.

The response adds `analysis_context` with methodology version `research-context-1.0`:

| Field | Meaning |
| --- | --- |
| `data_quality` | Mode, cutoff, bars used, excluded incomplete bars, missing slots, irregular steps, timezone assumptions, age and reasons |
| `timeframes` | Primary and derived intervals, completed bar count, bias, readiness and last completion time |
| `timeframe_alignment` | `aligned`, `conflicting` or `unconfirmed`; partial frames do not count as confirmed agreement |
| `regime` | Descriptive directional, range-bound, transition, high-volatility or insufficient-history condition |
| `decision` | Research stance `WATCH`/`WAIT`/`AVOID`, bias, strength, risk, horizon, prior support/resistance and confirmation/invalidation conditions |
| `limitations` | Interpretation and grouping boundaries |

`execution_allowed` and `probability_available` remain false. A research stance is separate from the versioned automatic strategy's BUY/NO_TRADE proposal and Java's independent risk approval. New context is also included in automatic stock-analysis evidence without changing its entry gates.

## Timeframes and data quality

The primary analysis uses all eligible supplied bars. Higher intervals are derived only when the target is an exact multiple of the source interval. A bucket needs every aligned source candle. Missing bars, partial opening buckets and unfinished closing buckets are not padded.

`nse_regular` anchors intraday grouping at 09:15 Asia/Kolkata and ends it at 15:30, without bridging dates. Thus a five-minute source creates six full hourly candles per regular day; the final fifteen minutes do not become a fabricated seventh hour. Daily context is not synthesized from a 375-minute cash session. It requires separately supplied daily data, which this phase does not accept as an extra context series. `continuous` groups by elapsed UTC boundaries for continuous series. Regular-session grouping is not an exchange-calendar check: holidays and special sessions remain the trading application's responsibility.

Within-session gaps are counted; normal overnight/weekend closures are not called missing bars. Timezone-free bar timestamps retain the existing UTC convention and are explicitly flagged. All-zero volume, insufficient history and off-session input reduce interpretability. No data-quality label certifies provider authenticity.

Trend efficiency is the absolute net twenty-bar move divided by total absolute movement over those twenty bars. Below 0.25 is labelled range-bound; directional strength uses fixed descriptive thresholds, and ATR above 3% flags high volatility. These are initial research heuristics, not statistically established edges. Extreme RSI or light participation raises the risk label. Existing SMA, simple-mean RSI, ATR and EMA conventions are retained.

## Replay API

`POST /replay` accepts the analysis request with at least 51 and at most 5,000 completed bars, historical mode, and optional `replay_options`:

```json
{
  "initial_capital": 10000,
  "warmup_bars": 50,
  "holding_bars": 5,
  "risk_per_trade_percent": 1,
  "max_position_percent": 100,
  "fee_bps": 5,
  "slippage_bps": 5,
  "stop_atr": 1.5,
  "target_r": 2
}
```

The fixed `LONG_ONLY_SMA_MOMENTUM_RESEARCH_V1` baseline requires SMA20 above SMA50 and a positive twenty-bar return from the preceding completed history. It enters at the next candle open plus assumed slippage, sizes whole shares within capital/risk limits, and models one position at a time. Fees are charged per side. Stops/targets use the signal's ATR. Gap exits use the next available open, not an invented stop-price fill. If the same OHLC candle reaches stop and target, stop wins and the ambiguity is counted. Maximum holding candles, session end and end of supplied data can close a simulated trade.

This is a cash-equity price-series research model. Explicit FNO provenance and fetched FNO replay are rejected because options/futures lot sizing is not implemented. Instrument tradability, corporate actions, actual depth/spread, taxes and broker fills are not independently verified or modeled. This baseline is separate from both the automatic EMA strategy and the older one-trade backtest.

The result includes a replay report ID, parameters, trade records with signal-time indicators, equity by completed candle, net return after estimated costs, maximum drawdown, average result, average R, win rate and ambiguity counts. With no trades, win rate/expectancy are null. Profit factor is null when no losses are observed. No optimization, out-of-sample profitability or execution quality is claimed. A synthetic upward-trending series can still lose after costs.

Replay uses the bounded analysis worker pool, with at most one replay in flight so simultaneous replays cannot occupy every default analysis worker. HTTP 503 with Retry-After signals capacity exhaustion. A disconnected or timed-out caller does not imply server cancellation; check saved reports before retrying.

## Reports and UI

Replay records use report kind `replay`. `GET /reports?kind=replay` lists compact results, excluding trades, the equity curve and input snapshot. `GET /reports/{id}` retains those details, including the exact request used for reproduction. Existing analysis/chat reports and storage schemas continue to work; no destructive migration is needed.

Trading AI UI: Market analysis includes context settings and a clearly labelled synthetic example. Historical replay has its own screen, configurable assumptions, equity chart and results. Reports can filter/reopen replay records.

Groww trader UI: AI Analysis displays context for automatic decisions and manual analysis. Its Historical research replay section accepts pasted requests or Groww history using the selected CASH instrument/date range. Reports offers Market analysis and Historical replay filters. Browser rendering escapes source strings and keeps research results separate from portfolio P&L.

Java routes:

| Route | Purpose |
| --- | --- |
| `POST /api/analyze` or `/api/analyze/stream` | Forward supplied analysis and optional context settings |
| `POST /api/backtesting/analyze` | Fetch history, then forward research settings without adding them to the broker request |
| `POST /api/ai/replay` | Replay supplied historical bars through Python |
| `POST /api/backtesting/replay` | Fetch CASH historical bars and run research replay; never call paper execution |
| `GET /api/ai/reports?kind=replay` | List replay reports |
| `GET /api/ai/reports/{id}` | Read analysis or replay detail |

Health remains API version 0.5.0 for compatibility and adds `analysis_context_v2` and `historical_replay` capability flags. The Java status response exposes `analysisContextSupported` and `historicalReplaySupported`. Restart both services after updating; historical files and synthetic examples can be tested without a working Groww connection.

## Verification

Python tests cover cutoff/future-data leakage, missing slots, session boundaries, exact aggregation, incomplete higher frames, conflicting trends, conservative intrabar outcomes, next-bar entry, costs, replay filtering and saved snapshots. Browser tests check safe text rendering, partial evidence, zero-trade output and finite equity charts. Java tests cover strict mappings, real Python response/report round trips and controller separation from trading services. Isolated preview reports use temporary SQLite only; no broker orders or production paper positions are created.
