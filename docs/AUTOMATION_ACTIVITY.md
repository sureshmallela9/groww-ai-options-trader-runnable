# Automation activity and processing status

Overview and Automation now show whether the server is processing, blocked, waiting, monitoring a position, stopped or paused. The status refreshes every five seconds while the page is visible; a manual Refresh status button is also available. Refreshing status does not start a scan or place an order.

During data validation the display shows actual completed history loads out of 51 (NIFTY plus the maintained 50 stocks), together with the stock currently being fetched. This measures history loading, not a percentage of the entire trading decision. Analysis and final risk checks have their own processing stages without an invented percentage or completion estimate.

The screen also shows the latest server check, when the browser received status, the last stock comparison and final decision, the last scan result, and recent stage transitions. A completed scan may return no qualifying stock. The next candle eligibility time is conditional on session, data and risk checks. Retry times are earliest eligibility times, not guarantees of completion.

When a connection fails or the page disconnects, the processing indicator stops and the display makes it clear that a current server status is unavailable. This does not stop server automation. A server restart continues to pause entries until explicitly started, as before.

## API

The existing authenticated `GET /api/v1/auto/status` retains `automation`, `portfolio`, and `risk`. The additive `automation.activity` object contains:

| Field | Meaning |
| --- | --- |
| `status` | PROCESSING, BLOCKED, WAITING, MONITORING, STOPPED or PAUSED |
| `processing` | Whether this server is actively working on this automation run |
| `phase` | CHECKING_RULES, VERIFYING_DATA, ANALYZING, FINAL_DECISION, RISK_CHECK, or the current result/restriction |
| `serverTime` | Server UTC timestamp for this response |
| `phaseStartedAt` | Start of the current phase, when available |
| `lastCheckedAt` | Latest server cycle check, including checks that wait for another candle |
| `completedHistories`, `totalHistories`, `currentSymbol` | Active history progress; currentSymbol is optional |
| `lastScanStartedAt` | Latest history collection/validation start |
| `lastSelectionAt`, `lastScanSummary` | Latest completed stock comparison and its result |
| `lastDecisionAt` | Latest completed final decision, when reached |
| `selectedSymbol` | Last selected candidate; it is not evidence of an executed order |
| `nextScanEligibleAt` | Optional next completed-candle eligibility time |
| `retryAt` | Optional earliest retry time after a worker error |
| `recentStages` | At most 12 persisted stage transitions for the current run |
| `checkIntervalMs`, `scanIntervalSeconds` | Configured server check delay and five-minute scan interval |

Activity is read-only observability. Entry restrictions, strategy requirements, risk revalidation, idempotency and paper-only execution remain in force. No static-IP setting or broker credential is changed by this feature. Trading AI's analysis contracts are unchanged.
