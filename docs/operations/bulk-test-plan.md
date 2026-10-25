# Bulk Messaging Test Plan (Spring Batch `bulkSendJob`)

Repeatable plan for functional, failure-recovery and throughput testing of the bulk module
(`com.example.messaging.batch`, `RetentionJob` for housekeeping). All data is synthetic. Numbers appear in this document
**only where they were actually measured**; every such number states the command, date and machine caveat.

## 1. What is tested, and where

| Area | Test | Kind | Runs in |
|---|---|---|---|
| Happy path, validation failures reported (never echoed), duplicates across chunks/threads, skip limit => `FAILED`, retry of a transient DB error, bounded concurrency, writer rate limit, policy-rejected rows | `BulkJobBatchIT` | integration (Spring context, H2, real security chain) | `mvn verify` |
| Multipart upload, role AND scope check with audited 403, tenant isolation (404), size/row/content/UTF-8/line-length limits, hostile file names, stop/restart state rules, orphaned job stop+restart, sweeper `ABANDONED`, failures paging, synthetic jobs | `BulkJobApiIT` | integration | `mvn verify` |
| Reliability scenario 9: crash mid-chunk, only whole chunks committed, restart resumes from the last committed chunk, exactly one message per row | `FailureRecoveryBulkIT#scenario09_bulkJobRestart_resumesWithoutDuplicates` (+ interrupted twice, file removed, uninterrupted reconciliation) | integration, `concurrency=1` | `mvn verify` |
| Same crash with 4 workers: restart re-reads from the top, ledger + idempotency keys still give exactly-once | `BulkJobRestartConcurrentIT` | integration, `concurrency=4` | `mvn verify` |
| Row rules: validation, duplicate detection, recipient/template checks, formula injection | `RowProcessorTest`, `CsvRulesTest` | unit | `mvn test` |
| Upload storage: sanitising, traversal, limits, normalisation, cleanup on failure | `BulkUploadStorageTest` | unit | `mvn test` |
| Ledger/counters/failure records, races, rollback behaviour | `BulkJobStoreTest` (real schema on H2) | unit | `mvn test` |
| Token bucket (fake clock + real threads) | `TokenBucketRateLimiterTest` | unit | `mvn test` |
| Dataset generator: determinism, rates, defect kinds, 1,000,000 rows in a 24 MB heap | `SyntheticDatasetGeneratorTest` | unit | `mvn test` |
| Retention: terminal-state rule, cut-offs, children first, legal hold, dry run, batching/resume, other tables | `RetentionJobTest` (real schema on H2) | unit | `mvn test` |
| Throughput | `BulkThroughputIT` | measurement, **off by default** (`-Dbulk.measure=true`) | on demand |

Maven wrapper command used in this project (JDK 21 and Maven are pinned, nothing is exported globally):

```bash
bash scripts/mvn.sh verify                                   # everything (unit + integration)
bash scripts/mvn.sh test -Dtest='Bulk*Test,RowProcessorTest,CsvRulesTest,SyntheticDatasetGeneratorTest,TokenBucketRateLimiterTest,RetentionJobTest'
bash scripts/mvn.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='BulkJobBatchIT,BulkJobApiIT,FailureRecoveryBulkIT,BulkJobRestartConcurrentIT'
```

## 2. Datasets

| Name | Rows | How | Use |
|---|---|---|---|
| tiny | 10-100 | built inline by the tests (`AbstractBulkIT.csv`) | functional |
| scenario | 60 (55 distinct, 3 duplicates, 2 invalid) | `AbstractRestartIT.scenarioFile()` | restart/exactly-once |
| standard | 20,000 | `SyntheticDatasetGenerator`, dup 2 %, invalid 1 %, seed 5 | throughput (measured, section 5) |
| large | 100,000 (= default `messaging.bulk.max-rows`) | generator CLI | stress, not run for this document |
| huge | 1,000,000 | generator CLI | generator memory test only; running the job needs `messaging.bulk.max-rows` raised |

Generator CLI (streams, constant memory):

