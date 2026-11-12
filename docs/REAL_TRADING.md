# Real and paper execution — 6 November 2026

The workspace now selects `PAPER` or `REAL` in its header. No property edit is needed for routine switching in this installation. Changing modes pauses automation, clears the old decision display and requires an explicit start in the newly selected mode. Switching is refused while any managed order or position is active or unresolved. The default for existing installations is PAPER.

## Using the UI

1. Open `/workspace/automation`, refresh and connect with the existing shared token.
2. Choose **Real trading**. The connection check reads Groww funds, cash positions and cash orders and checks the server's current public IPv4. It does not submit a broker order.
3. Register that displayed address in the [Groww API-key dashboard](https://groww.in/trade-api/api-keys). Confirm registration and real-money use in the dialog, then choose **Use real trading** yourself. The application stores the expected IP in its execution settings. This declaration is not a programmatic verification of Groww's whitelist.
4. Review the selected mode, capital and risk settings. Confirm automatic real entries and press **Start auto mode** yourself. Selecting a mode alone never starts trading.
5. Use **Stop new entries** to pause selection. **Stop and exit real positions** requests actual broker exits; keep checking until broker trades confirm closure. A pending exit is not a completed fill.

**Paper trading** uses the existing simulator. Paper and real records, charts, daily totals and entry counters are separated by mode. A mode change does not delete earlier records. The initial real implementation supports NSE cash-equity **MIS** long and short proposals only, with a separate configurable real exposure ceiling of **up to 1–5× allocated cash** (existing settings default to 1×). Each order is sized against Groww's exact MIS margin requirement, available MIS funds, cost reserve and independent cash-risk limits. See [Broker-verified intraday sizing](REAL_INTRADAY_MARGIN.md). The paper leverage setting is separate.

## Execution and recovery

The workflow is news shortlist → completed candles → Trading AI proposal → independent Java risk approval → committed live reservation → bounded LIMIT entry → actual broker trade reconciliation → broker SL_M protection. Entry rechecks include IP, session, quote, decision expiry, global one-active-order limit, current risk policy and broker margin. Tick rounding is included in risk validation. The AI does not possess a broker order client or authorize execution itself.

Broker commands are saved before network submission. Each create has a persisted 20-character reference. Acknowledgements never become simulated real fills. A timeout, server failure or duplicate-reference response is reconciled using that reference; it is not blindly resent. Definite authentication/validation rejection remains distinct from an unknown outcome.

Partial entry fills cancel the unfilled balance and protect the confirmed quantity. Protection quantity is reconciled as the entry cancellation settles. Target, loss-cap, kill-switch and time exits modify the existing protective order to MARKET rather than creating a competing sell/cover order. Actual fill quantities, timestamps and prices determine positions and chart markers. Broker account mismatches stop further execution and request cancellation of the app's protection to avoid reversing an externally changed position. Such incidents require checking Groww; the app does not invent a compensating fill.

Existing broker orders and positions are recovered from persisted references after a restart. Automatic new entries remain paused after restart, while risk monitoring continues for managed exposure. The risk monitor requests exits at **14:59 IST**, independently of AI and quote availability when an existing broker order can be modified. Closure is reported only after actual execution and terminal order reconciliation. Neither a scheduled request nor a stop trigger guarantees a fill, exact exit time or maximum realized loss during gaps, outages or broker rejection.

## Risk and reporting

One managed active order/position across both modes remains a hard invariant. Capital, price-band rules, spread, turnover, slippage, signal freshness, per-trade risk, daily loss, winning-trade limits and daily entry limits still apply. Daily loss uses the configured mode-specific intraday exposure ceiling: `min(effective capital, cash position cap) × selected-mode leverage × effective loss percentage / 100`. For ₹1,800 at 5× and 2%, the limit is ₹180; at 3× it is ₹108. There is no fixed ₹250 cap. Actual real buying power remains subject to Groww margin/funds approval. Per-trade risk stays cash-based. See [PERCENTAGE_DAILY_LOSS.md](../PERCENTAGE_DAILY_LOSS.md).

Trade prices and quantities are broker-confirmed. Charges and net P&L remain labelled **estimates**, not a replacement for the broker contract note. Day-wise real reporting includes partial exits on their actual IST execution date. Keep the laptop awake, both apps running and the registered connection available for local target/time controls. The broker-held DAY stop provides protection during application downtime, subject to broker/exchange acceptance and execution.

## API additions

All routes below use the existing authenticated localhost `/api/v1` boundary.

| Method and route | Meaning |
|---|---|
| `GET /execution` | Selected mode, capability, active execution flag and explicit-start requirement |
| `POST /execution/preflight` | Read-only account/IP checks; no order placement |
| `PUT /auto/mode` | Select PAPER or REAL; pause automation; reject active/unresolved trades |
| `POST /auto/start` | Existing route; mode must match the persisted selection and `confirmed` must be true |
| `POST /trade-intents` | Existing idempotent route; supports proposals for the selected mode |
| `GET /positions` | Managed records for the selected mode |
| `POST /positions/{intentId}/close` | Request a mode-aware exit; never assume a fill |

Real selection body: `{"mode":"REAL","confirmed":true,"ipRegistered":true,"publicIp":"<address returned by preflight>"}`. Paper selection: `{"mode":"PAPER"}`. Trading AI `DecisionRequest.mode` now accepts REAL as well as PAPER and RESEARCH; REAL still returns `executionAllowed:false` and requires independent execution approval. Research cutoffs cannot authorize real proposals.

Existing `PAPER_INTENTS` storage is retained for compatibility; each record's mode separates the ledgers. No historical migration, schema quota change or deletion was required. Legacy `/paper/positions/...` mutations explicitly reject real records. Operators can disable the installation's live capability with `LIVE_TRADING_AVAILABLE=false`; this is an installation safeguard rather than a routine mode switch.

## Validation and remaining live evidence

151 Java tests passed with zero failures, errors or skips using temporary test storage and an isolated Python API. They cover transport rejection versus uncertainty, long/short fills, partial fills, protective orders, deadline exits without AI/quotes, margin/IP rejection, independent dispatch risk, account mismatch, mode races and paper regression. 81 Python tests and 80 UI tests passed. The contract harness reported a Windows temporary-log cleanup lock after BUILD SUCCESS; application assertions had all passed.

Both running apps were restarted with their original environment. Saved trade fields were preserved; the deployed state was verified as PAPER, automatic entries off and live execution off. The AI's deployed OpenAPI includes REAL proposals. The real dialog's connection check passed against Groww; both confirmation boxes remained unchecked and no real order was sent. Actual whitelist acceptance, order-type support for this account, live fills and broker cancellation/modification behavior still require the user's first controlled real execution validation.

Official references: [Groww static-IP setup](https://groww.in/blog/static-ip-api-trading-setup), [orders and trade reconciliation](https://groww.in/trade-api/docs/curl/orders), [margin checks](https://groww.in/trade-api/docs/curl/margin), [order types and MIS](https://groww.in/trade-api/docs/curl/annexures), [authentication and rate limits](https://groww.in/trade-api/docs/curl).
