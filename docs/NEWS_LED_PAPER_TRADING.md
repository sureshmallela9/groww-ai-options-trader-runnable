# News-led research, daily reports and one-position paper trading

This phase implements the requested workflow in both applications. It remains long-only NSE cash paper simulation. A negative setup is recorded as negative/no-trade; opening short positions is not implemented. After an entry, a new AI signal does not switch stocks or exit early.

## Workflow

1. A bounded background worker in Trading AI collects permitted news. The supplied public sources are NSE corporate announcements and Federal Reserve press releases, using their official RSS feeds. Existing custom news configuration is preserved. JSON providers and manually imported news remain supported.
2. News is mapped to the maintained NSE stock universe. For a stock shortlist, use relevant news published today in Asia/Kolkata first. Only if no relevant today news exists, use available records from the preceding seven days. Negative or neutral today news does not get discarded in favor of an older positive story. Global/unmapped headlines remain in the report but do not masquerade as Indian stock news.
3. The AI returns up to five positive news candidates and a separate negative watchlist. Source links, publication dates, heuristic scores and keyword evidence explain the interpretation. This is a transparent lexical heuristic, not a trained forecast or a probability of profit.
4. The trader loads candidates plus full NIFTY-50 completed-candle context. All 50 histories still verify breadth and derived industry baskets; news alone cannot replace those checks. The strategy needs 100 completed five-minute candles, including earlier sessions.
5. Candle comparison can select only a stock in the fresh news plan. Final analysis returns a descriptive POSITIVE/NEGATIVE/NEUTRAL signal. Only positive, fully qualified long proposals can proceed; Java independently checks fresh quotes, costs, spread, depth, quantity and risk limits before a paper entry.
6. Only one managed active entry/position/exit is allowed, across manual and automatic canonical paper requests. The risk row is locked before approval, including concurrent distinct requests. Existing unresolved legacy demo entries also block a new managed entry; the legacy synthetic entry route is disabled.
7. Once open, hold the same stock until its stop, target, configured daily profit/loss limit, manual/emergency request, or time exit. Arbitrary AI exit proposals are recorded without moving the held position into an early exit. Existing independent kill-switch, stale-data and session protections remain.
8. At or after 14:59 IST, new entries are forbidden and managed open positions are marked for exit. The configured regular-session calendar now uses 14:59 square-off, with entries stopping at 14:58. An earlier configured session exit remains stricter. If data or depth is unavailable, the exit stays pending with an incident; the system never invents a fill at the deadline.

## News tab and daily archive

Open `/workspace/news`. The background worker saves a report keyed by IST date and updates that day's report as new evidence arrives. Past days remain available in the date/history controls. The app must stay running; this is not an operating-system scheduler or a notification automation.

Reports contain:

- An executive research summary and explicit data availability.
- S&P 500, Nasdaq Composite, Dow and VIX daily closing observations from FRED, each with source link, observation date, previous observation and change.
- Mixed observation dates, stale values and missing series called out explicitly. These are published closing values, not live US prices.
- Conditional US-to-India interpretations, including technology sentiment and volatility context. No fixed correlation or causal price forecast is assumed.
- The today/weekly stock-news window, positive candidates, negative watchlist, sources and sentiment evidence.
- Latest recorded candle comparison, breadth, candidate reasons and matching final decision, when available.
- Confirmation checklist, one-position/hold rules and report limitations.

The News page reads saved data; refresh does not place an order. The existing footer continues to display the current canonical paper portfolio P&L. Daily research reports are separate from trade fills and the ordinary saved analysis/replay reports.

## Timing and configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `TRADING_AI_NEWS_ENABLED` | `true` | One bounded collection/report worker per API process |
| `TRADING_AI_NEWS_REFRESH_SECONDS` | `60` | Delay after a news/report cycle, supported range 30–3600 |
| `TRADING_AI_NEWS_SOURCES` | `config/news_sources.json` | Explicit permitted JSON/RSS feed list |
| `TRADING_AI_US_CONTEXT_ENABLED` | `true` | Fetch public US closing observations |
| `TRADING_AI_US_REFRESH_SECONDS` | `3600` | US snapshot cache, supported range 900–86400 |
| Automation rule checks | 5 seconds | No overlapping selection worker; waits for session/risk/news readiness |
| Stock candle comparison | Eligible completed 5-minute bucket | Full context is cached within the bucket |
| Position monitoring | 1-second configured delay | Fresh quotes, target/stop, daily limits and exit deadline |

Set `dailyProfitTarget` in the administrator risk policy, in rupees. Zero disables the optional daily-profit amount exit. Existing stop/target and daily-loss rules remain active. After a daily-profit target exit, the session stop is latched even if a later quote would change the amount. `maxOpenPositions` is always effectively one, even if an older stored policy contained a larger value.

Feed failures, missing today/weekly records or missing universe coverage are visible states, not fabricated candidates. RSS dates without offsets are interpreted using the source timezone. Unrelated Indian-company disclosures are not stored as target-stock evidence; macro headlines are retained separately. Public feed bodies are used for bounded heuristic interpretation, while reports retain headlines, source links and evidence terms rather than republishing full articles.

## Integration contracts

Trading AI (same existing internal authorization):

- `POST /api/v1/automation/news-plan`, body `{}`. Returns `planId`, `status`, `lookbackUsed` (`TODAY` or `LAST_7_DAYS`), `candidates`, `bearishCandidates`, reasons, source dates, expiry and US context. `executionAllowed` is always false.
- `POST /api/v1/automation/select`: existing request plus optional `newsPlanId`. When supplied, the stored plan must exist, be READY and unexpired; only its candidates can qualify.
- `POST /api/v1/decisions/evaluate`: existing request plus optional `newsPlanId`. The selected symbol must belong to that plan. The response adds `analysisSignal` and global context; independent Java approval is still required.
- `GET /api/v1/news/daily-reports`: up to 31 saved daily-report summaries and worker/cache intervals.
- `GET /api/v1/news/daily-reports/{YYYY-MM-DD}`: one saved daily report. Today's initial partial report can be created from cached evidence without initiating a provider request.

Trader proxies: `GET /api/v1/research/news/daily-reports` and `GET /api/v1/research/news/daily-reports/{YYYY-MM-DD}`. Dates are validated before forwarding. The normal internal token is required. No credentials, risk policy or trading controls are exposed by the report.

## Sources

- [NSE's official RSS directory](https://www.nseindia.com/static/rss-feed)
- [Federal Reserve RSS directory](https://www.federalreserve.gov/feeds/feeds.htm)
- [S&P 500 daily closes and source notes](https://fred.stlouisfed.org/series/SP500)
- [Nasdaq Composite](https://fred.stlouisfed.org/series/NASDAQCOM)
- [Dow Jones Industrial Average](https://fred.stlouisfed.org/series/DJIA)
- [CBOE VIX](https://fred.stlouisfed.org/series/VIXCLS)
