# Spec-Driven Workflow: Idea to Production

An 11-step flow, followed by a worked example: **delivery-priority policy (priority aging / starvation prevention)**, whose full spec is `specs/0001-priority-aging.md`. The example is a *design exercise*: it describes a feature to build, not one that exists in the code at the time of writing (check source and `docs/validation/VALIDATION_REPORT.md` for status).

## The 11 steps
| # | Step | Output | Gate |
|---|---|---|---|
| 1 | Idea and problem framing | problem statement, who is hurt, evidence | owner agrees it is worth doing |
| 2 | Requirements | numbered requirements, non-goals | review |
| 3 | Acceptance criteria | Given/When/Then, measurable | testable? |
| 4 | Design | spec design section, ADR if cross-cutting, failure modes, security/perf notes | Gate A design review |
| 5 | Task decomposition | ordered tasks, each independently mergeable, with named tests | sizing sanity |
| 6 | Test plan first | named tests (unit, integration, failure), data, matrices | tests precede or accompany code |
| 7 | Implementation | small PRs per task | Gate B code review |
| 8 | Security and performance review | threat-model row, independent review if sensitive, perf notes | security gate |
| 9 | Documentation | docs, runbooks, config, ADR status, API docs | accuracy check |
| 10 | Release | readiness checklist, flag/canary plan, rollback plan | Gate C |
| 11 | Operate and learn | dashboards/alerts, SLO check, PIR if incidents, spec status Implemented, backlog follow-ups | review at 2 weeks |

## Worked example: priority aging / starvation prevention

### 1. Idea
Strict priority queues (`CRITICAL(9)`, `HIGH(6)`, `NORMAL(3)`, `LOW(1)`) can starve LOW messages when higher priorities are continuously busy (e.g., a large HIGH burst). LOW includes legitimate service messages that would never go out.

### 2. Requirements (summary)
R1 A message waiting longer than a priority-specific threshold is promoted one level (aging), up to a ceiling below CRITICAL. R2 CRITICAL is never displaced by aged traffic more than a bounded share. R3 Promotion is deterministic, audited and observable. R4 Feature-flagged, default off. R5 No change to consent/policy semantics (aging affects order only). R6 Works with RabbitMQ priority and the in-memory bus. Non-goals: dynamic per-tenant priorities, preemption of in-flight work.

### 3. Acceptance criteria (summary)
AC-1 LOW waiting > `lowToNormalAfter` becomes NORMAL (effective priority) exactly once per threshold; AC-2 never exceeds `maxEffectivePriority=HIGH`; AC-3 original priority is retained for reporting; AC-4 with flag off behavior is unchanged; AC-5 under a sustained HIGH flood at 90% capacity, a LOW message is dispatched within `lowToNormalAfter + normal wait` (bounded); AC-6 CRITICAL p95 dispatch delay does not regress by > 10% in the harness.

### 4. Design (summary)
- Store `effective_priority` and `priority_promoted_at` (nullable, expand-only migration). `priority` remains the requested value.
- A `PriorityAgingJob` (scheduler) finds QUEUED/RETRY_PENDING messages older than thresholds by `(status, created_at)`-bounded query, and re-publishes an envelope at the higher priority with a new `dispatchId`; the old envelope becomes a no-op because consumer CAS `QUEUED to PROCESSING` plus `processed_message` ensures only one executes (the stale lower-priority copy is acked and skipped).
- Bus semantics: RabbitMQ cannot reprioritize in place, so re-publish is the mechanism; in-memory bus identical.
- Weighted fair dequeue alternative considered (rejected: complex, broker-specific). See spec for alternatives.

### 5. Task decomposition
T1 migration + entity fields; T2 `AgingPolicy` pure class with thresholds config + unit tests; T3 `PriorityAgingJob` + CAS bookkeeping; T4 consumer skip logic test; T5 metrics + audit; T6 flag/config + docs; T7 harness scenario; T8 runbook update.

### 6. Test plan (names)
`AgingPolicyTest#promotesAfterThreshold_onlyOneLevelPerThreshold`, `#neverExceedsCeiling`, `PriorityAgingIT#lowMessageUnderHighFlood_isDispatchedWithinBound`, `#staleLowEnvelope_isSkippedAfterPromotion_noDoubleSend`, `#flagOff_noPromotion`, `#criticalDelayNotRegressed_harness`.

### 7. Implementation
Small PRs per task using the PR template; each runs checklists.

### 8. Security and performance review
Security: aging must not let a SENDER force HIGH by delaying; ceiling prevents CRITICAL abuse; priority field remains validated; audit entry `PRIORITY_PROMOTED` has ids only. Independent review not strictly required (not authn/consent), but the double-send risk touches idempotency: reviewer must verify invariant 3. Performance: aging query bounded by `FETCH FIRST n`, uses `(status, created_at)`-style index (new index decision documented), runs every N seconds with batch cap; metric `messaging.priority.promotions{from,to}` (new metric requires CONTRACT/DEVIATIONS note).

### 9. Docs
Update `docs/architecture/dispatch-retry-flow.md`, runbook `queue-backlog.md`, config docs, ADR (new ADR-0013 if accepted: priority aging), `context/lifecycle.md`/observability if metric added.

### 10. Release and rollback
Ship dark (flag off); enable in staging with fault/load; canary on one instance; success criteria: LOW wait bounded, CRITICAL delay unchanged, no duplicates. Rollback: disable flag (no schema rollback needed; columns nullable and ignored).

### 11. Operate
Dashboard panel: promotions rate, wait by priority; alert if LOW oldest age exceeds threshold even with aging on.

## Second example
Adding a provider: `specs/0002-new-provider-adapter.md`.
