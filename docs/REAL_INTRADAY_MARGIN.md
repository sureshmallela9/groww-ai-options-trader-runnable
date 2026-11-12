# Broker-verified real intraday sizing

Real intraday trading uses Groww **MIS** orders for NSE cash equities. The Risk & settings screen now has **Real intraday maximum exposure**, separate from paper leverage. Choose a ceiling such as **up to 3×** or **up to 5×**. Existing settings default to 1× until an administrator saves a higher ceiling. Loading paper-testing defaults preserves the selected real ceiling.

The ceiling is a maximum permitted exposure, not a promise of broker leverage. For every real entry the trader asks Groww for its required MIS margin for the exact stock, BUY/SELL side, whole-share quantity and LIMIT price. It also checks available MIS funds. If a stock requires more margin, an automatic proposal is reduced to a quantity actually verified by the margin API. Even 1× or no entry may result. Explicitly requested manual quantities are rejected instead of silently resized.

AI planning proposes the maximum whole-share quantity within its exposure, cost-reserve and exposure-based stop-risk limits. AI responses have `realMaxLeverage`, `planningLeverage`, `requiresBrokerMarginCheck: true`, `paperMarginAssumption: false` and `marginRequired: null` for REAL proposals. AI never supplies a verified broker margin or authorizes execution.

The trader limits exposure to the configured cash allocation times the ceiling. Groww's actual margin **plus a conservative round-trip cost reserve** must fit both the configured cash allocation and broker-available MIS funds. It uses at most ten margin estimates per automatic sizing attempt, approving only a quantity with a successful estimate. The bounded search can conservatively leave some cash unused. Broker errors or missing/zero/negative requirements block entry.

Per-trade risk uses the selected-mode configured exposure base, as requested on 11 November 2026. For INR 1,800 at 5× and 0.5%, the planned per-trade risk is INR 45. Remaining daily loss budget, stop/target geometry, market session, quote age, executable depth, static IP and one-active-order rules still apply. The daily loss budget separately uses the selected mode’s configured exposure ceiling; see [PERCENTAGE_DAILY_LOSS.md](../PERCENTAGE_DAILY_LOSS.md). Margin, funds and current risk settings are rechecked immediately before broker dispatch. A changed requirement can reject an unsent entry. Only actual broker trades become fills.

Saved real intents expose the approved `quantity`, original `requestedQuantity`, `quantityAdjustedForBrokerMargin`, `marginRequired`, `marginCostReserve`, `marginBudget`, `marginFundsAvailable`, `marginCheckedAt`, `maxIntradayLeverage`, `brokerEffectiveLeverage` and `brokerMarginVerified`. The effective multiple describes the checked order's notional divided by its margin estimate; it is not a guaranteed margin entitlement or the current margin on a partially filled position. Position details show the checked entry margin and ceiling.

## Using it

1. Restart Trading AI, then Groww Trader, using your usual launch method. Refresh the browser to load the versioned UI assets.
2. In **Risk & settings → Edit intraday rules**, select the real maximum exposure and save with the existing administrator token. Review the cash allocation, per-trade risk and daily loss limits.
3. When ready to trade, select **Real trading**, complete its connection/static-IP check and confirmations, and explicitly start auto mode. Saving margin rules does not switch mode or start automation.

The reviewed local exchange calendar currently includes a regular session for Monday, 9 November 2026; its session gate remains authoritative. No Monday job, real-mode activation, live order or change to saved risk settings was performed by this code update. Existing positions retain their agreed stop and target.

Official API reference: [Groww margin calculations](https://groww.in/trade-api/docs/curl/margin). CASH estimates use `POST /v1/margins/detail/orders?segment=CASH` with an MIS order body; available funds use `GET /v1/margins/detail/user` and `equity_margin_details.mis_balance_available`.
