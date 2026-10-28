# Paper platform API and setup

The options app remains at http://localhost:1111 and Trading AI at http://127.0.0.1:8000. Restart both after updating. The new UI is http://localhost:1111/paper-workspace.html.

## Configure the connection

In both run configurations, set INTERNAL_API_TOKEN to the same long random secret. Set GROWW_TRADER_URL=http://127.0.0.1:1111 in Python and TRADING_AI_ANALYSIS_URL=http://127.0.0.1:8000 in Java.

In Java only, set TRADING_ADMIN_TOKEN to a different random secret. Internal service credentials can activate the kill switch, but cannot reset it or change risk policy. Those operations also require the administrator token.

Existing report/database and Groww credentials remain in their respective application configurations. Broker credentials are not sent to Python. Do not commit real secrets or put them in frontend source. The workspace asks for the internal token and retains it in memory for that page.

Copy config/session-calendar.example.json to config/session-calendar.json in the options project. Populate actual verified exchange session dates, for example a YYYY-MM-DD object with open, close, entryCutoff and squareOff HH:mm values. The source URL and verified flag must reflect a reviewed calendar. An empty or missing date blocks entry. For packaged/Docker runs mount the real file and set TRADING_SESSION_CALENDAR to its path.

Optional: copy config/news_sources.example.json to config/news_sources.json in Trading AI and configure a permitted JSON source. No provider is enabled by the example. The expected provider output is an article array or an object with an articles array. Each article needs headline and timezone-aware publishedAt; optional fields are text, relatedSymbols, sourceUrl and verifiedFact.

## PostgreSQL in the Java app

Select SPRING_PROFILES_ACTIVE=postgres and configure DB_URL=jdbc:postgresql://localhost:5432/groww_trader plus the Java app's own DB_USERNAME and DB_PASSWORD. Provision that database/role first. Flyway then uses db/postgresql and the groww_trader schema. This does not migrate old Oracle rows. The original Oracle setup still uses the existing default profile.

Python continues to use TRADING_AI_DATABASE_URL for its own database. Do not use a single shared application schema for both services.

The Java project includes both the PostgreSQL JDBC driver and the separate Flyway PostgreSQL plugin required for migrations. This follows the [Flyway PostgreSQL documentation](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).

## Authentication and errors

Every versioned endpoint except /api/v1/health requires:

    Authorization: Bearer <internal-token>
    Content-Type: application/json
    X-Correlation-ID: <optional request identifier>

Responses return X-Correlation-ID. An unconfigured token returns 503; incorrect authorization returns 401. Invalid data returns 422, idempotency conflicts 409, unavailable upstream data 502/503, busy scanners 503 with Retry-After. Legacy local endpoints remain for the existing UI.

## Trading AI endpoints

| Method | Route | Behavior |
|---|---|---|
| GET | /api/v1/health | Minimal version/mode/auth-configuration status |
| GET / POST | /api/v1/universe /universe/refresh | Read or refresh official current NIFTY 50 snapshot |
| POST | /api/v1/news/analyze | Analyze and deduplicate supplied sourced articles |
| POST | /api/v1/news/collect | Collect only configured permitted JSON feeds |
| POST | /api/v1/analysis/market | Assess NIFTY, sector, breadth and scheduled-event evidence |
| POST | /api/v1/analysis/stock | Add past-only technical features to normalized candles |
| POST | /api/v1/candidates/scan | Start a bounded scan; returns 202 with jobId |
| GET | /api/v1/jobs/{jobId} | QUEUED, COMPLETED or FAILED scan result |
| POST | /api/v1/decisions/evaluate | Produce a structured paper proposal or NO_TRADE |
| GET | /api/v1/decisions/{decisionId} | Retrieve immutable decision evidence by its identifier |
| POST | /api/v1/positions/events | Deduplicated position assessment by eventId |
| GET / POST | /api/v1/events/outbox /events/{id}/ack | Read and acknowledge durable versioned events |

## Decision request

Send symbol, exchange=NSE, interval=5m, mode=PAPER, market and optional policy. market contains niftyBars, sectorBars, advancers, decliners, observedAt and scheduledEventRisk. Each bar retains the timestamp/open/high/low/close/volume shape. A minimum of 100 completed candles is required for a strategy proposal.

