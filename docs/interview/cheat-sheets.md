# Cheat Sheets

One-page tables for last-minute review. Project-specific rows come from `docs/design/CONTRACT.md` (cited as "CONTRACT section X"). General-knowledge rows are standard industry facts. Nothing here claims anything was executed.

## 1. HTTP status codes used (CONTRACT section 5)

| Code | When | Error code or header |
|---|---|---|
| 200 OK | Query success, cancel success, idempotent replay | `Idempotent-Replay: true` on replay |
| 202 Accepted | `POST /messages`, `POST /bulk-jobs` accepted for async processing | `Location` header |
| 400 Bad Request | Validation or malformed body | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `UNSUPPORTED_CHANNEL` |
| 401 Unauthorized | Missing or invalid token | `UNAUTHENTICATED` |
| 403 Forbidden | Authenticated but role or scope lacking | `FORBIDDEN` |
| 404 Not Found | Missing resource, or resource owned by another tenant (not 403, to avoid leaking existence) | `NOT_FOUND`, `TEMPLATE_NOT_FOUND`, `RECIPIENT_NOT_FOUND` |
| 409 Conflict | Illegal state transition, in-flight duplicate, stale optimistic lock | `ILLEGAL_STATE_TRANSITION`, `IDEMPOTENCY_IN_PROGRESS`, `CONFLICT_OPTIMISTIC_LOCK` |
| 413 Payload Too Large | Body over 256 KB | `PAYLOAD_TOO_LARGE` |
| 422 Unprocessable | Same Idempotency-Key with different request hash | `IDEMPOTENCY_KEY_REUSED` |
| 429 Too Many Requests | Per-client limit (default 50 req/s, burst 100) | `RATE_LIMITED`, `Retry-After`, `X-RateLimit-*` |
| 500 Internal Server Error | Unexpected | `INTERNAL_ERROR` |
| 503 Service Unavailable | Backpressure at `messaging.backpressure.max-queue-depth`, dependency down | `SERVICE_UNAVAILABLE`, `Retry-After` |

Callback behaviour as implemented (`docs/design/DEVIATIONS.md`, stage 2, item 7): every signature problem, an unknown provider and a key not bound to the provider all answer the same `401 INVALID_SIGNATURE`. A replayed `X-Event-Id` answers `200 {"result":"replayed"}` (the code `REPLAYED_EVENT` exists but stays unused). An event for an unknown message answers `202 unknown_message`.

Policy rejections at submission (opt-out, suppression, deleted recipient, no address) are persisted as `REJECTED` messages and answered `202` with `status=REJECTED` and `statusReason`; `POLICY_SUPPRESSED` is not returned by the submission path. Database outages and backpressure answer `503` with `Retry-After`; a duplicate-key conflict answers `409 DUPLICATE_RESOURCE`.

Other codes in the error list: `TEMPLATE_VARIABLE_MISSING`, `TEMPLATE_RENDER_ERROR`, `DUPLICATE_RESOURCE` (additive, DEVIATIONS stage 1).

Error body `ApiError`: `timestamp, status, error, code, message, path, correlationId, details[{field,message}]`.

General rules: 4xx means the client should change the request. 5xx means the server failed and retry may help. 401 versus 403: who are you versus what may you do. 202 versus 201: 202 means not done yet.

## 2. Message state machine (CONTRACT section 3.1)

```
ACCEPTED -> VALIDATED -> QUEUED -> PROCESSING -> SENT_TO_PROVIDER -> DELIVERED
```

| From | Allowed to | Actor |
|---|---|---|
| ACCEPTED | VALIDATED, REJECTED | Submission service |
| VALIDATED | QUEUED, CANCELLED, REJECTED | Outbox or poller, API cancel, dispatch-time policy |
| QUEUED | PROCESSING, CANCELLED | Dispatch consumer, API cancel |
| PROCESSING | SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED | Dispatch consumer |
| RETRY_PENDING | QUEUED, DEAD_LETTERED, CANCELLED | Retry scheduler, operator |
| SENT_TO_PROVIDER | DELIVERED, FAILED, DELIVERY_UNKNOWN | Callback processor, sweeper |
| DELIVERY_UNKNOWN | DELIVERED, FAILED | Late callback, `ReconciliationService` |
| FAILED | DEAD_LETTERED, QUEUED | Operator only |
| DEAD_LETTERED | QUEUED | Operator replay only |
| DELIVERED, REJECTED, CANCELLED | none (terminal) | |

