# Master Prompt — Groww AI Options Trader

Act as a principal Java, AI, quantitative trading, security and UX engineer. Build a robust Indian options trading platform using Java 21, Spring Boot, Spring AI, Angular or a polished responsive UI, Oracle Database, Flyway, and a self-hosted trading-focused model (Ollama initially). Implement in small compiling, tested phases. Start in paper mode and keep live execution disabled until explicitly approved and current Groww API/compliance requirements are verified.

## User rules
- Initial capital ₹10,000, UI-configurable.
- Maximum 5 ENTRY orders/day initially; exit orders do not count.
- Stop new entries after 3 completed profitable trades, where profit is net realized P&L after charges.
- Entry window 09:15–14:58 Asia/Kolkata on actual exchange sessions.
- Initiate square-off at 14:57; stop new entries, cancel eligible pending entries, attempt exits and monitor unresolved positions. Exit attempts must not be blocked by entry limits or entry cutoff. Do not claim settlement is complete at 14:57.
- UI-editable capital, risk per trade, daily loss limit, entry cap, profitable-trade stop and mode; server validation, confirmation, versioning and audit history.
- Every trade report must explain entry/exit using recorded evidence: timestamps, strategy/version, indicator values, source-linked news, AI assessment and uncertainty, orders/fills, charges, gross/net P&L, timeline and exit reason. Never invent post-hoc explanations.
- Spring AI interprets news and explains evidence. Deterministic Java owns numerical calculations, strategy gates, position sizing and order authorization. AI never submits orders.
- Use virtual threads for suitable I/O-bound work with bounded concurrency, timeouts, provider rate limits and backpressure. Benchmark rather than promise arbitrary latency.
- Oracle Database + Flyway are the target persistence layer.
- The trading-focused model should use curated knowledge/RAG and may be fine-tuned later only on reviewed data. Prompting alone cannot guarantee the model never answers outside the domain.
- Live Groww adapter is feature-flagged off until verified and approved.

## Architecture
Market/news adapters -> data quality -> quantitative analysis -> Spring AI structured assessment -> explicit strategy -> independent risk gate -> paper execution -> trade journal/reporting. Add Groww only after official API and compliance verification. Add reconciliation, idempotency, secret management, stale-data rejection, realistic costs/slippage, exchange calendar, observability and failure recovery.

## Delivery phases
1. Compiling foundation and paper UI.
2. Oracle persistence and audited settings.
3. Real market data, instruments and calendar.
4. Quantitative strategy and backtesting.
5. Spring AI news analysis and trading knowledge RAG.
6. Detailed trade journal/reports.
7. Paper execution/reconciliation.
8. Performance/security/load tests.
9. Official Groww adapter, disabled by default.
10. Controlled live pilot after explicit approval.
