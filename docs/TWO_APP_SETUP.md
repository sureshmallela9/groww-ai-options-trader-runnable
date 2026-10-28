# Versioned paper workflow

See [PAPER_PLATFORM_API.md](PAPER_PLATFORM_API.md) for the new authenticated paper workspace and PostgreSQL profile. The original analysis bridge below remains supported.

# Run the options app with Trading AI

The Java/Spring options app runs at http://localhost:1111. The Python/FastAPI service runs at http://127.0.0.1:8000. Start both apps.

## Trading AI in PyCharm

Use the trading-ai virtual environment and project working directory. Run module uvicorn with parameters:

    trading_ai.api:app --host 127.0.0.1 --port 8000

Keep your existing report database environment settings. Restart the Python process after this update. GET /health should report api_version 0.5.0 and capabilities.market_data_context true.

## Options app in IntelliJ

Set these values in the Java run configuration:

    TRADING_AI_ENABLED=true
    TRADING_AI_ANALYSIS_URL=http://127.0.0.1:8000
    TRADING_AI_TIMEOUT=20s
    TRADING_AI_MAX_IN_FLIGHT=12

Keep your existing Oracle and Groww configuration. Restart the Java application and refresh http://localhost:1111. For Docker callers, use a service address reachable from the container as described in the options project's README.

## Use the connection

The Trading AI section checks API compatibility and report database readiness. Select a Groww instrument, interval and date range, then choose Analyze full history only. The options backend obtains candles, normalizes OHLCV bars and passes the original response as market_data.

You can also paste a JSON request with 50–5,000 valid completed bars. Responses display indicators, reasons, risks, source excerpts and provider provenance. Saved reports reopen the same analysis. Existing paper backtesting uses the same API connection.

Indicators use normalized bars. The raw provider envelope, including FNO open interest when supplied, is retained in report details for provenance; this update does not add an OI indicator. The immediate response and report list contain market_data_summary instead of duplicating raw candles.

UPDATE AI API means the Python process needs the 0.5.0 contract. API NOT READY means a reachable service or report database is not ready. Validation errors show the offending field. Busy responses follow the existing bounded retry policy; ambiguous timeouts are not automatically repeated.

## Verify without account access

From the Trading AI folder:

    .\.venv\Scripts\python.exe -m unittest discover -s tests
    .\.venv\Scripts\python.exe tools\verify_options_bridge.py

The second command starts a temporary API with isolated SQLite storage and runs the relevant Java tests, including a real Java-to-Python HTTP round trip. It uses synthetic candles and mocked Groww calls; it does not use your configured PostgreSQL/Oracle databases. Java 21, Maven and the Python project dependencies are required.

From the options app folder, verify browser streaming and saved-report rendering with:

    node --test tests/trading-ai-browser.test.cjs
