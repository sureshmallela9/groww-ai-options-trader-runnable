# Manual event pause and index candle compatibility

Live diagnosis on 30 October 2026 found two separate blockers:

1. The manual scheduled-event pause was enabled. It intentionally stopped the selection workflow before news/candle calls. The user confirmed this was a mistake, and the existing paper-only API cleared it. Normal five-second checks resumed.
2. Groww returned real NIFTY prices with null index volume (754 rows in one observed response). The old stock-oriented parser discarded every row. Known cash index references now retain valid completed OHLC data. A zero OHLCV-schema placeholder is explicitly labelled `NOT_APPLICABLE_INDEX` in provenance. The original provider response retains its null values. This exception does not apply to stock/derivative volume, invalid prices, malformed volume, incomplete candles or future data.

The manual control is labelled as an entry pause and displays its saved state. Failed changes revert to the last server-confirmed checkbox value. Clearing a running pause immediately reports that normal checks will run next; it does not claim analysis has completed. Reapplying auto mode without an event field preserves an existing pause.

Automation failures now identify the failing stage/symbol and expose a safe validation reason for empty candle histories. The status API includes `activity.failure` and identifies a manual event block with `blockSource: MANUAL_SETTING` and a review action.

All independent entry and exit checks remain active. Index data is market context; an index reference cannot satisfy stock instrument, volume, liquidity or risk approval. Orders remain simulated paper orders.

A subsequent real scan reached M&M and exposed an inconsistent provider symbol validator that rejected ampersands. The Groww symbol and quote-list validators now accept exchange-symbol ampersands while still rejecting query delimiters such as `?`, `=` and `%`. An HTTP client test verifies that `NSE-M&M` is sent as one encoded query value (`NSE-M%26M`).

The first complete live scan returned NO_TRADE with NIFTY in a sideways regime. The entry gate was correct, but its fallback explanation incorrectly claimed all market evidence agreed. Trading AI now gives an explicit sideways/bearish NIFTY rejection reason, preserving the existing bullish-confirmation gate.

Source: [Groww's official historical-candles documentation](https://groww.in/trade-api/docs/curl/backtesting) describes OHLC for all instruments and volume for tradable equities/FNO.
