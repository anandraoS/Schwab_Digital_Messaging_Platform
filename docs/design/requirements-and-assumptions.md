# Requirements, Scope and Assumptions

This is an independent reference implementation inspired by a public job description for a digital messaging platform. It is not any employer's system. Everything is synthetic. Binding decisions: `CONTRACT.md` (initial plan) and `DEVIATIONS.md` (what was built; wins on conflict).

## Problem statement
Build a multi-tenant platform that accepts message requests over REST and delivers them as email, SMS or push through external providers, with reliable delivery semantics, operator tooling, bulk sending, and operational documentation.

## Functional requirements (summarised)
| Id | Requirement | Where |
|---|---|---|
| FR-1 | Accept a message request asynchronously (202), idempotently (`Idempotency-Key`), with template and variables, channel, category, priority, optional schedule and fallback channels | `docs/architecture/submission-flow.md`, `docs/api/openapi.yaml` |
| FR-2 | Enforce consent, suppression, quiet hours and tenant isolation; policy rejections are persisted `REJECTED` messages answered 202 | ADR-0011, ADR-0012 |
| FR-3 | Dispatch with bounded concurrency, provider rate limits, retries with backoff, dead-lettering, operator requeue/replay | `docs/architecture/dispatch-retry-flow.md`, ADR-0006 |
| FR-4 | Treat provider acceptance as not-delivery; ambiguous outcomes become `DELIVERY_UNKNOWN` and are reconciled before resend (email/SMS) | ADR-0010, `docs/architecture/reconciliation-flow.md` |
| FR-5 | Receive signed provider callbacks, normalise heterogeneous payloads, apply monotonic status rules, ignore duplicates and replays | `docs/architecture/callback-flow.md`, `callbacks-normalization.md` |
| FR-6 | Versioned templates with four-eyes approval and version pinning | ADR-0007, `docs/architecture/template-approval-flow.md` |
| FR-7 | Bulk CSV sending as a restartable batch job with progress, failure details, stop and restart | `docs/architecture/bulk-job-flow.md`, runbook `bulk-job-restart.md` |
| FR-8 | Operator console: message search and timeline, failed and dead-letter handling, queues, providers and fault injection (local only), templates, preferences, audit | `frontend/messaging-console` |
| FR-9 | Audit trail of state changes and operator actions; masked addresses in API and logs | `docs/security/data-minimization-and-retention.md` |
| FR-10 | Retention purge with legal-hold support | `docs/operations/data-retention-and-replay-policy.md` |

## Non-functional requirements
Illustrative targets only, derived by reasoning (see `docs/operations/slis-slos.md`): API availability SLO 99.9 percent and p95 submission latency 300 ms. No production traffic informed them and none is measured here. Security: OAuth2 JWT, role matrix, tenant isolation, HMAC callbacks, no secrets in files. Observability: Micrometer metrics, JSON-capable logs with correlation id, alert rules with runbooks.

## Scope
In scope: the backend, Angular console, database migrations, simulators, batch, observability and deployment assets, documentation, AI-engineering workspace, and a traceability matrix.

## Out of scope
- Real provider integrations (SMTP/ESP, SMS carriers, APNs/FCM) and real identity providers.
- Real customer data, real credentials, regulatory compliance sign-off (the consent model is an engineering model, not legal advice).
- Cross-tenant (global) suppression lists, attachments and rich content, inbound messaging (two-way SMS), A/B testing, analytics pipelines.
- Priority aging (spec 0001 is a worked example, ADR-0013 is Proposed).
- Multi-region active/active, CDC-based outbox, Kafka (documented as an alternative only).
- Running anything on Docker, Kubernetes, Helm, Cloud Foundry, GitHub Actions, Jenkins or Bamboo as part of this work.

## Assumptions
1. Single organisation operating multiple tenants in a shared schema; the tenant comes from the JWT `tenant` claim (`*` only for platform admin).
2. Providers are external, may be slow, may answer ambiguously, may send webhooks out of order and more than once.
3. Clients send an `Idempotency-Key` on every submission and retry 5xx/503 with the same key.
4. Delivery receipts can arrive before the sender's own commit is visible; an unknown message is acknowledged but not stored so a retry can succeed.
5. Bulk jobs run on the node that accepted them (single-node assumption for stop and orphan recovery).
6. PostgreSQL is the production database; H2 in PostgreSQL mode is used locally and in tests, so SQL stays portable (no JSONB, no ON CONFLICT, no partial indexes). Oracle compatibility is by documented mapping only, not executed.
7. Local runs use profile `local` on port 18080 (Angular dev server 14200); the application has no default profile so a forgotten profile cannot enable dev endpoints.
8. All secrets in the repository are dummy values; production secrets come from a secret store or environment variables.
9. Not executed here, by assumption of the environment: Docker, Kubernetes, k6, cloud services, real RabbitMQ/Redis/MongoDB/PostgreSQL.
10. Times are UTC in storage; the JVM zone is not assumed to be UTC (see DEVIATIONS test-support notes).

## Open questions (not resolved by this project)
- Real vendor semantics for status lookup and idempotency references.
- Whether suppression should be shared across tenants (needs a platform owner and a new ADR).
- Real traffic profile and therefore real SLO and capacity numbers.
