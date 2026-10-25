# Dependency and Risk Register (example)

Likelihood (L) and Impact (I): 1 low to 5 high. Score = L x I. Owners are roles. Status values: Open, Mitigating, Accepted, Closed.

## Dependencies
| ID | Dependency | Type | Needed by | Fallback |
|---|---|---|---|---|
| D1 | Enterprise IdP issuing JWT with `tenant`, `roles`, `scope` claims | external | Sprint 1 (prod), dev token locally | local dev issuer |
| D2 | Provider vendor sandboxes, webhook specs, quotas | external | before real integration | simulators |
| D3 | PostgreSQL/RabbitMQ/Redis/MongoDB environments | infra | staging | in-memory profiles |
| D4 | Compliance/privacy review of consent policy | governance | before production | n/a |
| D5 | Observability stack (Prometheus, Grafana, OTel collector) | infra | Sprint 3 | local metrics endpoint |
| D6 | Container/K8s/CF platform access | infra | Sprint 3 | static validation |

## Risks
| ID | Risk | L | I | Score | Mitigation | Owner | Status |
|---|---|---|---|---|---|---|---|
| R1 | Duplicate sends after ambiguous outcomes | 3 | 4 | 12 | ADR-0010, provider idempotency refs, no blind retry, reconciliation tests | Backend lead | Mitigating |
| R2 | Message loss between DB and broker | 2 | 5 | 10 | Outbox (ADR-0009), consumer dedupe | Backend lead | Mitigating |
| R3 | Cross-tenant data exposure | 2 | 5 | 10 | Tenant-scoped repositories, 404 policy, matrix tests, independent review, RLS in prod | Security lead | Mitigating |
| R4 | Provider outage/throttling | 4 | 3 | 12 | Breaker, backoff+jitter, multi-provider, runbooks | Ops lead | Mitigating |
| R5 | H2 vs PostgreSQL behavior divergence | 3 | 3 | 9 | portable SQL rule, Testcontainers PG in CI | Backend lead | Open (container tests N/E here) |
| R6 | Retry storm amplifies incident | 3 | 4 | 12 | full jitter, caps, shared token bucket, runbook | Backend lead | Mitigating |
| R7 | Priority starvation of LOW traffic | 3 | 2 | 6 | priority aging spec 0001 | Architect | Planned |
| R8 | Forged/replayed callbacks | 2 | 4 | 8 | HMAC + replay guard + unique constraint | Security lead | Mitigating |
| R9 | Template content error sent at scale | 3 | 4 | 12 | four-eyes, preview, cancel tooling, canary template rollout | Product owner | Mitigating |
| R10 | PII leakage in logs/diagnostics | 3 | 4 | 12 | Redaction, minimization, log tests, review checklist | Security lead | Mitigating |
| R11 | Consent logic misapplied (marketing as transactional) | 2 | 5 | 10 | category bound to template, audits, policy tests | Product owner | Mitigating |
| R12 | Operational unreadiness (runbooks untested) | 4 | 3 | 12 | game days, PIR culture | Ops lead | Open |
| R13 | Capacity assumptions wrong | 3 | 3 | 9 | load tests, metrics-based scaling, backpressure | Architect | Open (k6 N/E here) |
| R14 | Connection pool exhaustion cascades | 3 | 4 | 12 | short tx, no tx over provider call, alerts | Backend lead | Mitigating |
| R15 | AI-generated code violates invariants | 3 | 4 | 12 | invariants list, checklists, independent review, tests (`ai-engineering/`) | Tech lead | Mitigating |
| R16 | Deploy assets never executed here | 5 | 2 | 10 | explicit N/E labelling; validate in real pipeline | DevOps lead | Accepted for reference |
| R17 | Single datastore dependency (PostgreSQL) | 2 | 5 | 10 | HA, backups, DR design (not exercised) | DBA | Open |

## Review cadence
Reviewed at each sprint planning; scores updated with evidence; closed risks retained with reason.
