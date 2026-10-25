# Troubleshooting Scenarios

Six production-style problems with investigation steps. Fictional; derived from the design, not from real incidents.

## TS-1: Customers report SMS arrived twice
**Problem.** Support sees duplicate SMS for 40 recipients in 20 minutes during a provider slowdown.
**Investigation.** (1) Pick a message: `GET /messages/{id}/history`. (2) Count `message_attempt` rows: two attempts, first outcome TIMEOUT. (3) Was status DELIVERY_UNKNOWN in between? If not, retry-on-timeout was introduced (check recent deploy diff for outcome mapping). (4) If lookup said "not received" then the provider's lookup was inaccurate: check `idempotencyRef` echoed by provider in diagnostics. (5) Check whether the provider honors idempotency refs (vendor doc/sandbox).
**Solution.** If code regression: roll back, restore mapping, add test. If provider lookup inaccuracy: increase `reconcile-after`, require callback absence for longer, disable auto-retry for that provider (config), escalate to vendor.
**Trade-offs.** Longer unknown window vs duplicates.
**Regression tests.** `FailureRecoveryIT#scenario04_...`; provider contract test for lookup semantics.

## TS-2: Queue depth grows but consumers look idle
**Problem.** `messaging.queue.depth{NORMAL}` rising, CPU low, in-flight 0.
**Investigation.** `GET /ops/queues`: consumers count; was `POST /ops/consumers/pause` used (audit)? Thread dump: workers `WAITING` on queue.take? Prefetch/ack: Rabbit unacked messages equal prefetch with a stuck worker (deadlock on a lock?). Check scheduler: if the outbox publisher thread died on an unchecked exception, depth would not grow, but retry poller could be dead instead.
**Solution.** Resume consumers (paused by mistake) or fix swallowed exception: scheduler methods must catch and meter; restart instance; add liveness check on scheduler heartbeat.
**Trade-offs.** More heartbeat metrics.
**Tests.** Scheduler resilience test: exception in one tick does not stop subsequent ticks; pause/resume test.

## TS-3: Delivered statuses stopped updating after key rotation
**Problem.** `invalid_signature` spike for one provider since 14:00; many messages SENT_TO_PROVIDER aging to DELIVERY_UNKNOWN.
**Investigation.** Group failures by `keyId`: new keyId unknown (config missing). Confirm rotation time vs deploy. Check clock skew alternative (reason string).
**Solution.** Add new key id secret to config (dual keys), redeploy; ask provider to replay webhooks; reconciliation covers the rest.
**Trade-offs.** Dual-key window increases attack surface briefly.
**Tests.** `SignatureVerifierTest` dual key; rotation test.

## TS-4: POST /messages p99 latency doubled after release
**Problem.** p99 from 120 ms to 300 ms; DB CPU up.
**Investigation.** `http_server_requests_seconds` by uri: only POST /messages. Hikari acquire time up. `pg_stat_statements`: a new `SELECT` per request on `recipient_preference` without index? Or N+1 in policy evaluation (loading all suppressions). `EXPLAIN` the query; check statement count in integration test.
**Solution.** Add `recipient_id` index/unique lookup, fetch preference with recipient via join, cache short-TTL; verify with statement-count test.
**Trade-offs.** Caching staleness (consent changes) vs latency: keep TTL short and always re-check at dispatch.
**Tests.** Statement-count test on submission; perf harness comparison.

## TS-5: Bulk job finished "COMPLETED" but 3% fewer messages than rows
**Problem.** CSV 100,000 rows; `succeeded` 96,800; failures list only 400.
**Investigation.** `processed = succeeded + failed + duplicate`? Duplicates count: file contains repeated `externalRef` (dedupe correct); message count by `bulk_job_id` vs succeeded; look for rows rejected at dispatch policy (OPTED_OUT/SUPPRESSED) which are REJECTED messages, not job failures: expected for marketing. Check `bulk_job.duplicate`.
**Solution.** Explain via breakdown; improve job summary to show policy rejections (query by status) so counts reconcile; no data fix needed.
**Trade-offs.** Extra aggregate query on job detail.
**Tests.** Bulk IT with duplicates + opted-out recipients asserts breakdown arithmetic.

## TS-6: Pods OOM-killed every few hours
**Problem.** Container restarts, `DELIVERY_UNKNOWN` count spikes after each restart.
**Investigation.** Heap histogram before kill (`jcmd GC.class_histogram`), GC log shows old gen growing. Suspect: unbounded in-memory structure (e.g., failure map keyed by dispatchId for poison counting never cleaned; rate-limiter map per tenant). Check `-XX:MaxRAMPercentage`, off-heap (direct buffers from Netty/Lettuce).
**Solution.** Bound/expire the structure (Caffeine with max size and TTL), fix leak, set memory request/limit with headroom, enable heap dump on OOM to secure volume.
**Trade-offs.** Evicting poison counters can delay DLQ decision by one more delivery.
**Tests.** Unit test of cache bounds; soak harness (executed locally if desired) watching heap.