```bash
bash scripts/generate-dataset.sh 100000 .run/recipients-100k.csv 0.02 0.01     # fat jar + PropertiesLauncher
# equivalent without the script, from compiled classes:
java -cp backend/target/classes com.example.messaging.batch.SyntheticDatasetGenerator 100000 .run/r.csv 0.02 0.01 --seed=7
```

Options: `[--seed=N] [--refs=synthetic|seed] [--seed-recipients=40] [--start=1] [--vars=a,b,c]`.

* `--refs=synthetic` (default) writes `SYN-0000001...`. These recipients are **not** in the seed. They are created by
  `POST /api/v1/bulk-jobs/generate-synthetic` (idempotent top-up for the caller's tenant; switch off with
  `?createRecipients=false`), or in tests by `SyntheticRecipientProvisioner.ensure(tenant, n)`. A job run on such a
  file *without* provisioning reports every row as `RECIPIENT_NOT_FOUND` (a legitimate failure-path load test; set
  `messaging.bulk.skip-limit` high enough, otherwise the job fails by design after the limit).
* `--refs=seed` writes the seeded `CUST-001..CUST-040` of `tenant-retail` (max 40 rows, references must be unique).
* Defects (probability `invalidRate`): missing reference, unknown recipient (`NOPE-...`), wrong column count, formula
  injection (`=1+1`), 2,500-character field, bad locale. Duplicates (probability `duplicateRate`) repeat one of the last
  1,000 valid references.

## 3. Expected behaviour (pass criteria)

1. Terminal status is `COMPLETED` (no failed rows) or `COMPLETED_WITH_ERRORS`; `FAILED` only if the skip limit was exceeded
   or a non-skippable error happened (then `bulk_job_failure` line 0 says why).
2. `processed = succeeded + failed + duplicate` at the end (and `= total` when the whole file was read).
3. `COUNT(message_request WHERE bulk_job_id = ?) = succeeded + (policy-rejected rows)`; `COUNT(DISTINCT idempotency_key)` equals the
   message count (no duplicate sends).
4. No `idempotency_record` of the job is left `IN_PROGRESS`.
5. `bulk_job_failure` holds one row per rejected line (`line_no`, reason code, SHA-256 of the line); no recipient data.
6. Stop leaves `STOPPED` after the current chunk; restart finishes the file without duplicates.
7. `messaging.bulk.writers.active` never exceeds `messaging.bulk.concurrency`.

## 4. How to run a throughput measurement

```bash
# 20,000 rows, 4 chunk workers, chunk size 500 (the defaults), result appended to backend/target/bulk-measurements.txt
bash scripts/mvn.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=BulkThroughputIT -Dbulk.measure=true -Dbulk.rows=20000 -Dbulk.concurrency=4 -Dbulk.chunk=500
```

