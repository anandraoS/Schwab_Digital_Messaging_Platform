# Runbook: Bulk Job Stuck or Needs Restart

## Symptoms
`messaging.bulk.progress{jobId}` flat; job `RUNNING` but `processed` not changing; job `FAILED` after skip limit; `GET /api/v1/bulk-jobs/{id}` shows stale `updated_at`.

## Diagnose
1. `GET $BASE/api/v1/bulk-jobs/{id}` : status, counters, `spring_batch_execution_id`.
2. `GET $BASE/api/v1/bulk-jobs/{id}/failures?page=0&size=100` : row-level reasons (invalid columns, unknown recipients, duplicates).
3. Is the app instance that was running the job alive? Instance restarted mid-job leaves Spring Batch execution `STARTED` (zombie) until marked failed.
4. Are writer-side throttles slow (`rate_limit_per_sec`)? Throughput = rate; 100k rows at 50/s = ~33 min (normal, not stuck).
5. Is backpressure (503 `max-queue-depth`) blocking the writer? Check queue depth.
6. DB: pool saturation or lock waits (`database-outage.md`).
7. Thread dump for the batch executor threads.

## Mitigate / Recover
| Situation | Action |
|---|---|
| Job exceeded skip limit due to bad file | Fix file; submit a new job (new job id => new idempotency keys, careful not to double-send rows already sent: use only unsent rows; failures endpoint lists rows) |
| Instance died mid-run | `POST /api/v1/bulk-jobs/{id}/stop` if still RUNNING/zombie (marks STOPPED/FAILED), then `POST /api/v1/bulk-jobs/{id}/restart` (only FAILED or STOPPED jobs can be restarted; step-by-step in `bulk-job-restart.md`): with `bulk.concurrency=1` it resumes from the last committed chunk, with more workers it re-reads the file and the ledger plus idempotency keys `bulk-<jobId>-<externalRef>` prevent double submission |
| Backpressure | wait or lower job rate; restart after queue drains |
| Wrong content/audience sent | `stop`, then cancel remaining messages: filter messages by `bulk_job_id` and cancel those in VALIDATED/QUEUED/RETRY_PENDING (`POST /api/v1/messages/{id}/cancel`) |
| Job permanently unusable | no API action: the sweeper marks a job without progress for `messaging.bulk.stale-after` (default 30m) as `ABANDONED` (audited `BULK_JOB_ABANDONED`, terminal); submit a new job with the unsent rows |

## Verify
After restart: counters advance, `processed = succeeded + failed + duplicate` at completion, `COUNT(message_request WHERE bulk_job_id=?)` equals succeeded count (no duplicates).

## Prevent
Chunked commits, deterministic keys, skip limit, max rows, throttles, bulk priority LOW, startup sweeper for zombie executions (see source).

Proof: `FailureRecoveryIT#scenario09_bulkJobRestart_resumesWithoutDuplicates`.
