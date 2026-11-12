# Daily order reports and exposure-based daily loss

Open **Reports → Daily order details**. Choose an activity date or one of the recorded days. Dates and times use IST. The initial date is the latest recorded day. Search filters the selected day's table, while summary totals remain for the full day.

The table includes stock, LONG/SHORT direction, requested/filled quantity, actual entry and exit prices/times, latest order status, stop, target and realized net P&L. Expand **Order details** for identifiers, exit reason, gross result, charges, remaining quantity and any recorded incident/rejection. The position link opens the existing position detail screen.

Orders appear on their request, entry, completed exit, or recorded pending-exit dates. Cross-day trades appear on both entry and exit days. Realized P&L and finalized simulated charges count only on the actual completed exit date. A pending exit never counts as a completed fill. Historical rows show the latest saved status with recorded timestamps; they do not reconstruct historical end-of-day balances. Missing accounting values are labelled incomplete. The footer continues to show today's current portfolio P&L.

Reports read the existing authenticated `GET /api/v1/trade-intents` endpoint, then group records in the browser. No additional broker or AI request is needed to build the daily report. Refresh reports reloads the saved history; successful workspace status updates also refresh changed order records. Existing Market analysis and Historical replay report types remain available. Older legacy/demo records remain in their existing separate journal and are excluded from automatic paper totals.

## Daily intraday exposure loss rule — updated 10 November 2026

The fixed INR 250 cap has been removed. `dailyLossAmount` in older saved policies is
ignored. The current limit is `min(effective capital, maximum cash allocation) ×
selected-mode leverage × effective dailyLossPercent / 100`. Paper uses paperLeverage;
real uses realMaxLeverage. INR 1,800 at 5× and 2% therefore gives INR 180; at 3× it
gives INR 108. This uses configured exposure, not broker-confirmed buying power.
Risk status exposes the cash allocation, leverage, exposure base, limit and daily lock.
Per-trade risk also uses the selected-mode exposure base. Capital-based share-price eligibility still uses cash capital.

The daily budget applies to remaining planned risk, new-entry gates and realized plus
open P&L after estimated costs. A breach records dailyLossTriggeredOn in IST and
blocks new entries for that session, including after a pending exit rebounds. The
marker survives restarts; this update does not clear it. Paper exits need usable
quotes/depth; real exits remain pending until broker-confirmed fills. The threshold
does not guarantee an execution price or maximum realized loss.

## Historical verification — 4 November 2026

- 63 browser/UI tests passed, including six new day-report tests for IST boundaries, overnight fills, pending exits, missing amounts, long/short saved accounting and late responses.
- 54 Java risk/automation tests passed, including three new cash-cap tests covering legacy defaults, tighter percentage limits, projected risk, pending exits and the daily lock after rebound.
- Live daily report verified: 3 November has one entry, two exits, 30.36 INR simulated charges and 116.64 INR net realized P&L, matching saved records.
- Live risk status confirms cash cap 250 INR, effective limit 200 INR, paper automation enabled, real execution disabled. The open ITC position's entry, quantity, stop and target were preserved during the service update.
- Read-only monitoring every five minutes is scheduled for 4 November only. Compact snapshots and the session review are saved in the task workspace for the evening discussion.
