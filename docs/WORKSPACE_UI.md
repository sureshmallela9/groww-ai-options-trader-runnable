# Trading workspace screens

Every screen has a direct `/workspace/...` route. The dashboard, old hash links, `/paper-workspace.html` and `/research-tools.html` resolve to one connected workspace. See [Workspace architecture and routes](WORKSPACE_ARCHITECTURE.md) for the design and verification guide.

* Overview: current paper totals, selected-stock/reference chart, market context, current position and the next action.
* Automation: opt-in start/stop/exit controls, the active stage, session information and effective rules.
* AI Analysis: readable decision summary, indicator cards, positive/negative signs, risk notes, candidate comparison, source context and optional full technical JSON. Manual candle analysis and historical backtests remain in a collapsed research section.
* Reports: saved analysis questions and responses, searchable recent history and direct detail links.
* Positions: paper fills and history, green buy/red exit labels, P&L, search/status filters, paged history and addressable position-detail snapshots with lifecycle records.
* Risk & settings: dashboard settings, effective independent limits, kill switch and administrator-only hard-limit controls.
* Research tools: advanced manual market/news/scan workflows and clearly separated legacy demo/backtest records.

The header provides a Paper trading / Real trading mode control. Paper remains the active execution mode. Selecting Real opens the availability dialog; it never changes the execution mode or submits an order. The server's live-mode rejection remains authoritative. Real broker execution is still unavailable.

The shared connection token is entered in one dialog and remains in page memory. No credential is written to browser storage. Administrator credentials are cleared after their use. Navigation does not start, stop or change the server-side automatic run. The P&L footer stays visible across screens.

This update changes presentation only: the existing analysis, session/calendar rules, data validation, position sizing, risk approval and simulated fills remain on the same server endpoints. Tests use synthetic fixtures and isolated databases. No live or production paper order is placed during UI verification.
