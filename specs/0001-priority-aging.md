# Spec 0001: Delivery-Priority Aging (starvation prevention)

- Status: **Worked example, not implemented in code.** A grep of `backend/src` for aging / effective-priority logic finds nothing; there is no `messaging.dispatch.aging.*` property, no test and no metric for it. Decision record: `docs/adr/0013-priority-aging.md` (Proposed). Use it to practise spec-driven development, not as evidence of a built feature.
- Owner (role): Architecture
- Date: 2026-10-18
- Related: ADR-0001 (RabbitMQ priority queues), ADR-0003, ADR-0004, ADR-0006; `docs/architecture/dispatch-retry-flow.md`; worked in `ai-engineering/spec-driven-workflow.md`

## 1. Context and problem
Priorities `CRITICAL(9)`, `HIGH(6)`, `NORMAL(3)`, `LOW(1)` map to RabbitMQ `x-max-priority=9`. A sustained stream of higher-priority messages can starve LOW (and under heavy HIGH load, NORMAL). LOW includes real service notices and bulk jobs that must eventually go out. Strict priority gives no upper bound on LOW waiting time. Observed risk class (design analysis, not a measured incident): a long HIGH burst during a bulk run.

## 2. Goals and non-goals
Goals: bounded waiting for every priority; CRITICAL latency protected; deterministic, observable, reversible behavior.
Non-goals: per-tenant priorities, preempting in-flight work, changing consent/policy order, changing retry semantics.

## 3. Requirements
- R1 (MUST) A QUEUED or RETRY_PENDING-eligible message whose age exceeds `aging.thresholds.<priority>` has its *effective priority* raised one level per threshold elapsed.
- R2 (MUST) Effective priority never exceeds `aging.max-effective` (default HIGH); CRITICAL is only reachable by requested priority.
- R3 (MUST) Requested `priority` is immutable; reporting uses requested priority; dispatch ordering uses effective priority.
- R4 (MUST) Promotion is idempotent: at most one promotion per message per level; double execution of the job must not double-send.
- R5 (MUST) Feature flag `messaging.dispatch.aging.enabled` default `false`; off means byte-identical behavior.
- R6 (SHOULD) Promotion audited (`PRIORITY_PROMOTED`, ids only) and counted.
- R7 (MUST) Works with `RabbitMessageBus` and `InMemoryMessageBus`.

## 4. Acceptance criteria
- AC-1 Given aging enabled and a LOW message queued longer than `lowToNormalAfter`, when the job runs, then effective priority becomes NORMAL, requested stays LOW.
- AC-2 Given a message already at `max-effective`, then no further promotion.
- AC-3 Given the job runs twice concurrently, then exactly one promotion record and at most one dispatch of the message.
- AC-4 Given flag off, then no promotions and no new envelopes.
- AC-5 Given a sustained HIGH flood at 90% of consumer capacity, then a LOW message is dispatched within `lowToNormalAfter + normalToHighAfter + wait at HIGH` (bounded; asserted in IT with fake clock).
- AC-6 Given a CRITICAL burst, then CRITICAL dispatch delay p95 is within 10% of aging-off baseline in the local harness (measure only if executed).
- AC-7 Given an aged message is later cancelled before dispatch, then it is CANCELLED and never sent.
- AC-8 Given the stale lower-priority envelope is delivered after promotion, then it is acked and skipped (no second provider call).

## 5. Design
- Migration (expand-only, portable): `ALTER TABLE message_request ADD COLUMN effective_priority VARCHAR(16)` (nullable; null means requested), `ADD COLUMN priority_promoted_at TIMESTAMP`. Index decision: age scan uses `(status, created_at)`-prefix; evaluate against existing `(tenant_id,status,created_at)` and `(status,next_attempt_at)`; add `ix_msg_status_created (status, created_at)` only if the plan shows scanning (document in query-plans doc). Oracle DDL updated.
- `AgingPolicy` (pure): `Optional<Priority> promote(Priority requested, Priority effective, Duration age, AgingConfig cfg)`.
- `PriorityAgingJob` (`scheduler`): every `aging.interval` (default 10 s), selects up to `aging.batch` (default 200) QUEUED messages ordered by `created_at` where age > smallest threshold. For each: CAS `UPDATE message_request SET effective_priority=:new, priority_promoted_at=:now WHERE id=:id AND status='QUEUED' AND COALESCE(effective_priority,priority)=:old`. If 1 row updated: publish a new `DispatchEnvelope` (new `dispatchId`, same `attemptNo`, higher broker priority) via `MessageBus`; audit; metric.
- Stale envelope handling: both envelopes target the same message. The first consumer CAS `QUEUED->PROCESSING` wins; the second finds status != QUEUED (or `processed_message` conflict) and acks/skips (existing consumer logic, invariant 3). One `dispatchId` per (message, attemptNo) rule: to keep it, the promoted envelope reuses the *original* `dispatchId` plus an `envelopeRevision` field, and `processed_message` dedupe keys on `dispatchId` only (the second copy is skipped regardless of which copy arrives first). **Decision needed at design review**: reusing dispatchId (preferred; no contract change beyond an additive envelope field) vs new dispatchId (requires CAS-only dedupe). Spec adopts reuse.
- Config (`messaging.dispatch.aging.*`): `enabled`, `interval`, `batch`, `max-effective`, `thresholds.low-to-normal` (e.g., PT5M), `thresholds.normal-to-high` (PT15M); illustrative defaults.
- Alternatives: (a) weighted fair dequeue / separate consumer pools with reserved share (simple, robust, wastes capacity, no broker change; recommended complement: reserve 1 of 8 workers for LOW/NORMAL); (b) broker plugin priority tweaks (not supported in-place); (c) DB-driven dispatch ordering (higher DB load). Chosen: promotion job; reserved-worker option documented as fallback.
- ADR: propose ADR-0013 "Priority aging" before implementation.