If bars/quote are omitted, Python requests them through the normalized Java gateway. Optional supplied quote contains lastPrice, bid, ask, timestamp, sessionOpen, calendarVerified and instrumentVerified. No entry is proposed from missing confirmation.

Supply Idempotency-Key to replay a decision request exactly. Reusing it with a different body returns 409.

Response fields include decisionId, decisionVersion, symbol, exchange, instrumentType, intentType, action, strategy, strategyVersion, mode, signalConfidence, scoreKind, referencePrice, stopLoss, targetPrice, requestedQuantity, riskRewardRatio, estimatedRoundTripCostPerShare, createdAt, validUntil, quoteTimestamp, candleTimestamp, reasons, componentScores, market, analysis, newsIds, newsContext, knowledgeContext and ml status. newsContext retains the actual source-linked headlines and timestamps used at decision time.

BUY is a proposal. executionAllowed is always false in Trading AI and requiresIndependentRiskApproval is true. NO_TRADE has zero requested quantity. Scores are uncalibrated rule scores.

## Java endpoints

| Method | Route | Behavior |
|---|---|---|
| GET | /api/v1/health | Minimal service health |
| GET | /api/v1/market-data/quote?symbol=...&exchange=NSE | Normalized verified quote; broker timestamp may be null |
| POST | /api/v1/market-data/candles/query | symbol/exchange/interval/lookbackCandles request, 100–500 completed bars |
| GET | /api/v1/account/funds /account/positions | Read-only account data; not forwarded to Python |
| POST | /api/v1/research/market /research/evaluate /research/scan | UI proxy to Python research |
| GET | /api/v1/research/jobs/{id} | UI scan-job proxy |
| GET / POST | /api/v1/research/universe /research/universe/refresh | UI universe controls |
| POST | /api/v1/research/news/analyze /research/news/collect | UI news controls |
| POST | /api/v1/trade-intents | Independently validate and simulate a confirmed paper proposal |
| GET | /api/v1/trade-intents /trade-intents/{id} /orders/{id} | Paper state, fills and evidence |
| GET | /api/v1/paper/positions | Open or unresolved paper positions |
| POST | /api/v1/paper/positions/{id}/refresh /close | Refresh or request a paper exit |
| GET | /api/v1/risk/status | Policy, kill switch, calendar, open positions and pending events |
| PUT | /api/v1/risk/policy | Administrator-only policy update |
| POST | /api/v1/risk/kill-switch | Enable, or administrator-only reset |

## Paper intent command

    POST /api/v1/trade-intents
    Idempotency-Key: DEC-<decision identifier>
    Authorization: Bearer <internal-token>

    {
      "decisionId": "DEC-<identifier returned by Trading AI>",
      "mode": "PAPER",
      "quantity": 1,
      "confirmed": true
    }

Omit quantity to use the proposal's whole-share quantity. Larger quantities are rejected. Java retrieves the saved AI decision itself and obtains a new broker quote. It never trusts prices sent in this command. Replays return the original intent; changed terms with the same key or decision ID return 409.

A rejected intent contains riskRejections. An accepted intent records the paper lifecycle, filled quantity, current position, entry/exit reasons, decision and quote timestamps. A kill-switch or data outage may leave EXIT_PENDING with an incident; that does not mean a fill occurred.

## Verification

Run in the Trading AI project:

    .\.venv\Scripts\python.exe -m unittest discover -s tests -v
    .\.venv\Scripts\python.exe tools\verify_options_bridge.py

The verifier starts a temporary local API, generates a temporary token and uses isolated SQLite/H2 storage. It exercises real Java-to-Python HTTP calls and mocked broker data. It never submits Groww orders or uses your account/database credentials.

Run browser checks in the options project:

    node --test tests/trading-ai-browser.test.cjs
    node --check backend/src/main/resources/static/paper-workspace.js

These are regression/contract checks, not an assessment of strategy profitability. Actual PostgreSQL/Oracle migration and authenticated market-data operation must be verified in your configured environment.

Read traffic shares a local broker budget across legacy polling and the new gateway. Busy reads can return 429 with Retry-After. The configured one-second risk task is a scheduling interval, not a guarantee that a fresh executable quote is always available at that frequency.
