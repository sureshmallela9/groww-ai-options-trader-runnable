# Groww API audit report

Open **Groww API audit** in the workspace navigation (`/workspace/audit`) and connect with your existing workspace token. The report is separate from order/P&L reports. It combines paper and real requests because both modes use the same market-data connection. It does not change trading mode, risk settings, automation or orders.

## What is recorded

Every outgoing HTTP attempt made by the application's two Groww clients: authentication, quotes/LTP/OHLC/options, candles, funds/margin, positions/holdings, order creation/modification/cancellation/status/trade reads, and instrument-list downloads. Each row has start/completion time, category/purpose, method and endpoint template, approved request fields (such as stock, quantity, price, interval), duration and HTTP/broker outcome. Call IDs and hashed correlation IDs help identify requests. Connection/read failures are included; exception text is excluded.

HTTP success means the transport returned successfully. It does **not** prove an order filled. A Groww `FAILURE` envelope is distinguished from an HTTP error. Authentication and instrument responses are never captured. Other responses are observed through a bounded 8 KB prefix to extract only status and an approved error code; full bodies are not saved and the original response is preserved.

Credentials, authorization headers, API keys, checksums, access tokens, arbitrary query fields, raw response bodies and raw error messages are excluded. Unknown endpoints are replaced with a template. Cached reads, requests rejected before reaching the HTTP client, and Groww activity from other apps are not outgoing calls by this app and have no audit row. Existing higher-level trade events remain in their original report.

## Filters and export

Choose Today, Yesterday, Last 7/30 days or a custom inclusive IST date range (up to 366 days). Filter by category, result and stock. Pages show 200 most recent matching calls with stable snapshot pagination; totals cover all matching records. Only the latest page refreshes every 10 seconds while visible. Refresh reads local records and does not itself call Groww.

CSV exports use the displayed filters and snapshot. Exports over 50,000 calls require narrower filters; they are never silently truncated. CSV cells escape quotation marks and spreadsheet formula prefixes. A recording/storage warning or malformed record makes the audit incomplete and disables complete CSV export.

## Storage and coverage

Compact daily JSONL files are stored under `data/groww-api-audit/` relative to the trader's working directory. Override the directory with `GROWW_API_AUDIT_DIR` if needed. `recording.json` preserves the recording start time across restarts. No database tables or quotas change. Files are excluded from Git and no automatic deletion is enabled; include this directory in your normal local backups and monitor its disk usage.

Recording begins with the updated application. Older requests were not captured and cannot be recreated. Requests still in flight appear when they complete or fail; an abrupt process/OS termination can leave an in-flight attempt unrecorded. Write-failure counts apply to the current process lifetime and cannot reconstruct gaps across restarts. Disk failure never retries an order or replaces a successful broker response. The report shows recording errors instead of presenting them as complete coverage.

## Local authenticated read API

`GET /api/v1/reports/groww-api?from=YYYY-MM-DD&to=YYYY-MM-DD`

Optional: `category`, `outcome`, `symbol`, `limit` (1–500), `cursor`, `snapshotAt`. Subsequent pages should preserve the returned snapshot. `GET /api/v1/reports/groww-api/export` accepts the same filters and snapshot and returns a JSON object containing `fileName`, `rowCount` and `csv`. Both endpoints use the existing internal bearer-token protection. No new configuration is required for the default location.