## 6. Failure modes
| Failure | Outcome |
|---|---|
| Job crashes after CAS before publish | message promoted in DB, envelope not published; original lower-priority envelope still exists so message is not lost; next tick republishes only if still QUEUED and `priority_promoted_at` older than `republish-after`; duplicates skipped by consumer |
| Job runs on two instances | CAS makes second a no-op per message |
| Broker down | job publish fails; metric; retry next tick; no state change beyond DB promotion (flag `needs_republish` derivable from `priority_promoted_at`) |
| Message consumed between select and CAS | CAS affects 0 rows; skip |
| Clock skew between instances | thresholds in minutes; tolerate; use DB `created_at` and app `Clock` |

## 7. Security and privacy
No new inputs. Prevents a client from exploiting starvation, but must not let a client raise effective priority beyond `max-effective` (requested priority validation unchanged). Audit has ids only. No sensitive-change independent review required beyond normal, except reviewer must verify invariant 3 (double dispatch) given the duplicate envelope design.

## 8. Performance and capacity
Age scan bounded by `FETCH FIRST :batch ROWS ONLY` every 10 s; expected negligible vs dispatch load; extra envelopes bounded by promotions. Measurement plan: in-JVM harness scenario with HIGH flood; record numbers only from executed runs.

## 9. Observability
New metric `messaging.priority.promotions{from,to}` (propose adding to CONTRACT section 8 via DEVIATIONS entry), gauge of oldest queued age per effective priority (extends `messaging.queue.depth{priority}` semantics: document), log at DEBUG per batch, INFO summary. Alert: LOW oldest age > 2x `lowToNormalAfter` with aging enabled. Runbook `queue-backlog.md` to mention aging.

## 10. Test plan
- `AgingPolicyTest#promotesAfterThreshold_onlyOneLevelPerThreshold`, `#neverExceedsCeiling`, `#nullEffectiveTreatedAsRequested`.
- `PriorityAgingIT#lowMessageUnderHighFlood_isDispatchedWithinBound` (fake clock, FaultInjector NONE, consumer concurrency 1).
- `PriorityAgingIT#concurrentJobRuns_promoteOnce_dispatchOnce`.
- `PriorityAgingIT#staleEnvelope_isSkippedAfterPromotion_noDoubleSend`.
- `PriorityAgingIT#cancelledAfterPromotion_isNeverSent`.
- `PriorityAgingIT#flagOff_noPromotionsNoEnvelopes`.
- `LoadHarness` scenario `criticalDelayNotRegressed` (executed locally only).
- Migration test: Flyway on H2 applies; columns nullable.

## 11. Task decomposition
| Task | Role | Depends | DoD |
|---|---|---|---|
| T1 migration + entity fields + Oracle DDL | Backend | | integration context boots |
| T2 `AgingPolicy` + config + unit tests | Backend | | tests green |
| T3 `PriorityAgingJob` with CAS + republish | Backend | T1,T2 | IT concurrent test |
| T4 envelope revision field + consumer skip verification | Backend | T3 | stale envelope test |
| T5 metrics, audit, docs/runbook | Backend/Doc | T3 | metric asserted |
| T6 harness scenario | Performance | T3 | executed result recorded or "not measured" |
| T7 ADR-0013 and DEVIATIONS entry | Architecture | | review gate A |

## 12. Rollout and rollback
Ship with flag off. Staging: enable with synthetic flood and harness. Canary: enable on one instance (job is CAS-safe). Success: LOW wait bounded, CRITICAL delay unchanged, duplicate provider calls = 0 (compare attempts per dispatchId). Rollback: set flag false; columns remain unused (expand-only), previous app version ignores them.

## 13. Documentation updates
`docs/architecture/dispatch-retry-flow.md`, `docs/operations/runbooks/queue-backlog.md`, `docs/operations/slis-slos.md` (queue age by priority), ADR-0013, CONTRACT/DEVIATIONS, `ai-engineering/context/lifecycle.md` (note: effective priority).

## 14. Open questions
1. Reuse `dispatchId` vs new (owner: architecture; decide at gate A).
2. Does RETRY_PENDING age from `created_at` or from `next_attempt_at`? Proposal: from the moment it re-enters QUEUED.
3. Per-tenant fairness is out of scope: revisit if a single tenant dominates.
