# Groww AI Options Trader

Java 21 / Spring Boot application that reads Groww market data when configured and stores simulated paper trades and risk settings in Oracle. Market-data integration is read-only; live orders are not implemented.

## Current operation mode

- **Real market data:** Read-only Groww data polling is enabled by default when credentials are configured. NIFTY and BANKNIFTY quotes are polled once per second; a compact NIFTY option-chain snapshot is refreshed about every 30 seconds.
- **Paper execution:** Entries and exits remain synthetic. The application does not submit, modify, cancel, or track Groww orders. It always reports mode PAPER and liveExecutionEnabled false.
- **Analysis and backtesting:** Full-history analysis sends validated Groww candles to your local Trading AI API at TRADING_AI_ANALYSIS_URL (default http://127.0.0.1:8000). The separate paper-backtest flow uses the AI trend to simulate one entry and exit on later candles. It never submits a Groww order.

See [Architecture and API Guide](docs/ARCHITECTURE_AND_API.md) for the component diagram, API contracts, sample requests/responses, and run instructions.

## Historical candles and AI analysis

The app fetches real historical candles from Groww on demand. Full-history analysis sends normalized completed OHLCV bars and the original response envelope (including FNO open interest where returned). The backtest sends the first 50 bars to AI for a trend, then replays later bars with a 1 ATR stop and 2 ATR target. Its one-unit fills and fixed fee are illustrative; F&O lot size, taxes, slippage, and market impact are excluded. The AI service must accept the optional top-level market_data object.

See [Groww backtesting and AI request contract](docs/GROWW_BACKTESTING_AI_CONTRACT.md) for the exact request bodies, API routes, date-range caps, and PowerShell example.

## Initial NSE cash paper workspace

Open http://localhost:1111/paper-workspace.html after configuring the shared internal token, separate administrator token and verified session calendar. See [PAPER_PLATFORM_API.md](docs/PAPER_PLATFORM_API.md) and [MASTER_PROMPT_REVIEW.md](docs/MASTER_PROMPT_REVIEW.md). The new workspace uses independent persistent risk gates; legacy demo/backtest screens remain research experiments.

## Connect both projects

See [TWO_APP_SETUP.md](docs/TWO_APP_SETUP.md) for the Trading AI 0.5.0 contract, run settings, provider provenance and isolated verification. Restart both apps after updating.

## Requirements

- JDK 21 and Maven 3.9+
- Oracle schema/user and database access for Flyway
- Your Trading AI API running locally, with GET /health, POST /analyze, report endpoints, and support for the optional market_data object described in the integration contract
- Groww API credentials and historical-data entitlement for on-demand analysis and backtesting.

Use the Groww API secret, not the Groww account login password. Groww API-key authentication may require daily approval; access tokens expire daily. Read [Groww authentication and rate limits](https://groww.in/trade-api/docs/curl) and [Groww live-data docs](https://groww.in/trade-api/docs/curl/live-data).

## Configure and run

For Docker Compose, copy `.env.example` to `.env`, set Oracle and Groww credentials in that local file, and set `TRADING_AI_ANALYSIS_URL=http://host.docker.internal:8000` so the container can reach the API running on the host. Keep the Trading AI service bound to host loopback; do not expose it publicly. Then run:

~~~powershell
docker compose up --build
~~~

The `.env` file is not committed. For direct Maven or IntelliJ runs, set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `TRADING_AI_ANALYSIS_URL`, and any Groww settings in the process environment or IntelliJ Run Configuration. Spring Boot does not automatically read `.env` for a direct run.

Start directly from the project root:

~~~powershell
mvn -f backend/pom.xml spring-boot:run
~~~

Open http://localhost:1111. Oracle migrations create `GROWW_TRADER.TRADING_SETTINGS` and `GROWW_TRADER.PAPER_TRADES` on startup. The Oracle schema/user must exist and have table/index privileges.

## Analysis API

POST http://localhost:1111/api/analyze accepts caller-supplied candles with 50-5,000 validated bars. POST /api/backtesting/analyze fetches Groww candles and sends the full normalized history plus original response to AI. POST /api/backtesting/run gets a trend from the first 50 bars, then creates a paper-only replay record from later candles. The Trading AI API must accept market_data as documented in the integration contract.

The UI uses POST /api/analyze/stream for caller-supplied requests. GET /api/ai/status checks API health, while GET /api/ai/reports?kind=analysis and GET /api/ai/reports/{report_id} proxy saved reports.

## Safety and limitations

Groww market data is read-only and Groww order endpoints are not implemented. Backtest fills are simulated paper records only; they never authorize or submit broker orders. The application always returns tradeAuthorized=false.
