# Intraday exposure-based daily loss limit

Daily loss budget uses the selected mode's configured intraday exposure ceiling:

cash allocation = min(effective capital, maximum cash position allocation)
exposure base = cash allocation × selected-mode leverage
daily loss limit = exposure base × effective daily loss percentage / 100

Paper uses paperLeverage; real uses realMaxLeverage. INR 1,800 at 5× and 2% gives
INR 180; at 3× it gives INR 108; at 1× it gives INR 36. This is the configured
ceiling, not a claim of actual broker-granted leverage, used margin, or current position value.
Groww margin, funds, costs and depth still independently constrain real orders.

Risk status exposes dailyLossBasis=CONFIGURED_INTRADAY_EXPOSURE, dailyLossCashAllocation,
dailyLossLeverage, dailyLossExposureBase and dailyLossLimit. Automation, remaining
daily-risk checks, position exits and the UI use this shared budget. Realized and open
P&L after estimated costs count toward it. Exit requests are not completed fills.

Dashboard and administrator cash capital and percentage limits still use the stricter
values. Per-trade risk now uses the same configured exposure base: `exposure base × effective riskPerTradePercent / 100`. INR 1,800 at 5× and 0.5% gives INR 45. Capital-based share-price eligibility remains cash-based.
The fixed INR 250 cap remains removed; legacy dailyLossAmount values are ignored.
Existing daily-loss locks are not cleared. Changing allocation or leverage changes
the derived daily budget; the UI shows the current calculation. Trade stop/target and
daily profit configuration are unchanged. Broker execution can exceed the loss threshold.

Restart Groww Trader and refresh the browser to load the rebuilt code and labels.
No saved percentage/leverage change or database migration is required by this update.
