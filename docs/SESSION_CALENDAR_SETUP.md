# Session calendar and entry-window diagnostics

The ignored runtime file `config/session-calendar.json` now contains reviewed regular NSE cash sessions from 2026-10-28 through 2026-12-31. It is based on [NSE circular CMTR71775](https://nsearchives.nseindia.com/content/circulars/CMTR71775.pdf) and the [normal cash-market hours](https://www.nseindia.com/static/market-data/market-timings). Review subsequent exchange amendments before use. Weekends and listed holidays are closed. The November 8 special Muhurat session is explicitly blocked until its separate timing and strategy support are configured. Dates beyond this explicit coverage cannot authorize entry.

The file preserves the application's existing 14:58 entry cutoff and 14:57 square-off policy. The effective last entry time is the earlier value, 14:57 IST. Market-open status and entry permission are separate: between 14:57 and 15:30 the cash market can be open while this strategy forbids new entries. This is an application risk policy, not the exchange's closing time.

`TRADING_SESSION_CALENDAR` may point to an absolute reviewed JSON file. If no override is set, the default works from either the project root or its `backend` working directory. Explicit custom paths are never silently replaced.

The API/UI now distinguishes CALENDAR_MISSING, CALENDAR_INVALID, CALENDAR_NOT_REVIEWED, DATE_NOT_CONFIGURED, SPECIAL_SESSION_NOT_CONFIGURED, NON_TRADING_DAY, BEFORE_OPEN, ENTRY_WINDOW_OPEN, ENTRY_WINDOW_CLOSED and MARKET_CLOSED. It shows the resolved file, India clock, configured entry end and next configured entry session in the evidence panel.

While entries are session-blocked, an enabled auto worker may fetch a read-only NIFTY reference chart. It is labelled as a reference index, never a selected trade candidate, and cannot generate an order. A failed reference fetch leaves entries blocked. Stock selection, AI decisions and simulated entry still require the reviewed open entry session. Existing paper positions continue under the independent risk monitor.

Restart the Java application after installing this code, refresh the browser with Ctrl+F5, reconnect, and explicitly start paper auto mode. A restart pauses the prior automatic run. The calendar is re-read on each status check; later reviewed date changes do not require a Java restart. Never change `verified` merely to bypass a missing or uncertain session.