Memory hooks: cancel only from VALIDATED, QUEUED, RETRY_PENDING. "Scheduled" is not a status: it is VALIDATED with a future `scheduled_at`. Provider acceptance never implies delivery. Illegal transition gives `IllegalStateTransitionException` and 409 when API-driven.

## 3. Delivery-event rules (CONTRACT section 3.4)

| Rule | Detail |
|---|---|
| Dedup key | Unique (provider_id, provider_event_id) |
| Rank 1 | PROVIDER_ACCEPTED, SENT, DEFERRED |
| Rank 3 | DELIVERED, BOUNCED, FAILED, REJECTED |
| Monotonic | Lower rank never overwrites higher rank |
| Terminal conflict | Keep first terminal, audit `DELIVERY_EVENT_CONFLICT` |
| Old event | Recorded with `applied=false`, `ignored_reason` of OUT_OF_ORDER, STALE, or DUPLICATE |
| DELIVERED early | Applied even when status is PROCESSING, SENT_TO_PROVIDER, or DELIVERY_UNKNOWN |
| SENT after DELIVERED | Recorded, ignored |

## 4. Policy order (CONTRACT section 3.3)

| Step | Rule |
|---|---|
| 1 | Recipient exists for tenant and is not deleted |
| 2 | Global suppression blocks all categories on that channel (REJECTED `SUPPRESSED`) |
| 3 | MARKETING needs channel opt-in and `marketingOptIn=true` (else `OPTED_OUT`). Non-marketing ignores marketing opt-out |
| 4 | Quiet hours default 21:00 to 08:00 recipient time zone. Marketing and LOW/NORMAL SERVICE are deferred. SECURITY_ALERT always sends |
| 5 | Fallback channels re-run steps 1 to 4. Fallback never bypasses consent |
| 6 | Idempotency by tenant plus key |

## 5. Retry formula (CONTRACT section 6)

Reference formula: `next_attempt_at = now + min(cap, base * 2^(n-1)) * jitter`, with jitter drawn from 0.5 to 1.0 (a bounded-jitter variant).

Classic "full jitter" (AWS-style): `sleep = random(0, min(cap, base * 2^n))`. Full jitter spreads retries the most. Bounded jitter keeps a minimum wait. Name the difference if asked.

Worked example, base 1,000 ms, cap 60,000 ms:

| Attempt n | base x 2^(n-1) | After cap | Delay range with jitter 0.5 to 1.0 |
|---|---|---|---|
| 1 | 1,000 | 1,000 | 500 to 1,000 ms |
| 2 | 2,000 | 2,000 | 1,000 to 2,000 ms |
| 3 | 4,000 | 4,000 | 2,000 to 4,000 ms |
| 4 | 8,000 | 8,000 | 4,000 to 8,000 ms |
| 5 | 16,000 | 16,000 | 8,000 to 16,000 ms |
| 6 | 32,000 | 32,000 | 16,000 to 32,000 ms |
| 7 | 64,000 | 60,000 | 30,000 to 60,000 ms |

Rules: retry only TRANSIENT_FAILURE and RATE_LIMITED. Never blindly retry TIMEOUT or AMBIGUOUS for EMAIL/SMS (wait and reconcile). A token-bucket-empty requeue does not consume an attempt. Exhausted attempts lead to fallback channel (if consent allows) or DEAD_LETTERED(`RETRIES_EXHAUSTED`). Config keys: `messaging.dispatch.{max-attempts,backoff-base-ms,backoff-cap-ms}`.

## 6. Provider outcome mapping (CONTRACT section 6)

