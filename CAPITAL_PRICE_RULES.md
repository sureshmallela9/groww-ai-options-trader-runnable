# Capital-based share-price limits

Both Trading AI and Groww Trader apply this entry filter using **effective cash capital before leverage**. Effective capital is the lower of the dashboard capital limit and the independent platform capital limit.

| Effective cash capital | Allowed price per share |
| --- | --- |
| Below INR 10,000 | Strictly below INR 200 |
| INR 10,000 through INR 20,000, inclusive | Strictly below INR 500 |
| Above INR 20,000 | Up to INR 3,000, inclusive |

The final INR 3,000 share-price ceiling follows the user’s corrected maximum per share. It is editable. The filter does not guarantee that a stock or order is affordable: position value, actual Groww MIS margin, available funds, stop risk, liquidity and the daily loss budget can impose tighter limits. A 3x or 5x exposure ceiling does not move a stock into a higher capital band.

## Flow

1. Collect the normal fresh news shortlist and perform candle, market, sector and liquidity analysis.
2. Retain that evidence, then reject candidates outside the cash-capital price band before ranking qualified candidates. Select the highest-scoring eligible candidate; do not rotate stocks randomly or force a trade.
3. Recheck the final AI proposal against its executable entry price: ask for a BUY/LONG, bid for a SELL/SHORT.
4. Independently recheck in Groww Trader with its current effective capital and fresh executable price. A real entry outside the band is rejected before requesting broker margin. Recheck again immediately before real dispatch, so an older proposal or changed configuration cannot bypass the filter.
5. Existing open positions retain their original stop, target and exit monitoring. Price-band changes apply to new entries.

A news candidate can remain visible for learning even when rejected for price. If all candidates fail, the result is NO_TRADE with candidate-specific explanations. Price rejection does **not** count as “no directional news”: the existing fallback remains available only after a successful fresh news check with no directional candidates. Its separate configured maximum (currently allowed up to INR 500) also applies.

## Configuration and API

Open **Risk & settings → Edit intraday rules → Share-price limits by cash capital**. The three bands are editable, including whether boundary values are included. Changes use the existing protected intraday-rules save operation and administrator token. Saving does not switch paper/real mode or start automation.

The existing intraday configuration gains these fields:

```json
{
  "capitalPriceFilterEnabled": true,
  "capitalPriceBands": [
    {"capitalLimit":10000,"capitalLimitInclusive":false,"maxSharePrice":200,"sharePriceInclusive":false},
    {"capitalLimit":20000,"capitalLimitInclusive":true,"maxSharePrice":500,"sharePriceInclusive":false},
    {"capitalLimit":null,"capitalLimitInclusive":false,"maxSharePrice":3000,"sharePriceInclusive":true}
  ]
}
```

These fields accompany the existing fallback, leverage and stop/target fields when saving. Groww Trader forwards them in `policy` for `/api/v1/automation/select` and `/api/v1/decisions/evaluate`. Responses contain `capitalPriceCheck` with `enabled`, `capital`, `maxSharePrice`, `sharePriceInclusive`, `band`, `checkedPrice`, `allowed`, `reason` and `basis`. At the selection top level, `checkedPrice` is null because this describes the active policy; each candidate and the final decision include the checked price. This is a rule check, not a forecast or execution authorization.

Capital boundaries must increase, the last capital range must be open-ended, and prices must be positive. Validation applies even when stop/target price bands are off. Existing stored intraday settings that lack the new fields receive the enabled three-band defaults when read by the updated backend; no database migration or credential change is required. Existing old decision identities are not reused across changes to these eligibility rules.

## Activation and verification

Source changes do not restart the running applications. Restart the updated Trading AI and Groww Trader in your normal workflow, then reload the browser. When using a packaged trader, run the rebuilt backend JAR. Verify the effective capital and share-price limit under Risk & settings and the `capitalPriceCheck` in a newly completed analysis. An older backend is labelled as awaiting update rather than claiming enforcement.

Offline verification covers exact boundaries, legacy saved configuration, custom bands, news and fallback selection, both entry sides and modes, executable prices, policy forwarding, and independent real pre-dispatch rejection. Tests use synthetic data and mocked brokers; they do not prove broker execution against a real account.

## Observed repeated stock on 9 November 2026

At the read-only diagnosis, automation was REAL and enabled, effective cash capital INR 2,000, with no managed positions and status NO_TRADE. BAJAJFINSV was the sole news candidate from four articles, its completed-candle close was INR 1,835.80, and market/sector checks rejected it. This was a news shortlist, not an approved order. The selector uses the highest qualifying rule score and stable symbol ordering for ties; there is no hardcoded preference for BAJAJFINSV. This observation does not establish every historical selection over the previous week. Under the new defaults its price is independently ineligible for INR 2,000 capital.
