# Groww AI Options Trader

Java 21 / Spring Boot application that reads Groww market data when configured, sends the latest cached quote and NIFTY option-chain snapshot to local Ollama, and stores simulated paper trades and risk settings in Oracle.

## Current operation mode

- **Real market data:** Read-only Groww data polling is enabled by default when credentials are configured. NIFTY and BANKNIFTY quotes are polled once per second; a compact NIFTY option-chain snapshot is refreshed about every 30 seconds.
- **Paper execution:** Entries and exits remain synthetic. The application does not submit, modify, cancel, or track Groww orders. The API reports mode PAPER and liveExecutionEnabled false.
- **AI:** Spring AI sends the cached market snapshot to local Ollama and streams text to the dashboard. The model output is informational; the app always reports NO_TRADE and tradeAuthorized false.

See [Architecture and API Guide](docs/ARCHITECTURE_AND_API.md) for the component diagram, data collected from Groww, analysis behavior, every API route, and sample requests/responses.

## Requirements

- JDK 21 and Maven 3.9+
- Oracle schema/user and database access for Flyway
- Ollama with qwen2.5:7b available locally (or set TRADING_MODEL_NAME)
- Groww API credentials and live-data entitlement

Use the Groww API secret, not the Groww account login password. Groww API-key authentication may require daily approval; access tokens expire daily. Read [Groww authentication and rate limits](https://groww.in/trade-api/docs/curl) and [Groww live-data docs](https://groww.in/trade-api/docs/curl/live-data).

## Configure and run

For Docker Compose, copy .env.example to .env, set Oracle and Groww credentials in that local file, then run:

~~~powershell
docker compose up --build
~~~

The .env file is not committed. For direct Maven or IntelliJ runs, set DB_URL, DB_USERNAME, DB_PASSWORD, GROWW_API_ENABLED=true, GROWW_API_KEY, GROWW_API_SECRET, and OLLAMA_BASE_URL in the process environment or IntelliJ Run Configuration. Spring Boot does not automatically read .env for a direct run.

Start directly from the project root:

~~~powershell
mvn -f backend/pom.xml spring-boot:run
~~~

Open http://localhost:1111. Oracle migrations create GROWW_TRADER.TRADING_SETTINGS and GROWW_TRADER.PAPER_TRADES on startup. The Oracle schema/user must exist and have table/index privileges.

## Safety and limitations

Groww market data is read-only. Groww order endpoints are not implemented, and a static IP alone cannot make this application place orders. Paper entries use NIFTY-DEMO at a synthetic premium; exits also use synthetic values. The analysis is not a validated trading strategy and does not authorize an order. Keep the API restricted to a trusted local network; no API authentication layer is currently configured.

The local qwen2.5:7b test during implementation took about 12.2 seconds cold and 3.3 seconds warm to first content on that machine. This is a single measurement, not a latency guarantee. Virtual threads improve concurrent I/O handling, not individual model inference speed.