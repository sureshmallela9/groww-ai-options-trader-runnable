# Groww AI Options Trader

Java 21 / Spring Boot application that reads Groww market data when configured and stores simulated paper trades and risk settings in Oracle. Market-data integration is read-only; live orders are not implemented.

## Current operation mode

- **Real market data:** Read-only Groww data polling is enabled by default when credentials are configured. NIFTY and BANKNIFTY quotes are polled once per second; a compact NIFTY option-chain snapshot is refreshed about every 30 seconds.
- **Paper execution:** Entries and exits remain synthetic. The application does not submit, modify, cancel, or track Groww orders. It always reports mode PAPER and liveExecutionEnabled false.
- **Analysis:** The dashboard can fetch completed historical candles through Groww and send normalized OHLCV bars plus the original Groww response to your local Trading AI API at TRADING_AI_ANALYSIS_URL (default http://127.0.0.1:8000). Analysis never authorizes or triggers a trade.

See [Architecture and API Guide](docs/ARCHITECTURE_AND_API.md) for the component diagram, API contracts, sample requests/responses, and run instructions.

## Historical candles and AI analysis

The app fetches real historical candles from Groww on demand for the backtesting workflow. Groww data is passed to Trading AI as normalized completed OHLCV bars and the original response envelope (including FNO open interest where returned). The AI service must accept the optional top-level market_data object. This workflow analyzes historical data; it does not simulate fills or compute strategy backtest P&L.

See [Groww backtesting and AI request contract](docs/GROWW_BACKTESTING_AI_CONTRACT.md) for the exact request bodies, API routes, date-range caps, and PowerShell example.

## Requirements

- JDK 21 and Maven 3.9+
- Oracle schema/user and database access for Flyway
- Your Trading AI API running locally, with GET /health, POST /analyze, report endpoints, and support for the optional market_data object described in the integration contract
- Groww API credentials and historical-data entitlement for the on-demand analysis workflow.

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

POST http://localhost:1111/api/analyze accepts caller-supplied candles with 50-5,000 validated bars. The optional market_data object can carry source data such as Groww's original historical response. POST /api/backtesting/analyze fetches Groww candles and sends normalized OHLCV bars plus the original response envelope to AI. The Trading AI API must accept market_data as documented in the integration contract.

The UI uses POST /api/analyze/stream for caller-supplied requests. GET /api/ai/status checks API health, while GET /api/ai/reports?kind=analysis and GET /api/ai/reports/{report_id} proxy saved reports.

## Safety and limitations

Groww market data is read-only. Groww order endpoints are not implemented. Paper entries and exits remain synthetic. Groww history is used for analysis only and is not an order or a simulated backtest fill. The application always returns tradeAuthorized=false.
