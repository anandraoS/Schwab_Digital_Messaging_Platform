# Validation Report

Date of final verification: 2026-10-18. Machine: Windows 11, 8 logical CPUs, JDK 21 (GraalVM 21.0.7), Node 24, Chrome 155 headless.
Every number below was produced by a command that was actually run on this machine; commands are given from the repository root.
This is an independent reference implementation; results do not describe any employer's systems.

## 1. Executed and passed

| Area | Command | Result (observed) |
|---|---|---|
| Backend compile + all tests | `bash scripts/mvn.sh clean verify` | BUILD SUCCESS in 6 min 15 s. Surefire (unit): 281 run, 0 failures, 0 errors, 0 skipped. Failsafe (integration): 226 run, 0 failures, 0 errors, 1 skipped (`BulkThroughputIT`, opt-in via `-Dbulk.measure=true`). Re-run by the lead after all agents finished. |
| Backend coverage (JaCoCo, from that run) | `backend/target/site/jacoco/jacoco.csv` | 90.2 % instruction, 77.5 % branch (no coverage gate is enforced). |
| Backend static analysis | `bash scripts/mvn.sh verify -Pstatic-analysis` | Reported green by the integration agent after fixing 8 SpotBugs findings in code (0 SpotBugs bugs, 0 Checkstyle violations); two SpotBugs patterns are excluded with written reasons in `backend/config/spotbugs-exclude.xml`. Not re-run in the final lead pass. |
| OpenAPI contract | `OpenApiContractIT` (part of verify) | Passed: springdoc output contains every committed path; `docs/api/openapi.yaml` identical to the committed spec. |
| Angular lint / build / unit tests | `npx ng lint`, `npx ng build --configuration production`, `npx ng test --watch=false --browsers=ChromeHeadlessNoSandbox` (in `frontend/messaging-console`) | Lint clean; production build OK; 212 of 212 tests passed. Re-run by the lead. |
| End-to-end over real HTTP | `node tests/end-to-end/e2e.mjs` against `backend/target/messaging-platform.jar`, profile `local` | 254 passed, 0 failed, 0 skipped (267.9 s). Output: `docs/validation/e2e-run-output.txt`. An earlier run had 9 failing checks, all test-side defects, corrected and re-run on a fresh backend (see the header of that file). |
| Browser UI check | `node tests/end-to-end/ui-check.mjs` (headless Chrome via DevTools protocol) | 16 pages and 4 interactive flows, 0 console errors, 0 failed API calls; 23 screenshots in `docs/validation/screenshots/`. |
| Smoke test on the packaged jar | `SERVER_PORT=18380 RUN_PID_FILE=1 bash scripts/run-local.sh` then `BASE_URL=http://localhost:18380 bash scripts/smoke-test.sh` | SMOKE OK (liveness, readiness, 401, tokens, submit 202, idempotent replay 200, missing key 400, message progressed to SENT_TO_PROVIDER, ops summary, Prometheus meters). `scripts/seed-demo.sh` submitted 24 messages (24 x 202) and a synthetic bulk job (202). Both scripts had stale request shapes (token field names, template variables, channel/category) that the lead found and fixed; the k6 helper `performance/k6/lib/common.js` received the same fix but k6 itself was not run. |
| Failure-recovery scenarios | `FailureRecoveryIT` (scenario01-08, 10) and `FailureRecoveryBulkIT#scenario09_bulkJobRestart_resumesWithoutDuplicates` (part of verify); `tests/failure-recovery/*.mjs` | ITs passed in verify. The three Node scripts (provider outage, callback replay, provider timeout) were run by the messaging agent against the packaged jar; all checks passed. |
| Static infra validation | `bash scripts/validate-infra.sh` | PASS=6, FAIL=0, SKIPPED=14 (bash -n on scripts, YAML/JSON parse with a home-grown syntax checker, Cloud Foundry manifest validator, alert/metric/runbook consistency, secret-pattern scan, workflow permission checks, `make-zip.py` parse). |
| Performance (local, single machine) | `node performance/node-load/load-driver.mjs ...` | See `docs/validation/performance-results.md`. Example: submit with 8 clients 178.9 req/s, p95 134.8 ms; with 32 clients 262 accepted req/s with 1,069 backpressure 503s. Load generator and backend shared the machine; H2 and the in-memory bus, not PostgreSQL/RabbitMQ. |
| Query plans | `QueryPlanIT` (part of verify) | EXPLAIN ANALYZE of 17 hot queries on 100,000 messages, **H2 plans only**: `docs/database/query-plans-executed.md`. |
| Bulk throughput | `BulkThroughputIT` with `-Dbulk.measure=true` (batch agent) | 46-79 rows/s on this machine, bounded by the per-message submission path; see `docs/operations/bulk-test-plan.md`. |
| PDFs | `node tools/pdf/verify-pdf.mjs <pdf>` | See section 4. |

