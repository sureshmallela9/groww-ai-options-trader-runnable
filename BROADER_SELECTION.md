# Selection across NIFTY 50 and other stocks

One dedicated thread loads NIFTY 50 context (`automatic-nifty50-history`). A second loads other maintained NIFTY 500 stocks (`automatic-other-stock-history`). The existing selection coordinator combines both groups into **one AI selection request** and permits at most one managed entry. The workers never place orders.

## Selection flow

1. Refresh the official NIFTY 50 and NIFTY 500 registries when needed. Both must be current; the NIFTY 50 membership must be present in the broader registry. The official NIFTY 500 CSV contained 501 securities when checked on 9 November 2026; counts are displayed from the actual registry rather than assumed to be exactly 500.
2. Map news across the maintained broader registry. Use today's company news, falling back to the prior seven days as before. Up to three directional news candidates remain primary candidates.
3. Worker 1 loads the NIFTY reference and all 50 context histories. Worker 2 screens other stocks using Groww's LTP endpoint in groups of at most 50 instruments per request. Screening prices are cached for 60 seconds; these are price-screen inputs, not executable quotes or verified margin approvals.
4. The second worker applies effective cash-capital share-price limits and the separate configured fallback price ceiling. It loads full available completed candle histories for up to 12 outside stocks per cycle, including news candidates and a rotating affordable batch. News candidates can remain visible as rejected evidence even when too expensive. Rotation reserves room for news stocks without permanently starving other eligible stocks.
5. AI retains NIFTY 50 breadth and same-industry NIFTY context as its market baseline. Additional candidates do not change the breadth denominator. All 50 context histories remain required. Extra histories are separately bounded, unique, outside NIFTY 50 and validated against the current official broader registry. Missing sector evidence or stale/invalid history blocks qualification.
6. With price fallback enabled and a fresh successful news collection, qualified news candidates take priority. If none pass, affordable candle candidates from either group may qualify. A failed/stale news collection cannot enable this fallback. When fallback is off, only the approved directional news candidates can authorize proposals.
7. AI chooses one qualified candidate. Broader entries and fallback proposals must match the saved selection ID, news-plan ID, stock, side, source and freshness before final evaluation. The final quote, independent Java risk policy and real Groww MIS margin/funds checks can still reject it.

## Price and risk rules

The cash-capital bands remain: below INR 10,000 allows shares strictly below INR 200; INR 10,000 through INR 20,000 inclusive allows shares strictly below INR 500; above INR 20,000 allows shares up to INR 3,000 inclusive. Leverage does not raise these price limits. The separate fallback ceiling can be tighter. Daily loss, one-position, entry-count, stop/target and 14:59 IST exit rules remain in force.

The broader scan is a bounded candle batch after a wider price screen. It does **not** claim to analyse every NIFTY 500 history every five seconds, and it does not include every NSE-listed company. Network delays, broker budgets and retries can extend completion time. No fixed stock-selection deadline or trade is guaranteed.

## API changes

- Existing NIFTY 50 registry endpoints stay unchanged. New authenticated endpoints are `GET /api/v1/universe/extended` and `POST /api/v1/universe/extended/refresh`, backed by the official NIFTY 500 CSV.
- `/api/v1/automation/select` accepts `candidateSnapshots` (at most 12 additional histories) alongside the existing full `snapshots` context array. The new selection mode is `NEWS_THEN_PRICE`. Existing NEWS/PRICE_FALLBACK requests remain supported.
- Candidate rows expose `universeScope` (`NIFTY50` or `OTHER_STOCKS`) and `selectionSource` (`NEWS` or `PRICE_FALLBACK`). The winning row exposes the same provenance.
- `scanCoverage` identifies the 50-stock market context and the actual additional-history count. Trader `screeningCoverage` identifies outside registry size, available prices, affordable stocks and batch size. Counts distinguish price screening from candle analysis.
- Final `/api/v1/decisions/evaluate` requests use the winner's actual NEWS/PRICE_FALLBACK source and its stored selection ID for broader/fallback selection. Unknown or unconfigured symbols continue to produce NO_TRADE through the normal engine checks.
- Read-only trader activity exposes `universeWorkers.nifty50` and `universeWorkers.otherStocks`, including each group's status, history counts, symbol and update time. The UI displays the two groups separately and labels comparison rows by universe.

## Configuration and activation

No credentials or database schema changes are needed. Existing `fallbackEnabled` controls price-based opportunities; saved capital, fallback ceiling, leverage and risk settings remain unchanged. The application refreshes and stores the additional official registry automatically when the updated workflow runs.

Restart both updated applications, then reload the browser through the normal workflow after active orders/positions are resolved. Review the execution mode and explicitly start automation using the existing controls. Source installation or a rebuild does not itself restart services, activate automation or submit orders.

## Sources

- Official NIFTY 500: https://www.niftyindices.com/indices/equity/broad-based-indices/nifty-500
- Official constituent CSV: https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv
- Groww LTP documentation (up to 50 instruments per request): https://groww.in/trade-api/docs/curl/live-data
