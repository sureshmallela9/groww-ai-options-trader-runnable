# Groww historical data and Trading AI contract

## What the integration does

The dashboard can request historical candles from Groww, convert valid completed rows into the AI service’s OHLCV format, and send both the normalized bars and the original Groww response envelope to the configured Trading AI API.

Groww’s Backtesting API supplies historical market data; it does not run a strategy simulation or return backtest P&L. This project uses that data for AI analysis. Paper trade entries remain simulated, and this feature never submits broker orders.

The backend also exposes read-only proxy routes for Groww’s live market data, expiry and contract discovery, user profile, holdings, positions, and instruments CSV. User profile and portfolio responses are not automatically added to AI requests.

## Dashboard use

1. Open the app at http://localhost:1111.
2. In **Trading AI analysis**, choose an exchange, segment, Groww symbol, candle interval, and date/time range.
3. Choose a range that returns at least 50 completed candles and no more than 5,000.
4. Select **Fetch Groww candles + analyze**.

Times in the form are India local market time. The app drops rows with missing or invalid OHLCV fields and the currently unfinished candle. Groww’s original response, including any FNO open-interest values, is preserved in the AI request.

For derivatives, use Groww’s expiry and contracts routes below to discover a valid Groww symbol before requesting candles. Example symbols are `NSE-WIPRO`, `NSE-NIFTY`, and option symbols such as `NSE-NIFTY-30Sep25-24650-CE`.

## Request to this application

### Fetch historical data only

`POST http://localhost:1111/api/market/historical-candles`

~~~json
{
  "exchange": "NSE",
  "segment": "CASH",
  "groww_symbol": "NSE-WIPRO",
  "start_time": "2025-09-24 09:15:00",
  "end_time": "2025-09-26 15:30:00",
  "candle_interval": "5minute"
}
~~~

The application calls Groww `GET /v1/historical/candles` and returns Groww’s success envelope, including `status` and the unmodified `payload`.

### Fetch Groww history and send it to AI

`POST http://localhost:1111/api/backtesting/analyze`

~~~json
{
  "exchange": "NSE",
  "segment": "CASH",
  "groww_symbol": "NSE-WIPRO",
  "start_time": "2025-09-24 09:15:00",
  "end_time": "2025-09-26 15:30:00",
  "candle_interval": "5minute",
  "analysis_prompt": "Analyze the historical trend, supporting evidence, indicators, and risks. Do not produce or submit orders.",
  "knowledge_query": "trend and volume confirmation"
}
~~~

Optional text limits match the Trading AI API: `analysis_prompt` up to 1,500 characters and `knowledge_query` up to 500. The response contains Groww source metadata and the AI response. The full candle payload is sent upstream but is not duplicated in this response.

## Exact request sent to the Trading AI API

The backend posts to `POST {TRADING_AI_ANALYSIS_URL}/analyze`. The existing core fields remain `symbol`, `interval`, and `bars`. The new `market_data` object carries the Groww request and full, original Groww response envelope.

~~~json
{
  "symbol": "NSE-WIPRO",
  "interval": "5m",
  "analysis_prompt": "Analyze the historical trend, supporting evidence, indicators, and risks. Do not produce or submit orders.",
  "knowledge_query": "trend and volume confirmation",
  "bars": [
    {
      "timestamp": "2025-09-24T10:30:00+05:30",
      "open": 245.95,
      "high": 246.15,
      "low": 245.05,
      "close": 245.6,
      "volume": 735060
    }
  ],
  "market_data": {
    "provider": "GROWW",
    "endpoint": "/v1/historical/candles",
    "request": {
      "exchange": "NSE",
      "segment": "CASH",
      "groww_symbol": "NSE-WIPRO",
      "start_time": "2025-09-24 09:15:00",
      "end_time": "2025-09-26 15:30:00",
      "candle_interval": "5minute"
    },
    "response": {
      "status": "SUCCESS",
      "payload": {
        "candles": [
          ["2025-09-24T10:30:00", 245.95, 246.15, 245.05, 245.6, 735060, null]
        ],
        "closing_price": 245.4,
        "start_time": "2025-09-24 10:30:00",
        "end_time": "2025-09-24 12:00:00",
        "interval_in_minutes": 5
      }
    }
  }
}
~~~

The example shows one row to make the candle shape readable. A real request has **50–5,000** entries in `bars`; the `market_data.response.payload.candles` array is the complete Groww response. FNO candle rows can include a seventh open-interest value; it remains available in the raw response even though the core `bars` schema is OHLCV.

Timestamps in the normalized `bars` are emitted with the India offset (`+05:30`). Groww’s timezone-free candle timestamps are interpreted as India local market time. The backend sorts valid bars chronologically and filters duplicate timestamps, missing values, invalid OHLC relationships, and candles whose end time is still in the future.