The test provisions the recipients, generates the CSV, uploads it through the API, waits for the terminal status and
asserts the invariants of section 3 before it prints one `BULK-MEASUREMENT ...` line. What the number covers: upload,
validation, duplicate detection, template check, one `message_request` + outbox row + audit row + idempotency record per
valid row, counters. It does **not** cover provider dispatch (that is the dispatch pipeline's throughput).

Variables worth sweeping and what to read:

| Variable | Values | Look at |
|---|---|---|
| `messaging.bulk.concurrency` | 1, 2, 4, 8 | rows/s, `hikaricp_connections_active/pending`, lock waits on `bulk_job` |
| `messaging.bulk.chunk-size` | 100, 500, 1000 | rows/s, transaction length, memory |
| `ratePerSecond` of the job | none, 50, 500 | achieved rate vs configured |
| `messaging.bulk.skip-limit` / defect rate | 0 %, 1 %, 5 % | cost of rejected rows (chunk rollback + re-scan) |
| database | H2 (here), PostgreSQL (not run) | everything; H2 numbers are not a capacity statement |

On a running application read: `messaging.bulk.progress{jobId}` (0..1), `messaging.bulk.writers.active`,
`hikaricp_connections_*`, JVM heap, `GET /api/v1/bulk-jobs/{id}` counters, `messaging.queue.depth` of the dispatch queue.

### Manual API flow against a running instance (not executed for this document)

```bash
# 1. dev token with the bulk scope (profile local/test only), then upload
TOKEN=$(curl -s -X POST localhost:18080/dev/token -H 'Content-Type: application/json' \
  -d '{"subject":"bulk-sender","tenant":"tenant-retail","roles":["SENDER"],"scopes":["messages:bulk"]}' | python -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -X POST localhost:18080/api/v1/bulk-jobs -H "Authorization: Bearer $TOKEN" \
  -F file=@.run/recipients-100k.csv -F name=load-100k -F templateCode=TRADE_CONFIRMATION \
  -F channel=EMAIL -F category=TRANSACTIONAL
# 2. poll
curl -s localhost:18080/api/v1/bulk-jobs/<id> -H "Authorization: Bearer $TOKEN"
# 3. synthetic shortcut (creates SYN recipients, generates and runs)
curl -s -X POST localhost:18080/api/v1/bulk-jobs/generate-synthetic -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"rows":20000,"duplicateRate":0.02,"invalidRate":0.01}'
```

## 5. Measured results (actually run)

Machine: Windows 11, 8 logical CPUs, JDK 21 (HotSpot 21.0.7), 4 GB max heap, H2 2.x in memory (PostgreSQL mode), Spring
`test` profile (Hibernate statistics on). **Caveat: the machine was shared with other Maven builds running at the same
time, so absolute numbers are pessimistic and noisy; use them as order of magnitude and for relative comparison only.**
Date 2026-10-18. Provider dispatch not included.

| Rows | Concurrency | Chunk | Result | Rows/s | Notes |
|---|---|---|---|---|---|
| 20,000 (dup 2 %, invalid 1 %) | 4 | 500 | `COMPLETED_WITH_ERRORS`, 19,369 sent, 195 failed, 436 duplicates, 252.1 s | **79** | upload accepted in 1.4 s |
| 3,000 | 1 | 500 | `COMPLETED_WITH_ERRORS`, 65.9 s | 46 | |
| 3,000 | 4 | 500 | `COMPLETED_WITH_ERRORS`, 51.6 s | 58 | scaling 1 -> 4 workers is only 1.26x |
| 5,000 | 4 | 500 | `COMPLETED_WITH_ERRORS`, 4,841 sent, 54 failed, 105 duplicates, 87.4 s | 57 | later snapshot of the backend **with the dispatch pipeline running** (outbox publisher + consumers dispatch the new messages while the job runs) |

The first three rows were taken on a snapshot of the backend that had no outbox publisher yet (messages stayed `VALIDATED`);
the last row is the same measurement after the dispatch module had landed.

Reading the numbers: the batch layer itself is not the bottleneck. A throw-away timing loop (not committed) that called
`NotificationService.submitInternal` directly, with no batch at all, measured 21-53 ms per call on the same machine
(first 200 calls are slower: JIT warm-up), i.e. 20-50 calls/s per thread. A bulk row costs one such call plus a few
small statements, so 46-79 rows/s is what that submission path delivers on H2 with statistics enabled while the machine
is busy. Speed-ups therefore belong in the submission path (statement count per message, template/recipient caching,
a real database, no statistics) rather than in the batch step. The poor scaling with workers points to contention (single
H2 writer, the `bulk_job` counter row, `BATCH_STEP_EXECUTION` updates at every chunk commit); a PostgreSQL run is needed to
say anything about production scaling and was not possible here (no Docker).

Other measured facts:

* `SyntheticDatasetGeneratorTest#oneMillionRows_streamToDisk_inAnEmptyHeap_withinTheTimeBox`: 1,000,000 rows written
  (60+ MB) by a child JVM started with `-Xmx24m` in 6.2 s (the process would die with `OutOfMemoryError` if it kept rows in memory).
* `BulkJobBatchIT#writerRateLimit_pacesTheJob`: 60 rows at `ratePerSecond=50` finish in >= 0.9 s (about 1.1 s expected).

## 6. Not covered

PostgreSQL/Oracle runs, multi-node execution (jobs run on the node that accepted the request; see the module's known
gaps), provider dispatch throughput, k6 (not available here), Testcontainers.
