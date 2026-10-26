# Groww AI Options Trader — Spring AI + Ollama Paper MVP

A Java 21 / Spring Boot starter with a browser dashboard, configurable paper-trading limits, simulated trade journal, and Spring AI integration to your existing local Ollama model (`qwen2.5:7b`). The app defaults to local Ollama enabled. If Ollama is unavailable, the analysis endpoint safely falls back to `NO_TRADE`.

> **Safety and scope:** This is a paper-trading demo. It does not connect to Groww, use live market prices/options chains/news, or place broker orders. Trades use synthetic values. AI output is not a trading signal. Settings and trades are in-memory and reset on restart.

## Prerequisites (Windows 11)

- JDK 21
- Maven 3.9+
- IntelliJ IDEA (or VS Code)
- Ollama already installed and running with `qwen2.5:7b` downloaded

Verify Ollama first in PowerShell:

```powershell
ollama list
ollama ps
curl.exe http://localhost:11434/api/tags
```

If your installed model has a different name, set `TRADING_MODEL_NAME` accordingly. Do not download another model if `qwen2.5:7b` is already listed.

## Run from IntelliJ IDEA

1. Extract this ZIP to a normal writable folder, for example `C:\Projects\groww-ai-options-trader`.
2. IntelliJ: **File → Open** and choose the extracted project root.
3. Confirm **Project SDK = Java 21** and allow Maven to import `backend/pom.xml`.
4. Open the IntelliJ Terminal and run:

```powershell
cd backend
mvn clean package
mvn spring-boot:run
```

Or open `backend/src/main/java/com/optionstrader/OptionsTraderApplication.java` and run its `main` method.

The dashboard is served by Spring Boot at http://localhost:8080.

## Run with PowerShell environment overrides

The default configuration already uses `http://localhost:11434` and `qwen2.5:7b`. To override them for the current terminal session:

```powershell
$env:OLLAMA_ENABLED = "true"
$env:OLLAMA_BASE_URL = "http://localhost:11434"
$env:TRADING_MODEL_NAME = "qwen2.5:7b"
cd backend
mvn spring-boot:run
```

## API checks (cURL)

Check application health:

```powershell
curl.exe -i http://localhost:8080/api/health
```

Check AI configuration:

```powershell
curl.exe -i http://localhost:8080/api/ai/status
```

Call Spring AI → Ollama through the Java backend (not directly to Ollama):

```powershell
curl.exe -X POST "http://localhost:8080/api/analyze" `
  -H "Content-Type: application/json" `
  -d "{}"
```

Check the dashboard API:

```powershell
curl.exe http://localhost:8080/api/dashboard
```

Ollama itself can be tested directly with cURL:

```powershell
curl.exe -X POST "http://localhost:11434/api/chat" `
  -H "Content-Type: application/json" `
  -d '{"model":"qwen2.5:7b","messages":[{"role":"user","content":"Reply with only READY."}],"stream":false,"options":{"num_predict":20,"temperature":0}}'
```

## API endpoints

- `GET /api/health`
- `GET /api/dashboard`
- `GET /api/settings` and `PUT /api/settings`
- `GET /api/ai/status`
- `POST /api/analyze`
- `GET /api/trades` and `GET /api/trades/{id}`
- `POST /api/paper/execute`
- `POST /api/paper/close/{id}`
- `POST /api/paper/square-off`

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `OLLAMA_ENABLED` | `true` | Enable Spring AI Ollama analysis |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Local Ollama API |
| `TRADING_MODEL_NAME` | `qwen2.5:7b` | Installed Ollama model name |

## Paper-trading defaults

- Capital limit: ₹10,000, editable from dashboard
- Maximum 5 entry orders per day; paper exits do not count as entries
- Stop new entries after 3 completed profitable trades
- Entry window: 09:15–14:58 IST on weekdays; this demo does not use the official NSE holiday calendar
- Square-off starts at 14:57 IST; the button closes simulated paper positions only
- AI must never authorize orders; `tradeAuthorized` is always false in analysis output

## Troubleshooting

**`ollama list` does not show `qwen2.5:7b`:** Set `TRADING_MODEL_NAME` to the exact installed model name or download the model separately.

**Ollama works directly but `/api/analyze` falls back:** Confirm Ollama is running, `OLLAMA_BASE_URL` points to the host from the Java process, and inspect the Spring Boot console for dependency/network errors. If running the Java app directly on Windows, use `http://localhost:11434`. In Docker, the compose file uses `http://host.docker.internal:11434`.

**Maven dependency resolution fails:** Verify internet access and that `backend/pom.xml` imported the Spring AI BOM version used by the project. Share the first Maven error rather than changing versions randomly.

## Verification status

This ZIP was prepared from source, but it was **not compiled or integration-tested in this build environment** because Maven is unavailable here. Run `mvn clean package` on your Windows machine first. The Spring AI/Ollama call will be confirmed when `POST /api/analyze` returns mode `SPRING_AI_OLLAMA`; if Ollama cannot be reached, the endpoint intentionally returns `RULE_BASED_FALLBACK`.

Before any live use, add durable Oracle persistence, real licensed market data, validated backtesting with fees/slippage, exchange-calendar handling, order reconciliation, security controls, and a separately reviewed broker integration. Live order placement is not included.
