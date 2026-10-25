# 14. Bulk Messaging with Spring Batch

## 14.1 When bulk is the right tool

A campaign to 100,000 recipients is not 100,000 API calls from a script; it is a **job**: validated, throttled, observable, stoppable and restartable. The platform uses **Spring Batch 5** (JDBC job repository in the same database) with a chunk-oriented step. The batch writer does not talk to providers; it submits each row through the same `NotificationService.submitInternal` path as the REST API, so every bulk message gets the same policy, template pinning, outbox, audit and dispatch behaviour. Bulk adds throughput management, not a second delivery path.

## 14.2 Job design

{{diagram:bulk}}

| Element | Class | Role |
|---|---|---|
| Controller | `web/BulkJobController` | Upload, list, get, failures, stop, restart, generate-synthetic |
| Service | `batch/BulkJobService` | Validation, status bookkeeping, launching, stop, restart |
| Reader | `batch/RecipientCsvReaderFactory`, `BoundedLineReader` | Streams the CSV, never loads it whole; enforces line length and column limits |
| Row validation | `batch/CsvRowValidator`, `RecipientRowProcessor` | Columns, recipient resolution, in-job duplicate detection |
| Writer | `batch/BulkMessageWriter` | One `submitInternal` per item, transaction per chunk, rate limited |
| Rate limiting | `batch/TokenBucketRateLimiter` | Writer-side limit `ratePerSecond` on top of the provider token bucket |
| Listeners | `BulkJobListener`, `BulkSkipListener` | Final status, failure rows |
| Recovery | `BulkExecutionRecovery`, `BulkJobSweeper` | Close orphaned executions; mark stale jobs `ABANDONED` |
| Storage | `BulkUploadStorage`, `BulkJobStore` | Normalised upload files; job counters |
| Test data | `SyntheticDatasetGenerator`, `SyntheticRecipientProvisioner` | Generate N-row CSVs and recipients without any real data |

The CSV header is `externalRef,locale,var_firstName,var_amount,...`; the `var_` columns are the variables of the chosen template. Uploads are normalised to UTF-8 with LF line ends, BOM and trailing blank lines removed, and stored as `<messaging.bulk.upload-dir>/<tenant>/<jobId>.csv` (a server-generated name). Files are deleted when the job completes (`delete-file-on-completion`) and otherwise by `RetentionJob`.

## 14.3 Counters, skips and duplicates

| Rule | Detail |
|---|---|
| Counting | `processed = succeeded + failed + duplicate` |
| Policy rejections | Rows rejected by policy at submission (opt-out, suppression, ...) are persisted as `REJECTED` messages but counted as `failed` with reason `POLICY_REJECTED: <reason>` |
| Duplicates | Filtered (not skipped), recorded in `bulk_job_failure` as `DUPLICATE_EXTERNAL_REF: first seen on line N`; they do **not** count against the skip limit |
| Skip policy | Up to `messaging.bulk.skip-limit` (100) bad rows skipped and recorded; beyond that the job fails with a job-level failure `SKIP_LIMIT_EXCEEDED` (`line_no = 0`) |
| Final status | `COMPLETED_WITH_ERRORS` if and only if `failed > 0`; otherwise `COMPLETED`; `FAILED` when the step fails |
| Transient DB errors | Retried up to `retry-limit` |
| Rolled-back chunk | Leaves no counter and no message by design; details of rows rejected inside it are not kept, the job-level failure row explains why it failed |

## 14.4 Authorization

Bulk is a privileged operation. Create, generate-synthetic, stop and restart need role `SENDER`, `TENANT_ADMIN` or `PLATFORM_ADMIN` **and** the scope `messages:bulk` for every caller, `PLATFORM_ADMIN` included. A refusal is audited as `BULK_JOB_DENIED` and answered 403. Reads (list, get, failures) need one of SENDER, TENANT_ADMIN, PLATFORM_ADMIN, OPERATOR, AUDITOR and are tenant scoped (404 across tenants).

## 14.5 Job states

{{diagram:bulkStates}}

## 14.6 Restart and the exactly-one guarantee

{{diagram:bulkRestart}}

