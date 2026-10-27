# Implementation Plan

## Current milestone: local Trading AI integration for the paper MVP
- Java 21 + Spring Boot backend and browser dashboard.
- Analysis uses the configured local model API at TRADING_AI_ANALYSIS_URL (default http://127.0.0.1:8000).
- The adapter validates and forwards only actual completed OHLCV data, and preserves the structured report fields and report ID.
- This repository currently does not collect completed OHLCV candles, so the UI reports `CANDLES_REQUIRED` until a real candle source is added.
- Paper-only simulated trading; Groww market reads stay read-only and no live orders are called.

## Next milestones
1. Add a genuine completed-candle provider that preserves interval, timestamps, OHLC and volume without fabricating missing fields.
2. Run the application against Oracle and verify the local Trading AI API health, analysis and saved-report routes with mocked tests first.
3. Add automated tests for risk limits, time windows, persistence, and API contracts.
4. Add immutable decision/trade audit records and reject stale market data.
5. Build a reproducible backtesting pipeline with realistic fees, slippage, and out-of-sample evaluation.
6. Only after independent review and explicit user authorization, design a broker-order integration with reconciliation and explicit human controls.
