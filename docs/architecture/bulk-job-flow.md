# Bulk Job Flow (Spring Batch)

```mermaid
sequenceDiagram
  autonumber
  participant U as Client (SENDER + scope messages:bulk)
  participant BC as BulkJobController
  participant BS as BulkJobService
  participant SB as Spring Batch bulkSendJob
  participant R as FlatFileItemReader (streaming)
  participant P as RecipientRowProcessor
  participant W as BulkMessageWriter
  participant NS as NotificationService.submitInternal
  participant DB as Database
  U->>BC: POST /bulk-jobs (multipart CSV, templateCode, channel, category, ratePerSecond)
  BC->>BS: validate size, max rows, authz
  BS->>DB: insert bulk_job CREATED
  BS->>SB: launch async (JobParameters incl. jobId, file path)
  BC-->>U: 202 + job id
  loop chunk of 500
    SB->>R: read rows (never full load)
    R-->>P: row
    P->>P: validate columns, resolve recipient, dedupe (job_id, externalRef)
    P-->>W: valid items / skip bad rows (bulk_job_failure)
    W->>NS: submit per item, key bulk-jobId-externalRef, one tx per chunk
    NS->>DB: message_request VALIDATED + outbox_event
    W->>W: RateLimiter(rate_limit_per_sec)
    SB->>DB: commit chunk, update bulk_job counters, step execution context
  end
  SB->>DB: status COMPLETED or COMPLETED_WITH_ERRORS or FAILED
```

## Properties
- **Restart safety**: deterministic idempotency keys make re-processing a chunk a no-op; `POST /bulk-jobs/{id}/restart` relaunches the same `JobParameters` and resumes from the last committed chunk.
- **Skip policy**: up to `messaging.bulk.skip-limit` invalid rows skipped and recorded; beyond it the job is `FAILED`. Transient DB errors retried 3 times.
- **Bounded concurrency**: `messaging.bulk.concurrency` (default 4), multi-threaded step only behind `SynchronizedItemStreamReader`.
- **Isolation of priorities**: bulk messages default to LOW/NORMAL priority so they cannot starve CRITICAL/HIGH queues; aging (spec 0001) adds starvation protection for LOW.
- **Throttle**: writer-side rate limit plus the normal provider token bucket; consent policy is applied again at dispatch.
- **Progress**: `messaging.bulk.progress{jobId}` gauge; `GET /api/v1/bulk-jobs/{id}` and `/failures` (paged).
- **Stop**: `POST /bulk-jobs/{id}/stop` marks `STOPPED` and the step ends after the current chunk; `ABANDONED` is set by an operator/sweeper for jobs that never finish (see runbook `bulk-job-stuck.md`).
- Schema: `bulk_job`, `bulk_job_failure`, Spring Batch tables (`V100__spring_batch.sql`).
