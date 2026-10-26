# Architecture and API Guide

This describes the current application. Response values below are examples; live prices and AI text vary.

## What “real mode” means here

Groww read-only market data can feed the dashboard and AI analysis. Groww polling is enabled by default when credentials are configured. Trade execution remains PAPER: paper entries/exits are synthetic, and this application does not call Groww order-create, modify, cancel, or order-status APIs. The REAL DATA UI control describes the data source; it does not enable real orders. The API reports mode PAPER and liveExecutionEnabled false.

A static IP alone does not activate order execution. A Groww order-routing implementation, order-state tracking, and server-side risk controls are not present. Do not put API credentials in source control or chat. Use the Groww API secret, not the Groww account login password. Groww API-key authentication may require daily approval; access tokens expire daily. See [authentication](https://groww.in/trade-api/docs/curl), [live-data APIs](https://groww.in/trade-api/docs/curl/live-data), and [static-IP guidance](https://groww.in/blog/static-ip-api-trading-setup).

## Architecture

~~~mermaid
flowchart LR
    UI[Browser dashboard] -->|REST and SSE| API[Spring Boot :1111]
    API --> Analysis[AnalysisService]
    API --> Paper[TradingService]
    Paper --> DB[(Oracle GROWW_TRADER)]
    Scheduler[Scheduled market refresh] --> Groww[Groww read-only API]
    Groww --> Cache[In-memory quote and option-chain cache]
    Cache --> Analysis
    Analysis -->|Spring AI ChatClient| Ollama[Local Ollama]
    Ollama -->|streamed text| API
    API --> UI
~~~

### Components

- **Browser UI**: static HTML served by Spring Boot. It polls dashboard and cached market overview, streams AI analysis, and invokes paper-only trade actions.
- **Spring Boot REST API**: Java 21 application on port 1111; routes are under /api. Virtual threads are enabled for concurrent blocking I/O. They do not accelerate one Ollama inference.
- **Groww auth/client**: reads credentials only from environment configuration; warms an access token shortly after startup, refreshes it in memory before expiry, and makes authenticated GET requests. No broker order client exists.
- **Market data service**: LTP refresh every 1 second by default; option-chain refresh every 30 seconds after a 5-second initial delay. Results are cached in memory. Groww errors cause a 30-second retry backoff.
- **AI analysis**: Spring AI ChatClient calls local Ollama; default model qwen2.5:7b, temperature 0, max output 120 tokens, keep-alive 30 minutes, timeout 45 seconds. The UI uses server-sent events (SSE).
- **Oracle**: Flyway creates GROWW_TRADER.TRADING_SETTINGS and GROWW_TRADER.PAPER_TRADES. Settings and simulated trades persist. Market snapshots and analysis history are not persisted.
- **Paper trading**: applies weekday/time, daily entry count, daily loss, and profitable-trade-stop checks. The entry is NIFTY-DEMO at synthetic ₹100 premium; exits are synthetic. It is not a signal-driven strategy.

## Groww data used

The scheduled quote poll calls Groww live LTP for CASH symbols NSE_NIFTY and NSE_BANKNIFTY. Cached fields include state, connected, source, liveData, fetchedAt, requestLatencyMs, note, and each quote’s symbol, price, currency.

For the scheduled option chain, the service gets NSE NIFTY expiry dates, chooses the nearest future expiry, then requests that NIFTY chain. It compacts the result to underlying LTP and up to five strikes closest to the underlying. Each strike may include CE/PE symbol, LTP, open interest, volume, and available Greeks: delta, gamma, theta, vega, rho, IV. The scheduled analysis snapshot currently includes this compact NIFTY chain. The direct option-chain API supports NIFTY or BANKNIFTY and returns the requested Groww chain under data.

Credentials are not copied into the model prompt. If Groww is disabled, credentials are missing, or requests fail, the cache reports unavailable/error state and the UI uses no fabricated quote values.

## How analysis works and its limits

POST /api/analyze and POST /api/analyze/stream read the latest cached quote/chain snapshot, serialize it to JSON, and attach it to the Ollama user prompt. The system prompt says to use only that data, not invent missing values, and always set trade_authorized=false. If liveData is false, the prompt requests a NEUTRAL assessment and explanation. The model output is informational; the app always returns status NO_TRADE and tradeAuthorized false.

This code does not calculate technical indicators, read news, fetch historical candles, enforce a response JSON schema, or translate the model response into an order. If Spring AI/Ollama is unavailable or times out, the fallback says no live analysis was produced; it is not an independent indicator strategy.

## Run/configure

### Docker Compose

1. Copy .env.example to .env.
2. Fill Oracle values and Groww API key/secret locally; keep GROWW_API_ENABLED=true. Do not commit .env.
3. Ensure Oracle and Ollama are reachable. The Compose defaults use host.docker.internal for host services.
4. Run docker compose up --build and open http://localhost:1111.

### IntelliJ or direct Maven

Spring Boot does not automatically load .env during a direct IntelliJ/Maven run. Put variables in the IntelliJ Run Configuration or PowerShell process environment:

~~~powershell
$env:DB_URL = 'jdbc:oracle:thin:@//localhost:1521/XEPDB1'
$env:DB_USERNAME = 'GROWW_TRADER'
$env:DB_PASSWORD = 'set-locally'
$env:GROWW_API_ENABLED = 'true'
$env:GROWW_API_KEY = 'set-locally'
$env:GROWW_API_SECRET = 'set-locally'
$env:OLLAMA_BASE_URL = 'http://localhost:11434'
mvn -f backend/pom.xml spring-boot:run
~~~

Use your local values; do not paste them in chat. When Groww is configured and requests succeed, GET /api/groww/status should show CONFIGURED and GET /api/market/overview should show LIVE. The Oracle schema/user must already exist and permit Flyway table/index creation.

## HTTP API reference

Base URL: http://localhost:1111/api. Endpoints currently have no authentication layer; keep the app on a trusted local network and do not expose it publicly. All analysis endpoints have no request body: they use the server’s latest cached snapshot.

### Health and settings

**GET /health**

Request: GET /api/health

Response:
~~~json
{"status":"UP","mode":"PAPER","liveExecutionEnabled":false}
~~~

**GET /dashboard**

Request: GET /api/dashboard

Response:
~~~json
{
  "mode":"PAPER","capitalLimit":10000.00,"riskPerTradePercent":1.00,
  "dailyLossPercent":3.00,"dailyLossLimit":300.00,"maxEntryOrdersPerDay":5,
  "entryOrdersUsed":0,"stopAfterProfitableTrades":3,"profitableTrades":0,
  "netPnl":0,"openPositions":0,"sessionOpen":true,
  "currentTime":"2026-10-26T10:00:00+05:30[Asia/Kolkata]",
  "entryStart":"09:15","entryCutoff":"14:58","squareOffStart":"14:57",
  "trades":[]
}
~~~

**GET /settings**

Request: GET /api/settings

Response:
~~~json
{
  "capitalLimit":10000.00,"riskPerTradePercent":1.00,"dailyLossPercent":3.00,
  "maxEntryOrdersPerDay":5,"stopAfterProfitableTrades":3,
  "entryStart":"09:15","entryCutoff":"14:58","squareOffStart":"14:57",
  "timezone":"Asia/Calcutta","mode":"PAPER"
}
~~~

**PUT /settings**

Request:
~~~http
PUT /api/settings
Content-Type: application/json

{"capitalLimit":10000,"riskPerTradePercent":1,"dailyLossPercent":3,"maxEntryOrdersPerDay":5,"stopAfterProfitableTrades":3}
~~~

Response: same shape as GET /settings with the saved values. Validation: capital > 0; risk > 0 and <= 5%; daily loss > 0 and <= 10%; max entries 1–30; profitable stop 1–20. Invalid settings return HTTP 400.

### AI

**POST /analyze**

No body. Request: POST /api/analyze, Accept: application/json.

Response (sample):
~~~json
{
  "model":"qwen2.5:7b","timestamp":"2026-10-26T10:00:00+05:30","liveData":true,
  "marketData":{
    "state":"LIVE","connected":true,"source":"Groww API","liveData":true,
    "fetchedAt":"2026-10-26T04:30:00Z","requestLatencyMs":120,
    "nifty":{"symbol":"NIFTY","price":25140.25,"currency":"INR"},
    "bankNifty":{"symbol":"BANKNIFTY","price":56220.80,"currency":"INR"},
    "optionChainState":"LIVE","optionChainExpiry":"2026-10-29",
    "optionChain":{"underlyingLtp":25140.25,"strikeCount":100,"nearestStrikes":{}}
  },
  "mode":"SPRING_AI_OLLAMA","status":"NO_TRADE",
  "analysis":"Illustrative model text; actual output varies.",
  "tradeAuthorized":false,"totalLatencyMs":3300,"firstContentMs":900,
  "note":"AI uses the latest available Groww market and option-chain snapshot; it cannot authorize orders."
}
~~~

Prices/times/model text above are illustrative. If no live market data, liveData is false. If model integration fails, mode is RULE_BASED_FALLBACK and analysis explains no live analysis was produced.

**POST /analyze/stream**

No body. Request: POST /api/analyze/stream, Accept: text/event-stream.

Response is an SSE stream containing a meta event (mode/model/liveData), one or more delta events (text chunks), then done (totalLatencyMs, firstContentMs, tradeAuthorized=false). On error an error event may be sent. Example:
~~~text
event: meta
data: {"mode":"SPRING_AI_OLLAMA","model":"qwen2.5:7b","liveData":true}

event: delta
data: model-generated text chunk

event: done
data: {"totalLatencyMs":3300,"firstContentMs":900,"tradeAuthorized":false}
~~~

**GET /ai/status**

Request: GET /api/ai/status

Response:
~~~json
{
  "enabled":true,"model":"qwen2.5:7b","provider":"Ollama via Spring AI",
  "ollamaBaseUrl":"http://localhost:11434","timeoutSeconds":45,
  "lastTotalLatencyMs":3300,"lastFirstContentMs":900,
  "lastCompletedAt":"2026-10-26T04:30:00Z","readyForLiveTrading":false
}
~~~

Latency/completion fields are null before the first completed analysis.

### Groww and market data

**GET /groww/status**

Request: GET /api/groww/status

Response (no credential values are returned):
~~~json
{"enabled":true,"credentialsConfigured":true,"state":"CONFIGURED","authentication":"API_KEY_DAILY_APPROVAL","tokenReady":true,"tokenExpiresAt":"2026-10-27T00:30:00Z","lastAuthenticationError":null}
~~~

**GET /market/overview**

Request: GET /api/market/overview

Response sample:
~~~json
{
  "state":"LIVE","connected":true,"source":"Groww API","liveData":true,
  "fetchedAt":"2026-10-26T04:30:00Z","requestLatencyMs":120,
  "nifty":{"symbol":"NIFTY","price":25140.25,"currency":"INR"},
  "bankNifty":{"symbol":"BANKNIFTY","price":56220.80,"currency":"INR"},
  "note":"Read-only market data. Broker order placement is disabled."
}
~~~

If disabled/unavailable, state describes the condition, liveData/connected are false, and quotes are null. The UI does not substitute demo index prices.

**GET /market/option-chain?underlying=NIFTY&expiry=YYYY-MM-DD**

Supported underlyings: NIFTY and BANKNIFTY. Expiry is required.

Request: GET /api/market/option-chain?underlying=NIFTY&expiry=2026-10-29

Response sample:
~~~json
{
  "state":"LIVE","source":"Groww API","underlying":"NIFTY",
  "expiry":"2026-10-29","fetchedAt":"2026-10-26T04:30:00Z",
  "data":{
    "underlying_ltp":25140.25,
    "strikes":{
      "25100":{
        "CE":{"trading_symbol":"EXAMPLE","ltp":120.5,"open_interest":12345,"volume":1000},
        "PE":{"trading_symbol":"EXAMPLE","ltp":95.2,"open_interest":23456,"volume":2000}
      }
    }
  }
}
~~~

The data object is Groww’s raw chain response. Sample symbols/prices are placeholders.

### Paper journal and actions

**GET /trades**

Request: GET /api/trades

Response when empty: ~~~json [] ~~~

Otherwise returns an array of paper trade records.

**GET /trades/{id}**

Request: GET /api/trades/PAPER-ABC12345

Response shape:
~~~json
{
  "id":"PAPER-ABC12345","symbol":"NIFTY-DEMO","optionType":"CE","quantity":1,
  "entryPremium":100.00,"exitPremium":null,"grossPnl":null,"charges":null,
  "netPnl":null,"status":"OPEN",
  "entryReason":"DEMO ONLY: synthetic setup. Not a real market signal.",
  "exitReason":null,"entryTime":"2026-10-26T10:00:00+05:30","exitTime":null,
  "evidence":["Synthetic data only","No broker order sent","Demo rules passed"],
  "timeline":["paper entry created; no broker order sent"]
}
~~~

Unknown ID returns HTTP 404.

**POST /paper/execute**

No body. Request: POST /api/paper/execute.

Response: a trade object as above with generated PAPER-... ID. It creates a simulated NIFTY-DEMO entry at ₹100, quantity 1. It is rejected on weekends, outside 09:15–14:58 Asia/Kolkata, at the daily entry limit, after the configured profitable-trade stop, or at/below the daily loss threshold. Guard rejection returns HTTP 409.

**POST /paper/close/{id}**

No body. Request: POST /api/paper/close/PAPER-ABC12345.

Response: updated trade object with CLOSED status, exitPremium, grossPnl, charges, netPnl, exitReason, exitTime, and timeline. Exit premium and P&L are synthetic and not based on Groww quotes.

**POST /paper/square-off**

No body. Request: POST /api/paper/square-off.

Response:
~~~json
{"closed":[]}
~~~

When paper positions are open, closed contains the updated trade objects.

### Common error responses

Invalid settings/underlying: HTTP 400, response shape:
~~~json
{"error":"Invalid settings or unsupported underlying."}
~~~

Paper session/risk guard: HTTP 409:
~~~json
{"error":"Outside entry window 09:15–14:58 IST."}
~~~

Missing paper trade: HTTP 404:
~~~json
{"error":"Trade not found"}
~~~

### curl quick checks

~~~bash
curl --location 'http://localhost:1111/api/health'
curl --location 'http://localhost:1111/api/groww/status'
curl --location 'http://localhost:1111/api/market/overview'
curl --location --request POST 'http://localhost:1111/api/analyze' --header 'Accept: application/json'
curl --location --request POST 'http://localhost:1111/api/analyze/stream' --header 'Accept: text/event-stream'
~~~

The analysis requests accept no body. They use the server-side cache. These examples do not create or change trades.