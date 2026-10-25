# Documentation Consistency Log

Pass date: 2026-10-18. Rule applied: `DEVIATIONS.md` (what was built) wins over `CONTRACT.md` (initial plan); where the two disagree with the code, the code (verified by reading the class) wins. Only documentation was edited; no backend, frontend, test, performance, tools or observability file was touched.

## Fixes made
| # | File | Contradiction found | Fix |
|---|---|---|---|
| 1 | `docs/architecture/callback-flow.md` | Described an earlier plan: replay guard before normalisation with a bare "200", duplicate recorded as `ignored_reason=DUPLICATE`, unknown message stored as `UNKNOWN_MESSAGE`, 200 for processed events, metric list incomplete | Rewritten from `CallbackController`, `CallbackService`, `ReplayGuard`, `DeliveryEventProcessor`: uniform 401, 400 for malformed, replay answered 200 `replayed`, duplicate/unknown create no row, results and HTTP codes table, side effects, key id `acme-sms-key` |
| 2 | `specs/0002-new-provider-adapter.md` | Planned `AcmeClient`, `AcmeCallbackMapper`, key `acme-key-1`, payload field `eid`, tests that do not exist (`AcmeSmsProviderTest`, `ProviderExtensionIT`, `CallbackIT`) | Rewritten as requirement-to-implementation table (`AcmeSmsProvider`, `AcmeSmsPayloadMapper`, `acme-sms-key`, `id`, seed row disabled by default) and acceptance criteria mapped to real tests |
| 3 | `docs/adr/0004-idempotency.md` | Callback layer said duplicates are recorded `ignored_reason=DUPLICATE` with HTTP 200 | Replay = 200 `replayed`; unique-key duplicate = 202 `duplicate` with no second row |
| 4 | `docs/adr/0012-consent-and-suppression-policy.md` | "Global suppression", submission-time `POLICY_SUPPRESSED`, reference to non-existent `docs/design/policy.md`, alternative table implying a global list | Suppression is per tenant (schema `UNIQUE(tenant_id, channel, address_hash)`, tenant-scoped repository lookups); policy rejections are persisted `REJECTED` answered 202; `POLICY_SUPPRESSED` unused; ADR is the policy reference |
| 5 | `docs/architecture/submission-flow.md` | "either `POLICY_SUPPRESSED` or accepted-then-REJECTED" | States 202 + `REJECTED` + `statusReason`; adds `DUPLICATE_RESOURCE` 409 and DB-outage 503 |
| 6 | `docs/leadership/api-contract-guide.md` | `POLICY_SUPPRESSED` listed as a 422; no `DUPLICATE_RESOURCE` | 409 row gains `DUPLICATE_RESOURCE`; 422 row only `IDEMPOTENCY_KEY_REUSED`; note on 202 REJECTED, 200 idempotent replay and 200 replayed callback |
| 7 | `docs/leadership/feature-specification.md` | Opt-out / suppression edge cases listed as bare "REJECTED" | Clarified as 202 with status REJECTED and `statusReason`; suppression tenant scoped |
| 8 | `ai-engineering/context/api.md` | Error-code list had no `DUPLICATE_RESOURCE`, treated `POLICY_SUPPRESSED` / `REPLAYED_EVENT` as live | Added `DUPLICATE_RESOURCE`; marked the two codes unused with the real behaviour |
| 9 | `ai-engineering/context-map.md` | Referenced non-existent `design/policy.md`; CONTRACT described as binding | Points to `DEVIATIONS.md` (wins), this log and the requirements document |
| 10 | `docs/architecture/dispatch-retry-flow.md` | Sequence diagram showed dedupe + status CAS before policy/rate-limit/circuit checks and a separate `DispatchService` | Diagram replaced by the implemented order (cheap refusals first, circuit permit reserved before the claim, single-transaction claim, outbox single transaction) plus notes on quiet-hours deferral, dispatch-time rejection and requeue budget |
| 11 | `docs/adr/0009-outbox-pattern.md` | Outbox claim described as a `CLAIMED` status, publish after claim | Describes `markSent` CAS, `available_at`, publish in the same transaction (rollback gives at-least-once) |
| 12 | `docs/adr/0003-message-lifecycle-state-machine.md` | `VALIDATED to REJECTED` attributed to dispatch-time suppression | Notes dispatch-time rejection is applied from PROCESSING after the claim |
| 13 | `docs/operations/runbooks/bulk-job-stuck.md` | "Mark ABANDONED (operator action)"; restart "resumes from last committed chunk" unconditionally | ABANDONED is set by the sweeper (terminal); restart resume depends on `concurrency=1`; links the new restart runbook |
| 14 | `docs/adr/` | Spec 0001 (priority aging) is not implemented but had no decision record | Added `0013-priority-aging.md` (Proposed, not implemented) and index row; `ADR README` status line updated |
| 15 | `specs/0001-priority-aging.md` | Status "Draft ... verify in source" | Status states "worked example, not implemented in code" (grep of `backend/src` finds no aging logic) |
| 16 | `docs/design/CONTRACT.md` | Presented as the single binding source although partly superseded | One-line banner pointing to `DEVIATIONS.md` and this log; no contract text changed |

## Added files
`docs/operations/runbooks/bulk-job-restart.md` (alert `BulkJobStalled` references it), `docs/operations/runbooks/high-api-latency.md` (four alerts reference it), `docs/design/requirements-and-assumptions.md`, `docs/index.md`, `docs/traceability/job-description-traceability-matrix.md` and `.csv`, `README.md`, `docs/adr/0013-priority-aging.md`, this log.

## Alert runbook check (`observability/prometheus/alerts.yml`)
Every `runbook_url` now resolves to an existing file: `bulk-job-restart.md` (was missing, created), `high-api-latency.md` (was missing, created as a short entry point delegating to `high-latency-and-resource-saturation.md`), `callback-signature-failures.md`, `consumer-crash-ambiguous-outcomes.md`, `database-outage.md`, `dlq-growth.md`, `provider-outage.md`, `provider-rate-limit.md`, `queue-backlog.md`, `redis-outage.md`, `retry-storm.md`. Metric names used by the alerts match the names registered in `MessagingMetrics`.

## Found but not fixed (outside the editable scope)
- `observability/prometheus/alerts.yml`, alert `BulkJobStalled`: description says "restart if STOPPED/FAILED", but a stalled job is normally still `RUNNING`; it must be stopped first (`POST /bulk-jobs/{id}/stop`), then restarted. The runbook covers this; the annotation text is unchanged.
- `docs/api/openapi.yaml` vs the stage-3 additions in `DEVIATIONS.md` (bulk `createRecipients`, `sort`, `Location`, `line_no = 0`): DEVIATIONS says these are not yet in `messaging-api-v1.yaml`; not edited here.
- Frontend `HistoryEntry` model vs the `TimelineEntry` returned by `GET /messages/{id}/history` (listed in DEVIATIONS as a frontend change still needed).
- `docs/interview/**`, `docs/validation/**`, `docs/pdf/**` and `docs/api/**` were not read or edited by this pass; they may still repeat the older plan (for example 409 replay or `POLICY_SUPPRESSED`) and should be re-checked.
- `tests/end-to-end/e2e.mjs`, `tests/api-http/` and `tests/security/` named in CONTRACT section 11 did not exist when this pass ran (only `tests/end-to-end/ui-smoke.md` and `tests/failure-recovery/`); the README mentions `e2e.mjs` and says to see its header.
- Oracle reference DDL lacks the V200 tables (stated in DEVIATIONS and in the traceability matrix).
