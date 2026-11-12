# Groww fill reconciliation and intraday maximum sizing — 11 November 2026

Groww returned individual completed trades with trade_status EXECUTED. Reconciliation
now accepts EXECUTED or COMPLETED while retaining broker identity, whole-share,
positive price, unique trade ID, timestamp and cumulative fill checks. An order
acknowledgement or a flat position alone is never treated as a fill.

If the user closes a managed position in Groww, the trader can adopt a fully matched
external exit from exact MIS order-list records and individual trades. The account
must be flat for that instrument, our own exits must be terminal, quantities must
match exactly, receipts must be unique and in the same session after the entry.
Pending, partial, duplicate, mixed or unrelated activity remains blocked. No new
exit is sent to recover a confirmed external closure. Existing protective orders
must first be cancelled and confirmed terminal to avoid reversing the position.

The external exit remains read-only across restart; its broker order can be read
from the order list if the detail endpoint does not expose manual orders.

Future entries remain NSE CASH MIS only. Configured real leverage is an up-to
ceiling, verified with current broker MIS funds and exact margin estimates.
The AI chooses the maximum whole-share proposal within exposure, planning costs
and stop-risk limits. Independent Java checks further cap automatic quantities by
fresh price, exposure risk, remaining daily risk and executable depth, then verify
broker funds/margin. Manual explicit quantities are not silently increased.

Per-trade and daily risk now share the selected mode's configured exposure base:
min(effective capital, cash allocation cap) × selected mode leverage.
INR 1,800 × 5 × 0.5% gives INR 45 planned per-trade risk; at 2% the daily threshold
is INR 180. This changes risk basis, not saved capital, percentages or leverage.
Share-price eligibility remains cash-capital based; existing positions retain
their stop/target. The fixed INR 250 cap stays removed. No threshold guarantees
the realized loss or an execution price.
