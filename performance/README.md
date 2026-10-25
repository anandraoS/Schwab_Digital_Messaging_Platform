# Performance tests

Status: **NOT executed.** k6 is not installed on the authoring machine, so every k6 script here is unrun and its
thresholds are illustrative (derived from the illustrative SLOs in `docs/operations/performance-tests.md`). The Node
load driver can run anywhere Node 18+ exists; no result has been recorded yet.

## Layout
| Path | What |
|---|---|
| `k6/lib/common.js` | shared helpers: `/dev/token` auth, request builders, SLO thresholds |
| `k6/submit-high-volume.js` | ramping-arrival-rate API submission up to 150 req/s |
| `k6/concurrent-consumers.js` | steady producers + sampling of `/ops/queues` (vary `MESSAGING_DISPATCH_CONCURRENCY`) |
| `k6/provider-rate-limit.js` | SMS burst with RATE_LIMIT fault injection |
| `k6/bulk-processing.js` | synthetic bulk job end-to-end duration/throughput |
| `k6/db-throughput.js` | write + indexed-read mix to expose pool saturation |
| `k6/backlog-recovery.js` | pause consumers, build backlog, resume, measure drain time |
| `k6/retry-storm.js` | 100% TRANSIENT provider failures, then recovery |
| `k6/dashboard-queries.js` | operators polling console endpoints |
| `node-load/load-driver.mjs` | zero-dependency Node driver with p50/p95/p99 output |

## Prerequisites
1. Backend running with profile `local` on port 18080 (`bash scripts/run-local.sh`). `POST /dev/token` must be enabled.
2. For k6: install k6 (https://k6.io/docs/get-started/installation/). Fault-injection scripts need `PUT /api/v1/providers/{id}/fault-mode` (profile local/test only).

## Commands
Run from the repository root.

```bash
# Node driver (runs here, no extra install)
node performance/node-load/load-driver.mjs --scenario submit --concurrency 16 --duration 30 --out performance/node-load/results/submit.json
node performance/node-load/load-driver.mjs --scenario read   --concurrency 16 --duration 20
node performance/node-load/load-driver.mjs --scenario mixed  --requests 5000 --concurrency 32
node performance/node-load/load-driver.mjs --scenario idempotent --requests 200 --concurrency 8

# k6 (NOT executed here)
k6 run -e BASE_URL=http://localhost:18080 performance/k6/submit-high-volume.js
k6 run -e BASE_URL=http://localhost:18080 performance/k6/concurrent-consumers.js
k6 run -e BASE_URL=http://localhost:18080 -e PROVIDER_ID=sim-sms performance/k6/provider-rate-limit.js
k6 run -e BASE_URL=http://localhost:18080 -e ROWS=20000 performance/k6/bulk-processing.js
k6 run -e BASE_URL=http://localhost:18080 performance/k6/db-throughput.js
k6 run -e BASE_URL=http://localhost:18080 -e BACKLOG=5000 performance/k6/backlog-recovery.js
k6 run -e BASE_URL=http://localhost:18080 -e PROVIDER_ID=sim-email performance/k6/retry-storm.js
k6 run -e BASE_URL=http://localhost:18080 performance/k6/dashboard-queries.js
# Compose profile (needs Docker, NOT executed): docker compose --profile load -f infrastructure/compose/docker-compose.yml run --rm k6
```

Environment knobs: `BASE_URL`, `TENANT`, `TEMPLATE_CODE`, `RECIPIENTS`, `PROVIDER_ID`, `ROWS`, `BACKLOG`.
Assumptions that must be confirmed on first run are listed at the top of `k6/lib/common.js` (dev-token request/response shape,
seed template code, provider ids).

## Results (empty until executed)
Record only numbers produced by a real run: date, commit, machine, backend profile, command, outcome.

| Date | Commit | Environment | Scenario / command | Throughput | p50 | p95 | p99 | Error rate | Notes |
|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | |
