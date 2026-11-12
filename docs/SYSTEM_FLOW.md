Current workflow: see [News-led research and daily reports](NEWS_LED_PAPER_TRADING.md) for the news-first pipeline, US closing context, one-position hold rule and 14:59 IST exit deadline.

# Groww, Trading AI and the results window

Use **System flow** in the workspace header or **View call flow** under Automation. The panel shows the entry gate, Groww data, Python analysis, independent Java paper risk checks, results, and schedules. **Open separate window** opens a read-only window that receives a display-only snapshot from the workspace.

The separate window shares the existing five-second status feed. It does not receive the access token, input candles or account details, and does not make broker, analysis, or trading requests. The source workspace must remain open and connected. While that window is open, the source can continue status polling even when its own tab is hidden, without loading its charts or market overview. Closing the flow window restores normal hidden-tab polling behavior. This does not stop server automation.

Messages are accepted only from the exact same origin and expected window. Navigation requests are restricted to AI Analysis, Reports and Positions. Stale source updates suppress active-call highlights after 15 seconds. API details and reasons are rendered as text.

## Actual request path

1. Java checks its session calendar and configured entry restrictions. An event flag, kill switch, existing position, session or daily limit can stop selection before stock histories are requested. Independent background data feeds and open-position monitoring can still run.
2. For an eligible five-minute bucket, Java loads NIFTY plus the 50 constituent histories through Groww's `GET /v1/historical/candles`. Each history contains 100 completed five-minute bars, including earlier sessions. The per-bucket cache can avoid repeated loads. The 51 histories do not imply exactly 51 HTTP requests across all broker services.
3. Java sends the validated history batch to Python `POST /api/v1/automation/select`. Python ranks at most one qualifying candidate using market, derived industry-basket, technical, volume and capital conditions, and returns reasons. A no-trade result is normal.
4. For a candidate, Java obtains its fresh Groww quote and sends `POST /api/v1/decisions/evaluate`. This automatic path supplies candles and the quote; Python does not need a second direct Groww connection. Other research routes can obtain missing inputs through the normalized Java gateway.
5. Java independently reloads the decision, rechecks a fresh quote and configured risk rules, and records a paper fill only if allowed. Real execution remains unavailable.
6. Browser `GET /api/v1/auto/status` displays the selection and final decision. AI Analysis shows automatic evidence. Overview/Positions show simulated fills and P&L. Reports lists saved research analyses and replays; it is not the automatic decision-record listing.

## Schedules

The authenticated status response adds `automation.flow`, a display-only object populated from the same configuration keys as the scheduled services. It contains numeric timings and enable flags, without credentials or URLs. These values are configuration, not a measurement of every HTTP request.

| Activity | Default | Condition |
| --- | --- | --- |
| Automation checks | 5 seconds | Enabled, idle, scheduler active, no backoff |
| Full stock scan | Each eligible completed 5-minute candle | All prechecks and history validation pass; service/data failures can retry after backoff |
| AI final decision | On selected candidate | No ambiguous repeat in the same candle bucket |
| Groww index LTP | 1-second cycle delay | Groww enabled and credentials present; failures wait 30 seconds |
| Option-chain context | 30-second cycle delay | Separate background expiry/chain refresh |
| Open-position monitoring | 1-second cycle delay | Monitor enabled, open positions, usable quotes |
| Position-event dispatch | 3-second queue check | Pending events only; reassessment on a new candle bucket or at least 0.5% movement |
| Browser automation status | 5 seconds | Visible workspace or open read-only flow window |
| Overview quote display | 1 second | Visible Overview; reads cached Java data |
| AI health display | 15 seconds | Visible workspace; not market analysis |

Latency, retries, missing credentials, disabled services, the normalized read pacing and the shared broker budget can reduce actual request frequency. Configured fixed delays are not real-time guarantees. Highlights use reported server stages, so short operations can finish between screen updates.

No trading rules, broker credentials, source documents, or Python contracts are changed by this feature.
