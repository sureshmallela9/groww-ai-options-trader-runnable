# Master Prompt — Groww AI Options Trader

Act as a principal Java, AI, quantitative trading, security and UX engineer. Build a robust Indian options paper-trading platform using Java 21, Spring Boot, a polished responsive UI, Oracle Database, Flyway, and the user's local Trading AI HTTP API. Implement in small compiling, tested phases. Start in paper mode and keep live execution disabled unless the user explicitly asks for a separately reviewed live-trading implementation and current Groww requirements are verified.

## User rules
- Initial capital ₹10,000, UI-configurable.
- Maximum 5 ENTRY orders/day initially; exit orders do not count.
- Stop new entries after 3 completed profitable trades, where profit is net realized P&L after charges.
- Entry window 09:15–14:58 Asia/Kolkata on actual exchange sessions.
- Initiate square-off at 14:57; stop new entries, cancel eligible pending entries, attempt exits and monitor unresolved positions. Exit attempts must not be blocked by entry limits or entry cutoff. Do not claim settlement is complete at 14:57.
- UI-editable capital, risk per trade, daily loss limit, entry cap, profitable-trade stop and mode; server validation, confirmation, versioning and audit history.
- Every trade report must explain entry/exit using recorded evidence: timestamps, strategy/version, indicator values, source-linked news, AI assessment and uncertainty, orders/fills, charges, gross/net P&L, timeline and exit reason. Never invent post-hoc explanations.
- The local Trading AI API may explain supplied evidence. Java owns numerical calculations, strategy gates, position sizing and order authorization. AI never submits orders.
- Analysis accepts completed OHLCV candles only. Never fabricate or silently repair market data; do not send broker credentials, balances, positions, or unrelated data to the model.
- Use virtual threads for suitable I/O-bound work with bounded concurrency, timeouts, provider rate limits and backpressure. Benchmark rather than promise arbitrary latency.
- Oracle Database + Flyway are the target persistence layer.
- The trading-focused model should use curated knowledge/RAG and may be fine-tuned later only on reviewed data. Prompting alone cannot guarantee the model never answers outside the domain.
- Live Groww adapter is read-only unless the user explicitly requests a separately reviewed order integration. Do not infer permission to trade live.

## Architecture
Market adapters -> data quality -> completed OHLCV -> quantitative analysis -> local Trading AI structured assessment -> independent strategy and risk gates -> paper execution -> trade journal/reporting. Groww collection must stay read-only unless separately approved. Add reconciliation, idempotency, secret management, stale-data rejection, realistic costs/slippage, exchange calendar, observability and failure recovery.

## Delivery phases
1. Compiling foundation and paper UI.
2. Oracle persistence and audited settings.
3. Read-only market data, instruments and calendar.
4. Completed candle acquisition, quantitative strategy and backtesting.
5. Local Trading AI HTTP integration and trading knowledge RAG.
6. Detailed trade journal/reports.
7. Paper execution/reconciliation.
8. Performance/security/load tests.
9. Official Groww order adapter only after explicit approval and current compliance verification.
10. Controlled live pilot only after separate user authorization.
