# Dedicated stock-selection data workers

The current implementation assigns **one dedicated worker to NIFTY 50** (`automatic-nifty50-history`) and **one to other maintained NIFTY 500 stocks** (`automatic-other-stock-history`). The existing single coordinator (`automatic-paper-selection`, a legacy internal thread name used in both execution modes) combines their data and permits one final AI selection and independently approved entry at a time.

See [BROADER_SELECTION.md](BROADER_SELECTION.md) for the complete flow, bounded other-stock scanning, news-first fallback, capital rules, API contract, monitoring fields and activation instructions. This replaces the earlier generic two-history-worker arrangement.

Read-only `/api/v1/auto/status` exposes the groups under `activity.universeWorkers.nifty50` and `activity.universeWorkers.otherStocks`. The Automation UI shows each group's progress; AI Analysis labels candidate rows as NIFTY 50 or Other stocks. Screening counts distinguish price coverage from full candle analysis.

The two readers do not submit orders. Existing broker pacing, independent risk approval and one-active-position controls remain in force. The application must be restarted to load structural thread changes; source installation alone does not activate the new flow.