| Outcome | Next state |
|---|---|
| ACCEPTED | SENT_TO_PROVIDER |
| TRANSIENT_FAILURE, RATE_LIMITED | RETRY_PENDING, backoff |
| PERMANENT_FAILURE | FAILED (hard bounce adds suppression, fallback if allowed) |
| TIMEOUT, AMBIGUOUS | DELIVERY_UNKNOWN for EMAIL/SMS (reconcile). PUSH may retry |
| Poison envelope | DLQ with POISON_MESSAGE |

## 7. Database isolation levels and anomalies (standard SQL, general knowledge)

| Level | Dirty read | Non-repeatable read | Phantom read | Notes |
|---|---|---|---|---|
| READ UNCOMMITTED | possible | possible | possible | PostgreSQL treats it as READ COMMITTED |
| READ COMMITTED | no | possible | possible | Default in PostgreSQL and Oracle |
| REPEATABLE READ | no | no | possible in the standard | PostgreSQL uses snapshot isolation here, phantoms not seen but write skew possible. MySQL InnoDB default |
| SERIALIZABLE | no | no | no | PostgreSQL uses SSI and may abort with serialization failure, so retry the transaction. Oracle SERIALIZABLE is snapshot-based |

Anomaly one-liners: dirty read sees uncommitted data. Non-repeatable read gives a different value for the same row on re-read. Phantom gives a different row set for the same query. Lost update is two writers overwriting each other (fix with optimistic `version`, `SELECT ... FOR UPDATE`, or compare-and-set). Write skew is two transactions reading overlapping data and writing disjoint rows that together break an invariant.

Project hooks: optimistic locking with `version` on tables marked with a diamond in CONTRACT section 4 (409 `CONFLICT_OPTIMISTIC_LOCK`), CAS updates such as `UPDATE ... WHERE status=...` for outbox claim and QUEUED to PROCESSING (CONTRACT sections 4 and 6).

## 8. Kafka versus RabbitMQ

| Aspect | RabbitMQ (chosen, ADR-0001) | Kafka |
|---|---|---|
| Model | Smart broker, push to consumers, queues and exchanges | Distributed log, consumers pull and track offsets |
| Ordering | Per queue, weakened by multiple consumers and requeue | Per partition, strong |
| Priorities | Native (`x-max-priority=9`) | None (use separate topics) |
| Per-message TTL, delay, DLX | Native | Not native (retry topics) |
| Replay | No (consumed messages are gone) | Yes, retention-based |
| Throughput | Tens of thousands per second per node | Hundreds of thousands to millions per second |
| Consumer scaling | Competing consumers on a queue | Bounded by partition count |
| Ops complexity | Lower | Higher (cluster, partitions, rebalancing) |
| Best for | Task dispatch, priorities, retry and DLQ, per-message control | Event streaming, replay, analytics, huge fan-out |
| Delivery | At-least-once with acks, publisher confirms | At-least-once default, exactly-once within Kafka transactions |

## 9. Cloud Foundry versus Kubernetes

| Aspect | Cloud Foundry | Kubernetes |
|---|---|---|
| Abstraction | Application platform (`cf push` of code or a droplet) | Container orchestrator (you ship images and manifests) |
| Packaging | Buildpacks, droplet | Container image, YAML or Helm |
| Config | `manifest.yml`, env vars, user-provided services | ConfigMap, Secret, Helm values |
| Scaling | `cf scale`, instances and memory | Deployment replicas, HPA |
| Service binding | Marketplace service instances, `VCAP_SERVICES` | Operators, external services, secrets |
| Networking | Routes, GoRouter, app-to-app policies | Service, Ingress, NetworkPolicy |
| Health | Health check types (port, http, process) | Liveness, readiness, startup probes |
| Flexibility | Lower, faster developer path | Higher, more operational burden |
| Rollout | Blue-green with route mapping | Rolling update, canary via tooling |

Note: CF and K8s assets in the repo are statically validated only and marked "not executed" (CONTRACT section 0).

