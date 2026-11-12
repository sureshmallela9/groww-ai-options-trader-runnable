# GPU batches and Groww Trader storage

Enabled 4 November 2026 at the user's request. The 5 GB quota is applied. The RTX 5050 CUDA self-test and real-device regression tests now pass. The previous Windows block was resolved by the user; the assistant did not change Windows security settings.

## Groww Trader database allowance

The Oracle `GROWW_TRADER` schema's `USERS` tablespace quota is **5G = 5,368,709,120 bytes**. This replaces the earlier 256 MB allowance; it changes only the schema's storage limit. It does not create a 5 GB file immediately or change privileges, credentials, risk settings or existing records. Allocated storage at the change was 267,780,096 bytes. The local C: drive had about 649 GB free.

Repeated updates of large JSON/CLOB records contributed to storage pressure. The larger allowance restores capacity; it does not remove historical records or implement a retention/purge policy. Allocation and logical payload size differ, so quota monitoring should use `USER_TS_QUOTAS` and `USER_SEGMENTS`, not only the size of the JSON text. A failed write should not be treated as a successful trade or silently dropped.

## Actual GPU workload

The laptop exposes an **NVIDIA GeForce RTX 5050 Laptop GPU**, approximately 8 GB of VRAM, driver 592.82, with reported CUDA support 13.1. Trading AI now has an optional CuPy CUDA implementation of the existing EMA9/20/50 and MACD12/26/signal9 calculations for batches of candle histories. This is arithmetic acceleration of the existing strategy, not a new prediction model.

The 50-stock selection route can send the momentum calculation to CUDA when the batch contains at least 8,192 closes. Ragged histories are supported, with maximum 64 series and 5,000 bars per series. The GPU receives each series in chronological order. The existing analysis code still checks completed candles, freshness, history, market/sector context, liquidity and risk. GPU output cannot authorize an order by itself.

The kernel uses double precision, the same first-close EMA seed and update order as the CPU code, and disables fused multiply-add. Startup runs a numerical parity self-test. Actual CUDA tests compare flat, rising, falling, one-bar and long histories, and compare complete stock selections and rejection behavior against CPU results.

Small requests use CPU to avoid transfer/launch overhead. Source chat, internet requests, databases, most indicator/context work, historical replay and the scikit-learn training baseline continue to use CPU. No GPU LLM or calibrated predictor was installed. Java's independent paper risk gates and order simulation remain unchanged.

## Installation and settings

Only Trading AI's virtual environment needs the optional GPU packages:

```powershell
python -m pip install -e ".[gpu]"
```

The optional extra uses official `cupy-cuda13x[ctk]` 14.2.x and a matching CUDA Toolkit 13.1.x component set from PyPI. The existing NVIDIA driver and Windows graphics settings are retained. CPU-only installations can omit this extra.

| Setting | Default | Meaning |
|---|---|---|
| `TRADING_AI_GPU` | `auto` | Use a successfully tested CUDA device; `cpu` disables it. |
| `TRADING_AI_GPU_MIN_CELLS` | `8192` | Minimum total closes in a batch, supported range 1–320,000. |
| `TRADING_AI_GPU_MEMORY_MB` | `256` | Limit for CuPy's memory pool, supported range 64–1,024 MB. This does not reserve the whole GPU; CUDA context memory can be additional. |

GPU work is serialized by a lock and synchronized before results are returned. Runtime CUDA errors disable that path for the process and continue with CPU calculations. The health response reports this fallback; it never labels an unavailable GPU as active.

## Checking the running app

`GET /health` and `GET /api/v1/health` on Trading AI add a `compute` object with:

- `gpuReady`, `backend`, `device` and `selfTestPassed`;
- `scope: BATCH_EMA_MACD_ONLY` and `smallRequestsUseCpu: true`;
- configured threshold and memory-pool limit;
- count, size and compute duration of actual GPU batches;
- a bounded failure reason if CUDA is unavailable.

The Groww workspace's Trading AI indicator says **GPU batches ready** when that health check succeeds. This reports capability; `gpuBatchCount` distinguishes actual batch work from startup readiness. A health request does not run a market scan or start trading. A measured kernel time is not end-to-end order latency or a promise of faster individual API requests.

References: [CuPy installation](https://docs.cupy.dev/en/stable/install.html), [official CuPy CUDA 13 package](https://pypi.org/project/cupy-cuda13x/).

## Validated activation and performance

The earlier Windows Application Control block was resolved by the user. Both CPU/GPU numerical parity tests and complete stock-selection/rejection comparisons pass on the RTX 5050. All 79 AI tests passed with no CUDA skips. The running API reports backend CUDA, gpuReady true and selfTestPassed true.

Warm offline synthetic benchmark (50 stocks × 775 candles): EMA/MACD arithmetic about 15.1 ms CPU versus 1.8 ms GPU, approximately 8.4× for that stage. Full selection originally measured about 595 ms; GPU plus reductions in repeated model serialization, timezone conversion and sector-input construction measured about 472 ms, about 21% less compute time. The optimized full decision output exactly matched its saved pre-optimization SHA-256. These measurements exclude broker, internet and database latency and are not a live order-speed guarantee.

Single-stock 750-candle math was faster on CPU in the benchmark, so the existing minimum-batch threshold remains in place. The GPU memory pool remains capped at 256 MB. GPU readiness does not start automation or enable real execution.
