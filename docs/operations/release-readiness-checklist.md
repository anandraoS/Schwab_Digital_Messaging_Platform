# Release Readiness Checklist

Tick each item with evidence (link/command output) in the release PR. Items marked (N/E) cannot be executed in the authoring environment; in a real pipeline they must be.

## Code and tests
- [ ] `mvn -B verify` passes (unit + integration `*IT` on H2). Evidence: CI log or `docs/validation/VALIDATION_REPORT.md`.
- [ ] `FailureRecoveryIT` all 10 scenarios green (see `docs/architecture/reliability-scenarios.md`).
- [ ] Angular: `ng test --watch=false --browsers=ChromeHeadless` and production build pass.
- [ ] `*ContainerIT` (PostgreSQL, RabbitMQ, Redis, Mongo) with `-Pcontainers` (N/E here).
- [ ] OpenAPI committed spec matches springdoc output (test); breaking-change review done.
- [ ] No new TODO/FIXME without ticket; no `System.out`.
## Data and schema
- [ ] Flyway migrations are expand-only for rolling deploy (`docs/database/schema-evolution.md`); Oracle reference DDL updated.
- [ ] Large-table index/DDL plan reviewed; migration timing rehearsed on prod-sized copy (N/E).
- [ ] Retention config unchanged or reviewed.
## Security
- [ ] Security checklist done (`ai-engineering/checklists/security-review.md`); independent review for auth/tenant/callback/template changes.
- [ ] Dependency, container, secret scans clean or triaged (N/E here).
- [ ] No dev endpoints enabled in `prod-like` (`dev-token-enabled=false`, no `/dev/**`, no fault-mode).
- [ ] Secrets rotated/available per `docs/security/secrets-management.md`.
## Reliability and performance
- [ ] Load test vs baseline (k6/harness) no regression > agreed threshold (N/E for k6).
- [ ] Config review: concurrency, prefetch, backoff, pool sizes sane for target environment.
- [ ] Graceful shutdown verified (rolling restart loses no messages: in-flight redelivered, dedupe holds).
- [ ] Capacity/headroom review including DB connections vs instances.
## Observability and operations
- [ ] Dashboards and alert rules updated for new metrics; alert-to-runbook links valid.
- [ ] Runbooks updated for new failure modes; on-call briefed.
- [ ] Feature flags / config defaults documented; rollback plan reviewed (`rollback-plan.md`).
## Documentation
- [ ] ADR added/superseded for decisions; spec status updated (`specs/`).
- [ ] CHANGELOG/release notes with migration notes and behavior changes.
- [ ] Contract doc (`docs/design/CONTRACT.md` or DEVIATIONS) in sync.
## Deployment
- [ ] Staging soak with synthetic traffic and fault injection (N/E here).
- [ ] Canary plan: % traffic/instances, success criteria (error rate, dispatch delay p95, DLQ growth), abort criteria.
- [ ] Post-deploy verification script (smoke: submit, status, callback via simulator) passes.
- [ ] Go/no-go recorded by release owner and on-call.
