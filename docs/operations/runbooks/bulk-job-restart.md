# Runbook: Bulk Job Restart (stalled, stopped or failed job)

Referenced by the `BulkJobStalled` alert (`observability/prometheus/alerts.yml`). Companion triage document: `bulk-job-stuck.md`. Written from the implemented behaviour recorded in `docs/design/DEVIATIONS.md` (section "Bulk jobs and retention") and the code in `backend/src/main/java/com/example/messaging/batch/BulkJobService.java`, not from a real incident. Set `BASE` and `TOKEN` as described in `README.md` of this folder.

## Symptoms and alerts
- Alert `BulkJobStalled`: `messaging_bulk_progress` for a `jobId` unchanged for 10 minutes while between 0 and 1.
- `GET $BASE/api/v1/bulk-jobs/{id}` shows `RUNNING` with counters not moving, or `FAILED` / `STOPPED`.
- `GET $BASE/api/v1/bulk-jobs/{id}/failures` contains a job-level row (`line_no = 0`), e.g. `SKIP_LIMIT_EXCEEDED` or `STEP_FAILED: <exception type>`.

## Who may act
`stop` and `restart` need role SENDER, TENANT_ADMIN or PLATFORM_ADMIN **and** the token scope `messages:bulk` for every caller, PLATFORM_ADMIN included. A refusal is answered 403 and audited as `BULK_JOB_DENIED`. Reads are tenant scoped (another tenant's job is 404). A PLATFORM_ADMIN needs an `X-Tenant-Id` header to act on a tenant's job.

## State rules (enforced in `BulkJobService`)
| Action | Allowed from | Otherwise |
|---|---|---|
| `POST /api/v1/bulk-jobs/{id}/stop` | `RUNNING` (with a batch execution id) | 409 `ILLEGAL_STATE_TRANSITION` |
| `POST /api/v1/bulk-jobs/{id}/restart` | `FAILED` or `STOPPED` | 409 `ILLEGAL_STATE_TRANSITION` |
| neither | `COMPLETED`, `COMPLETED_WITH_ERRORS`, `ABANDONED` (terminal for the API) | 409 |

Restart also needs the uploaded CSV to still exist (`<messaging.bulk.upload-dir>/<tenant>/<jobId>.csv`). The file is deleted when a job completes (`delete-file-on-completion`) and later by `RetentionJob`; if it is gone the restart is refused with 409 and you must submit a new job (see "File gone" below). If too many bulk jobs are already queued the restart answers 503 with `Retry-After: 30`.

## Diagnose
1. `GET $BASE/api/v1/bulk-jobs/{id}`: status, `total/processed/succeeded/failed/duplicate`.
2. `GET $BASE/api/v1/bulk-jobs/{id}/failures?page=0&size=100`: row reasons. `line_no = 0` is the job-level cause.
3. Is the job simply slow? Throughput is bounded by `ratePerSecond`: 100,000 rows at 50/s is about 33 minutes. A flat counter is only a problem if it stays flat.
4. Is the node that accepted the job still alive? Jobs run on the node that accepted the request (single-node assumption). If that process died the job stays `RUNNING` in the database although no worker runs it.
5. Backpressure or database trouble: `GET $BASE/api/v1/ops/queues`, `database-outage.md`.
6. `messaging.bulk.writers.active` (gauge) shows whether writers are active.

## Mitigate and recover
| Situation | Action |
|---|---|
| Job `RUNNING`, worker alive but wedged | `POST .../stop`, wait for `STOPPED`, then `POST .../restart` |
| Job `RUNNING` but its node died (orphan) | `POST .../stop`: the orphaned execution is closed as `STOPPED` and audited with `orphaned=true`; then `POST .../restart` |
| Job `FAILED` after a transient cause (DB blip, backpressure) | fix the cause, then `POST .../restart` |
| Job `FAILED` with `SKIP_LIMIT_EXCEEDED` | the file has too many bad rows: fix the file and submit a **new** job. A new job has new idempotency keys (`bulk-<newJobId>-<externalRef>`), so rows already sent by the old job would be sent again: remove them from the new file first |
| Job `ABANDONED` (sweeper: no progress for `messaging.bulk.stale-after`, default 30m) | terminal: cannot be restarted. Submit a new job with the unsent rows |
| File gone | submit a new job; use the messages list filtered by the old job's `bulk_job_id` to find what was already sent |
| Wrong audience or content | `stop`, then cancel remaining messages that are still `VALIDATED`/`QUEUED`/`RETRY_PENDING` with `POST /api/v1/messages/{id}/cancel` |

### What a restart does (and does not) guarantee
- With `messaging.bulk.concurrency = 1` the reader checkpoints per chunk and the restart resumes from the last committed chunk.
- With more workers there is no exact position: the restart re-reads the file from the top. The `bulk_job_row` ledger and the idempotency key `bulk-<jobId>-<externalRef>` turn already processed rows into no-ops, so each `externalRef` is sent at most once. Which occurrence of a duplicated reference wins is not deterministic in that case (exactly one is sent either way).
- A chunk that was rolled back leaves no counter and no message by design; details of rows rejected inside a rolled-back chunk are not kept.

## Verify
- Status moves to `RUNNING` then a terminal state; `processed` increases.
- At completion `processed = succeeded + failed + duplicate`; `COMPLETED_WITH_ERRORS` iff `failed > 0`. Policy rejections (opt-out, suppression) count as `failed` with reason `POLICY_REJECTED: <reason>`.
- No duplicates: the number of `message_request` rows with the job's `bulk_job_id` equals `succeeded + (policy-rejected rows)`; there is exactly one message per `externalRef`.
- Audit trail shows `BULK_JOB_STOPPED` / `BULK_JOB_RESTARTED` (`GET $BASE/api/v1/audit-events?entityType=BULK_JOB&entityId={id}`).

## Escalate
Platform on-call if the restart answers 409 "cannot be restarted: ..." repeatedly or the batch repository tables (`BATCH_*`) look inconsistent.

## Prevent
Chunked commits, deterministic idempotency keys, skip limit, `max-rows`, writer rate limit, stale-job sweeper, `ratePerSecond` sized to the provider limits.

## Evidence in the repository
`FailureRecoveryBulkIT#scenario09_bulkJobRestart_resumesWithoutDuplicates`, `FailureRecoveryBulkIT#interruptedTwice_stillExactlyOncePerRow`, `FailureRecoveryBulkIT#restartOfAFailedJobWhoseFileWasRemoved_isRefused`, `BulkJobRestartConcurrentIT#multiThreadedJob_restartedFromTheTop_producesNoDuplicates`, `BulkJobApiIT#stopAndRestart_followTheStateRules`, `BulkJobApiIT#orphanedRunningJob_canBeStoppedThenRestarted`, `BulkJobApiIT#sweeper_abandonsStaleJobs_only`. Executed results, if any, are recorded only in `docs/validation/VALIDATION_REPORT.md`.