Two mechanisms make a restart safe:

| Mechanism | Mechanics |
|---|---|
| **Deterministic idempotency key** | Each row is submitted with key `bulk-<jobId>-<externalRef>`, so submitting the same row again returns the original message instead of creating a second one |
| **Row ledger** | `bulk_job_row` has primary key `(job_id, external_ref)` plus the line number. A re-seen reference on the **same** line is a replay (safe); the same reference on **another** line is a duplicate |

With `messaging.bulk.concurrency = 1` the reader checkpoints per chunk and a restart resumes from the last committed chunk. With more workers no exact position exists, so the restart re-reads the file from the top and the ledger plus the idempotency key make already processed rows no-ops. With several workers, which occurrence of a duplicated reference wins is not deterministic, but exactly one message is sent either way.

Restart is allowed only from `FAILED` or `STOPPED`; any other status is 409 `ILLEGAL_STATE_TRANSITION`. `stop` on a `RUNNING` job whose batch execution is not alive in this JVM closes the orphaned execution as `STOPPED` so it can be restarted. Jobs run on the node that accepted the request (a single-node assumption for stop and orphan recovery); the sweeper marks jobs with no progress for `stale-after` as `ABANDONED`.

## 14.7 Throughput and fairness

| Control | Default | Why |
|---|---|---|
| `messaging.bulk.concurrency` | 4 | Bounded writers (multi-threaded step behind a synchronised reader) |
| `chunk-size` | 500 | One transaction per chunk |
| `max-rows` | 100000 | Upper bound for one job |
| `max-upload-bytes`, `max-line-length`, `max-columns` | bounded | Reject abusive files before parsing |
| `ratePerSecond` | request parameter, capped by `max-rate-per-second` | Throttle intake so bulk does not flood the queue |
| Message priority | LOW or NORMAL by default | Bulk cannot starve CRITICAL and HIGH |
| `max-parallel-jobs`, `launch-queue-capacity` | bounded | Limit concurrent jobs |

Extra meters: `messaging.bulk.progress{jobId}` (gauge), `messaging.bulk.writers.active`. Audit actions: `BULK_JOB_DENIED`, `BULK_JOB_ABANDONED`, `BULK_JOB_STOPPED`.

> [!NOTE] Synthetic generation
> `POST /api/v1/bulk-jobs/generate-synthetic` creates and submits a job without an upload: it provisions the recipients it needs (`SYN-0000001...`, fictitious `example.test` addresses) for the caller's tenant and generates exactly the variable columns of the chosen template. With `createRecipients=false` every row fails with `RECIPIENT_NOT_FOUND`. `scripts/generate-dataset.sh` writes an N-row CSV from the command line.

## 14.8 Tests that prove it

| Test | Proves |
|---|---|
| `BulkJobBatchIT` | End-to-end job: counters, failures, final status |
| `BulkJobApiIT` | Upload, list, get, failures, authorization and tenant scoping |
| `BulkJobRestartConcurrentIT`, `AbstractRestartIT` | Restart with concurrency, no duplicates |
| `FailureRecoveryBulkIT` | Scenario 9: writer failure mid-chunk, restart, message count equals valid unique rows |
| `CsvRulesTest`, `RowProcessorTest` | CSV rules and row validation |
| `TokenBucketRateLimiterTest` | Throttle |
| `BulkThroughputIT` | In-JVM throughput harness (local only; numbers are in the validation report, not here) |
| `SyntheticDatasetGeneratorTest`, `BulkUploadStorageTest`, `BulkJobStoreTest` | Generator, storage, counters |

## Reference material

{{embed:docs/architecture/bulk-job-flow.md|title=Bulk job flow (design document)|note=Implemented details (row ledger, counting, restart modes, authorization) are in sections 14.3 to 14.6 and DEVIATIONS "Bulk jobs and retention".}}

{{embed:docs/operations/bulk-test-plan.md|title=Bulk test plan}}

{{embed:docs/operations/runbooks/bulk-job-stuck.md|title=Runbook: Bulk job stuck}}

{{embed:docs/operations/runbooks/bulk-job-restart.md|title=Runbook: Bulk job restart}}
