# Groww AI Options Trader — Oracle, Groww market data, and Ollama

A Java 21 / Spring Boot paper-trading application. When configured, it reads live Groww index quotes and a compact NIFTY option-chain snapshot, sends that data to local Ollama for a streamed analysis, and stores paper trades and risk settings in Oracle.

> **Safety:** The application is hard-coded to paper mode. Paper entries and exits are simulated. No Groww order-placement API is called; `LIVE_TRADING_ENABLED` is forced to `false`. AI output cannot authorize an order.

## Requirements

- JDK 21 and Maven 3.9+
- Oracle database and a schema user with permissions to run Flyway migrations
- Ollama running locally with the configured model (default `qwen2.5:7b`)
- Groww Trading API access with live-data entitlement for market quotes and option-chain requests

Groww API-key/secret authentication requires daily approval from the Groww API keys page. Store the API **secret** as `GROWW_API_SECRET`; this is not your Groww account login password. Groww's current docs list the API token and read-only market-data endpoints and their rate limits: [authentication and rate limits](https://groww.in/trade-api/docs/curl), [live data and option chain](https://groww.in/trade-api/docs/curl/live-data). The app polls two index prices once per second (60 requests/minute), below the documented 300/minute live-data limit.

## Configure

1. Copy `.env.example` to `.env` and replace the Oracle password and Groww API key/secret locally. Never commit `.env` or paste credentials into chat.
2. Confirm the Groww API key is enabled and its daily approval is complete. Keep `GROWW_API_ENABLED=false` until credentials and entitlement are ready; change it to `true` to enable read-only market data.
3. For direct Windows execution, keep `OLLAMA_BASE_URL=http://localhost:11434` and point `DB_URL` to your Oracle listener. For Docker Compose, the compose file maps Oracle and Ollama through `host.docker.internal` by default.
4. Run from the project root:

```powershell
cd backend
mvn clean package
mvn spring-boot:run
```

Open http://localhost:1111. Oracle migrations run at startup and create `GROWW_TRADER.TRADING_SETTINGS` and `GROWW_TRADER.PAPER_TRADES`. The configured Oracle user/schema must exist and allow table/index creation. Paper trades and risk settings persist across restarts; market snapshots are held in memory.

## What the application does

- Fetches `NIFTY` and `BANKNIFTY` LTPs from Groww every second when enabled; the UI no longer displays fabricated market prices.
- Reads the nearest upcoming NIFTY expiry and option-chain data when analysis is requested. It sends the underlying price and the nearest five strikes/Greeks to Ollama.
- Streams Ollama text to the UI. The analysis panel shows time to first content and total response time; `GET /api/ai/status` reports the last measurements.
- Uses Java 21 virtual threads for request concurrency (`spring.threads.virtual.enabled=true`). This helps the server handle concurrent I/O; it does not make a single Ollama inference itself faster.
- Stores paper trades and risk settings in Oracle. Paper fills remain synthetic and are not Groww orders.
- Uses a 30-minute Ollama model keep-alive, concise output limit, and 45-second response timeout. If Groww data is absent, the AI is instructed to return a neutral no-trade analysis.

## Useful endpoints

- `GET /api/health`
- `GET /api/groww/status`
- `GET /api/market/overview`
- `GET /api/market/option-chain?underlying=NIFTY&expiry=YYYY-MM-DD`
- `GET /api/ai/status`
- `POST /api/analyze` (JSON response) or `POST /api/analyze/stream` (SSE)
- `GET /api/dashboard`, `GET /api/trades`, `GET /api/settings`
- `POST /api/paper/execute`, `POST /api/paper/close/{id}`, `POST /api/paper/square-off`

When Groww credentials are missing, rejected, or not entitled for live data, `/api/market/overview` returns an explicit status and the dashboard shows no prices. It does not substitute demo numbers.

## Ollama latency

A short local `qwen2.5:7b` request measured during this implementation took about 12.2 seconds cold and 3.3 seconds to first content when warm. That is one local measurement, not a performance guarantee. The app keeps the model loaded between requests and records first-content/total time so you can compare real analysis calls on your machine. Virtual threads increase request concurrency, not model token speed; GPU memory, model loading, context size, and output length drive inference latency.

## Static IP and live orders

Groww states that API orders must originate from a registered static IP. Since your static IP is not ready yet, order placement is not implemented or enabled. After the IP is registered, this project still requires a separate safety review before any broker order path is added. Read-only data and paper mode can be evaluated independently.