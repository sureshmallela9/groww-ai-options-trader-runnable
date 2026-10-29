# Analysis from the first completed candle

Analysis now accepts 1–5,000 valid completed OHLCV bars. The same lower limit applies to Python `/analyze`, supplied bars in the versioned stock-analysis API, and Java's direct analysis and historical analysis bridge. Empty or invalid data is still rejected; the automatic session endpoint returns a waiting state for empty valid history.

With fewer than 50 bars, `data_sufficiency` is `partial`, `trend` is `mixed`, and the summary describes provisional price movement. `indicator_status` explains unavailable indicators. Their numeric values are JSON null, never zero-filled or calculated using shorter windows with the original indicator name. SMA20/relative volume require 20 bars; SMA50 requires 50; five-bar return requires 6; 20-bar return requires 21; RSI14 and ATR14 require 15 (14 changes with a previous close). The established calculation is retained for 50 or more bars. Versioned EMA/MACD features retain their documented first-close seed; strategy warm-up is still required for paper proposals.

## Automatic session analysis

Open `http://localhost:1111`, find **Trading AI analysis**, select exchange, segment, Groww symbol and candle interval, and enable **Automatically analyze today from 09:15 India time · check every 5 seconds**.

The Java app calls the broker's historical endpoint using today's India-time range, 09:15 to now (capped at 15:30). Completed valid candles from the entire available range are passed to Python. An empty session, a holiday, an invalid symbol or delayed provider data cannot produce an invented analysis. The regular-session range is an analysis default, not verified exchange-calendar evidence for a trade; special sessions require a manual historical date range.

The browser checks every five seconds while visible, with one outstanding request. Errors back off; broker Retry-After is respected. There is no five-second candle generation: with a five-minute interval, the first 09:15 candle can be analyzed after 09:20. It can appear later if the provider delays delivery. Changing the selection discards stale responses; stopping the checkbox or leaving the page stops browser polling. This is an opt-in page-based workflow, not an unattended server scheduler.

Java shares a five-second fetch budget for identical session settings and uses bounded workers. New or corrected normalized OHLCV data generates a new report. Unchanged data reuses the previous result, so it does not fill Reports with duplicates. The cache is process-local and date-specific; a Java restart clears it. Ambiguous AI failures are retained for unchanged bars to prevent automatically repeating a request that may already have saved a report; use Saved reports and manual analysis to recover. Changes to the source index alone do not automatically reanalyze unchanged candles.

Endpoint: `POST /api/backtesting/analyze/session`. Body: `exchange`, `segment`, `groww_symbol`, `candle_interval`, optional `analysis_prompt` and `knowledge_query`. The server owns start/end times even if those fields are supplied. The legacy local UI authentication behavior applies. Returns `status`, `message` when waiting, `checkedAt`, `pollAfterMs`, `automaticAnalysis`, `tradeAuthorized: false`, and, when available, `source`, `analysis`, `reusedAnalysis`. Status can be `WAITING_FOR_SESSION`, `WAITING_FOR_CANDLES`, `ANALYZED` or an AI availability/error status. Raw and valid candle counts are separate.

Manual historical backtests still require 51 bars for their 50-bar signal and later replay. The versioned paper strategy still requires its independent 100-bar warm-up, calendar, freshness and risk checks. Automatic session analysis never invokes order, paper-intent or backtest execution endpoints.

Restart both applications after updating, then refresh the browser with Ctrl+F5. If a packaged Java JAR is used, rebuild the package before restarting.
