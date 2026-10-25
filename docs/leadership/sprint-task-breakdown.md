# Sprint Task Breakdown (example, 3 sprints)

Illustrative plan for a team of ~5 engineers + QA, 2-week sprints. Estimates are story points (relative), not measurements. Each task has a definition of done per `ai-engineering/checklists/definition-of-done.md`.

## Sprint 1: Walking skeleton and safe acceptance
Goal: submit, persist, queue, dispatch to a simulator, see status. Demo: end-to-end happy path locally.
| ID | Task | SP | Depends | Acceptance |
|---|---|---|---|---|
| S1-1 | Project skeleton, profiles, Flyway V1 schema, H2/PG | 5 | | app boots on `local`; migrations apply |
| S1-2 | Security: JWT resource server, dev token, tenant context | 5 | S1-1 | 401/403/404 matrix tests |
| S1-3 | Domain: enums, `MessageStateMachine`, audit service | 3 | S1-1 | full transition matrix test |
| S1-4 | `POST /messages` + idempotency + validation | 8 | S1-2,3 | AC-1..4 in `acceptance-criteria.md` |
| S1-5 | Template service (create/publish/pin) + renderer | 8 | S1-1 | pin, escape, variable validation tests |
| S1-6 | Outbox + `InMemoryMessageBus` + `DispatchConsumer` happy path + simulators | 8 | S1-4 | message reaches SENT_TO_PROVIDER |
| S1-7 | Status/list/history endpoints | 3 | S1-4 | pagination + masked output tests |
| S1-8 | Metrics skeleton, correlation id, structured logs | 3 | S1-1 | `/actuator/prometheus` lists core meters |
Risks: schema portability H2/PG; mitigation: portable-SQL checklist.

## Sprint 2: Reliability
Goal: failures are safe. Demo: fault injection (timeout, outage, duplicate callbacks) with correct states.
| ID | Task | SP | Depends | Acceptance |
|---|---|---|---|---|
| S2-1 | `RetryPolicy`, backoff/jitter, RETRY_PENDING, DLQ, poison | 8 | S1-6 | scenario01, scenario08 |
| S2-2 | `ResilientProviderInvoker` (breaker, timeout) | 5 | S1-6 | circuit metrics, outcomes |
| S2-3 | Callback endpoint: HMAC, replay guard, normalization, ranking | 8 | S1-6 | scenario03; forged callback 401 |
| S2-4 | Ambiguity: sweeper, `ReconciliationService`, lookup | 8 | S2-1 | scenario04/05 |
| S2-5 | Rate limit (token bucket), Redis adapter + resilient fallback | 5 | S1-6 | scenario07, scenario10 |
| S2-6 | Policy service (consent, suppression, quiet hours, fallback) | 8 | S1-4 | table-driven tests |
| S2-7 | Ops endpoints, replay/requeue/cancel | 5 | S2-1 | scenario01 replay step |
| S2-8 | `FailureRecoveryIT` consolidation | 5 | all | 10 scenarios |

## Sprint 3: Scale, operability, UX
Goal: bulk, console, observability, deployment assets, docs.
| ID | Task | SP | Depends | Acceptance |
|---|---|---|---|---|
| S3-1 | Spring Batch bulk job + restart | 8 | S2-6 | scenario09 |
| S3-2 | Angular console (messages, templates, queues, DLQ) | 13 | S2-7 | unit tests, role-aware nav |
| S3-3 | Mongo diagnostics + TTL | 3 | S2-3 | best-effort test |
| S3-4 | Dashboards/alerts, runbooks wiring | 5 | S2-* | alert rules lint (static) |
| S3-5 | Docker/Compose/K8s/Helm/CF assets (static validation) | 5 | | files lint; marked N/E |
| S3-6 | Perf harness + k6 scripts | 5 | S2-8 | harness executed locally; k6 N/E |
| S3-7 | Security tests + threat model review | 5 | | `tests/security` |
| S3-8 | Docs, ADR review, release checklist | 5 | | review gate |

## Cross-sprint working agreements
Trunk-based with short-lived branches; PRs < 400 lines where possible; every PR uses `.github/pull_request_template.md`; security-sensitive PRs need independent reviewer (CODEOWNERS); ADR for each cross-cutting decision.

## Dependencies and risks
See `risk-register.md`.