## 10. OAuth2 and OIDC flows

| Flow | Use | Key points |
|---|---|---|
| Authorization Code + PKCE | Browser SPA, mobile, any public client | User login at the identity provider, code exchanged with PKCE verifier. Current best practice for SPAs |
| Authorization Code (confidential client) | Server-side web app | Client secret on back channel |
| Client Credentials | Service to service | No user. Used by `ROLE_SENDER` service clients |
| Refresh Token | Renew access | Rotate and bind. Short access token lifetime |
| Device Code | TVs, CLI | User approves on another device |
| Implicit, Resource Owner Password | Deprecated | Do not use |

Resource server checklist (Spring Security 6): validate signature (RS256, JWKS or public key), `iss`, `aud`, `exp`, `nbf`. Map `roles` claim to authorities. Enforce tenant claim on every query. Callbacks are different: HMAC signature, not JWT. Reference claims: `sub`, `roles`, `tenant`, `scope` (CONTRACT section 1). OIDC adds an ID token and user info on top of OAuth2.

## 11. JVM flags (Java 21, general knowledge)

| Flag | Meaning |
|---|---|
| `-Xms` / `-Xmx` | Initial and maximum heap. Often set equal in containers |
| `-XX:MaxRAMPercentage=75.0` | Heap as share of container memory (container aware) |
| `-XX:+UseG1GC` | G1, default collector on server-class machines |
| `-XX:+UseZGC -XX:+ZGenerational` | Low-pause generational ZGC (generational is default in newer JDKs) |
| `-XX:MaxGCPauseMillis=200` | G1 pause target (a goal, not a guarantee) |
| `-Xlog:gc*:file=gc.log` | Unified GC logging |
| `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...` | Heap dump on OOM |
| `-XX:MaxMetaspaceSize` | Cap class metadata |
| `-Xss` | Thread stack size |
| `-XX:+ExitOnOutOfMemoryError` | Crash so the orchestrator restarts the pod |
| `-XX:NativeMemoryTracking=summary` | Track native memory |
| `-Djava.security.egd=file:/dev/urandom` | Faster entropy on old setups (legacy) |
| `-XX:+AlwaysPreTouch` | Touch heap at start (trade start time for steady latency) |

Tools: `jcmd`, `jstack`, `jmap`, `jfr` (Java Flight Recorder), VisualVM, async-profiler.

## 12. SQL pitfalls

| Pitfall | Why it hurts | Fix |
|---|---|---|
| `SELECT *` | Extra IO, breaks on column change | List columns |
| N+1 queries | One query per child row | Fetch join, `@EntityGraph`, projections |
| Function on indexed column (`WHERE LOWER(email)=...`) | Index not used | Functional index or store normalised |
| Leading wildcard `LIKE '%x'` | Full scan | Full-text index or reverse index |
| `OFFSET` deep paging | Scans and discards rows | Keyset (seek) pagination |
| Implicit type conversion | Index skipped | Match types |
| `NOT IN` with NULLs | Returns nothing | `NOT EXISTS` |
| `COUNT(*)` on huge tables | Slow | Estimates, counters, or summary tables |
| Missing index on foreign key | Slow joins and lock escalation | Index it |
| Over-indexing | Slower writes | Index for real queries only |
| Long transactions | Lock holding, bloat, replication lag | Short transactions, chunking |
| Read-modify-write without lock | Lost update | Version column, CAS, `FOR UPDATE` |
| `UNIQUE` with NULL semantics differing by database | Surprising duplicates | Know your engine |
| Oracle: empty string equals NULL | Logic bugs when porting | See oracle mapping doc in `docs/database/oracle-mapping.md` |
| Cartesian join (missing ON) | Row explosion | Review join predicates |
| Timestamps without time zone policy | Wrong times | Store UTC, convert at the edge |

Portable-SQL rule from CONTRACT section 1: no partial indexes, JSONB, or `ON CONFLICT`, so migrations run on both PostgreSQL and H2 in PostgreSQL mode.

## 13. Spring annotations

