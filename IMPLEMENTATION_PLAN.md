# Implementation Plan

## Current milestone: local AI paper MVP
- Java 21 + Spring Boot backend and browser dashboard.
- Spring AI integration to local Ollama model `qwen2.5:7b` enabled by default.
- `/api/analyze` returns a safe `NO_TRADE` fallback when the model is unavailable or data is missing.
- Paper-only simulated trading; no Groww API, live prices, or live orders.

## Next milestones
1. Build and run locally with `mvn clean package`; verify `/api/ai/status` and `/api/analyze`.
2. Add automated tests for risk limits, time windows, AI fallback, and API contracts.
3. Add Oracle persistence and immutable decision/trade audit records.
4. Define licensed market-data and news providers; timestamp all inputs and reject stale/missing data.
5. Build a reproducible backtesting pipeline with realistic fees, slippage, and out-of-sample evaluation.
6. Only after independent review, design a broker integration with reconciliation and explicit human controls.