For `1week` and `1month`, the AI `interval` is sent as `7d` and `30d`; the exact Groww interval remains in `market_data.request.candle_interval`. The AI service should use that metadata when it needs to distinguish weekly from monthly bars.

### Required Trading AI API change

The previous AI request contract rejected unknown top-level fields. Update the AI API request model to accept the optional object:

- `market_data.provider`
- `market_data.endpoint`
- `market_data.request`
- `market_data.response` (the raw Groww `status/payload` envelope)

Continue validating and analyzing `bars` as 50–5,000 completed OHLCV bars. Do not require the AI service to rebuild bars from the raw Groww rows. This app forwards the normalized bars for the existing indicator pipeline and the raw response for additional context such as FNO open interest.

If the AI endpoint still rejects `market_data`, the Groww request succeeds but the analysis response will show the upstream AI validation error. Once the AI API accepts this schema, the dashboard path works without changing the request emitted by this app.

## Groww endpoints exposed by the app

All routes below are read-only and proxy the matching Groww API data. The app obtains the Groww access token internally and never includes credentials in requests sent to the AI service.

| Application route | Groww API operation |
|---|---|
| `GET /api/groww/user/profile` | `GET /v1/user/detail` |
| `GET /api/groww/portfolio/holdings` | `GET /v1/holdings/user` |
| `GET /api/groww/portfolio/positions?segment=FNO` | `GET /v1/positions/user` |
| `GET /api/groww/portfolio/position?trading_symbol=RELIANCE&segment=CASH` | `GET /v1/positions/trading-symbol` |
| `GET /api/groww/instruments.csv` | Download Groww instrument CSV |
| `GET /api/market/quote?exchange=NSE&segment=CASH&trading_symbol=WIPRO` | `GET /v1/live-data/quote` |
| `GET /api/market/ltp?segment=CASH&exchange_symbols=NSE_NIFTY,NSE_RELIANCE` | `GET /v1/live-data/ltp` |
| `GET /api/market/ohlc?segment=CASH&exchange_symbols=NSE_NIFTY,NSE_RELIANCE` | `GET /v1/live-data/ohlc` |
| `GET /api/market/option-chain?underlying=NIFTY&expiry=2026-10-29` | `GET /v1/option-chain/exchange/NSE/underlying/NIFTY` |
| `GET /api/market/greeks?exchange=NSE&underlying=NIFTY&trading_symbol=...&expiry=2026-10-29` | `GET /v1/live-data/greeks/...` |
| `GET /api/market/expiries?exchange=NSE&underlying_symbol=NIFTY&year=2026&month=10` | `GET /v1/historical/expiries` |
| `GET /api/market/contracts?exchange=NSE&underlying_symbol=NIFTY&expiry_date=2026-10-29` | `GET /v1/historical/contracts` |
| `POST /api/market/historical-candles` | `GET /v1/historical/candles` |
| `POST /api/backtesting/analyze` | Fetch candles, then call Trading AI `POST /analyze` |

Groww limits a historical request to 30 days for 1-5 minute bars, 90 days for 10-30 minute bars, and 180 days for hourly, daily, weekly, and monthly bars. The backend rejects ranges beyond these limits before sending the request. Groww permits up to 30 days per request for 1–5 minute bars, 90 days for 10–30 minute bars, and 180 days for hourly through monthly bars. The backend checks these limits before calling Groww. The Groww live-data API supports up to 50 instruments per LTP/OHLC request. The instrument CSV is a public static file; other Groww calls use the authenticated API base URL and the standard `Authorization: Bearer`, `Accept: application/json`, and `X-API-VERSION: 1.0` headers.

## Example PowerShell request

~~~powershell
$body = @{
  exchange = "NSE"
  segment = "CASH"
  groww_symbol = "NSE-WIPRO"
  start_time = "2025-09-24 09:15:00"
  end_time = "2025-09-26 15:30:00"
  candle_interval = "5minute"
  analysis_prompt = "Analyze trend, evidence, and risk from the historical candles."
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "http://localhost:1111/api/backtesting/analyze" -ContentType "application/json" -Body $body
~~~

Groww data availability depends on the user's subscription, instrument, interval, and date range. The integration does not request or test live account data while building or testing the application.

## Official Groww references

- [Backtesting and historical candles](https://groww.in/trade-api/docs/python-sdk/backtesting)
- [Historical data](https://groww.in/trade-api/docs/python-sdk/historical-data)
- [Live data](https://groww.in/trade-api/docs/python-sdk/live-data)
- [Portfolio](https://groww.in/trade-api/docs/python-sdk/portfolio)
- [Instruments](https://groww.in/trade-api/docs/python-sdk/instruments)
- [User profile](https://groww.in/trade-api/docs/python-sdk/user)
- [Exceptions](https://groww.in/trade-api/docs/python-sdk/exceptions)
- [Annexures and candle intervals](https://groww.in/trade-api/docs/python-sdk/annexures)