| Annotation | Purpose |
|---|---|
| `@SpringBootApplication` | Config, auto-config, component scan |
| `@Configuration`, `@Bean` | Java bean definitions |
| `@Component`, `@Service`, `@Repository` | Stereotypes (`@Repository` adds exception translation) |
| `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping` | Web endpoints |
| `@PathVariable`, `@RequestParam`, `@RequestBody`, `@RequestHeader` | Request binding |
| `@Valid`, `@Validated` | Bean validation |
| `@ControllerAdvice`, `@ExceptionHandler` | Central error mapping (`GlobalExceptionHandler`) |
| `@Transactional` | Transaction boundary (proxy based, self-invocation bypasses it) |
| `@Profile` | Conditional beans (for example `rabbit` profile) |
| `@ConfigurationProperties` | Typed config (`PropertiesConfig` records) |
| `@ConditionalOnProperty` | Switch beans by property (`messaging.bus.type`) |
| `@Scheduled`, `@EnableScheduling` | Periodic jobs (outbox, sweeper) |
| `@Async`, `@EnableAsync` | Async execution on an executor |
| `@EntityGraph`, `@Query` | Spring Data fetch and custom query |
| `@Version` | JPA optimistic locking |
| `@PreAuthorize`, `@EnableMethodSecurity` | Method-level authorization |
| `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest` | Test slices |
| `@Qualifier`, `@Primary` | Disambiguate beans |
| `@Retryable`, `@CircuitBreaker` | Retry and Resilience4j (when those libraries are present) |

Gotchas: `@Transactional` on private methods or self-calls does nothing. Default rollback is for unchecked exceptions only. `readOnly=true` is a hint. Lazy loading outside a transaction throws `LazyInitializationException`. Prefer constructor injection.

## 14. Metrics names (CONTRACT section 8, keep exactly)

| Metric | Type | Labels |
|---|---|---|
| `messaging.messages.accepted` | counter | channel, priority, tenant |
| `messaging.transitions` | counter | from, to |
| `messaging.dispatch.delay` | timer | accepted to provider submit |
| `messaging.provider.calls` | counter | provider, channel, outcome |
| `messaging.provider.latency` | timer | provider |
| `messaging.provider.circuit.state` | gauge | provider (0 closed, 1 half-open, 2 open) |
| `messaging.queue.depth` | gauge | priority |
| `messaging.queue.inflight` | gauge | |
| `messaging.queue.dlq.depth` | gauge | |
| `messaging.retry.scheduled` | counter | channel |
| `messaging.deadletter.total` | counter | reason |
| `messaging.callbacks` | counter | result (accepted, duplicate, invalid_signature, replayed, out_of_order, stale) |
| `messaging.bulk.progress` | gauge | jobId |
| `messaging.idempotency.hits` | counter | |
| `messaging.ratelimit.rejected` | counter | scope |
| `messaging.redis.fallback` | counter | |
| `messaging.diagnostics.write.failures` | counter | |

Plus Spring defaults such as `hikaricp_connections_*` and `http_server_requests_seconds`.

Golden signals mapping: latency (`messaging.dispatch.delay`, `messaging.provider.latency`), traffic (`messaging.messages.accepted`), errors (`messaging.provider.calls{outcome}`, `messaging.deadletter.total`), saturation (`messaging.queue.depth`, `hikaricp_connections_active`).

## 15. Quick reference: limits and defaults (CONTRACT sections 5, 6, 9)

| Item | Value |
|---|---|
| Body limit | 256 KB |
| API rate limit | 50 req/s, burst 100 |
| Page size | at most 200 |
| Consumer concurrency | 8 (config `consumer.concurrency`) |
| Prefetch | 16 |
| In-memory queue capacity | 5,000 |
| Outbox poll | 200 ms |
| Graceful shutdown | 30 s |
| Callback tolerance | 5 minutes |
| Bulk chunk size | 500 |
| Bulk concurrency | 4 |
| Local API port | 18080 (8080 is occupied on the authoring machine) |