## 2. NOT executed (no tool, service or credentials available)
Docker image builds; `docker compose config/up`; Trivy; hadolint; kubectl/Kustomize/kubeconform; Helm lint/template/install; `cf push` or any Cloud Foundry foundation
(manifests are only parsed and checked by `cloud-foundry/validate-manifest.mjs`); GitHub Actions, Jenkins and Bamboo runs (YAML syntax only); k6 scripts
(`performance/k6/*`); Testcontainers tests (`-Pcontainers`, including `RabbitMessageBusContainerIT`); real PostgreSQL, Oracle, RabbitMQ, Redis (`RedisCoordination` Lua token bucket
compiled but never run) and MongoDB (tests use H2 in PostgreSQL mode, the in-memory bus, in-memory coordination and `mongo-java-server`); `-Psecurity-scan` (OWASP
dependency-check; no NVD key, so **no dependency-vulnerability findings exist or are claimed**); promtool, Grafana rendering of the dashboards; OpenTelemetry export to a collector;
Playwright; axe/Lighthouse/screen-reader testing; cross-browser testing; GitHub Copilot or any agent session other than Claude Code (the AI-workspace files are authored artifacts).

## 3. Known limitations and open issues (honest list)
- RabbitMQ and Redis adapters are unproven in execution; their pure logic is unit-tested (routing, topology vs `definitions.json`, fallback decorator with a stub).
- H2 behaviour is not PostgreSQL behaviour: list/audit queries sort by `(timestamp DESC, id)` and H2 reads the whole tenant/status partition; PostgreSQL plans are unverified.
- The API accepts faster than the pipeline dispatches on this machine (170-300 req/s vs about 120-200 msg/s) and applies backpressure (503) at 5,000 pending, by design.
- Bulk restart is deterministic only with one worker (resume from the last committed chunk); with more workers the ledger and idempotency keys prevent duplicates but re-read the file.
- The in-memory bus loses queued envelopes on restart; the stale-QUEUED poller (`pollStaleQueued`) re-publishes them, and the idempotent consumer makes duplicates harmless.
- Handled idempotency-key races are logged at ERROR by Hibernate although the request ends 200/409 correctly.
- Priority aging (spec 0001, ADR-0013) is a worked specification example and is **not implemented**.
- `scripts/stop-local.sh` needs `RUN_PID_FILE=1` when starting with `run-local.sh`.
- Provider p95 and dispatch-delay p95 gauges read 0 when idle (rolling ~2 minute window).

## 4. PDF verification
Run `node tools/pdf/verify-pdf.mjs docs/pdf/<file>.pdf --min-pages N`; results of the final build are appended below by `scripts/` output of the lead (see section 5).

## 5. Final artifacts
Both PDFs live in `docs/pdf/`; the ZIP `Schwab_Digital_Messaging_Platform.zip` is at the repository root (built by `python -I scripts/make-zip.py`). The final PDF page counts and ZIP file count are recorded in `docs/validation/final-artifacts.txt`.
