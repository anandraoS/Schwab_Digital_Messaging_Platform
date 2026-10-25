# Caching and Connection Pools

Two topics that look unrelated and fail in the same way: a small shared resource (a cache entry, a database connection) becomes the bottleneck, and a naive reaction (more retries, bigger pool) makes the outage worse. This chapter gives the vocabulary, the arithmetic and the project's real choices. Nothing here claims a measurement: the reference implementation was never run against a real PostgreSQL, Redis or load generator (see the Honesty note in the front matter).

## Kid-friendly analogies

| Concept | Analogy |
|---|---|
| Cache | A sticky note on the fridge with the pizza shop's phone number. Fast, but if the shop changes its number the note is wrong until you replace it. |
| Cache-aside | You look at the sticky note first; if it is missing you look it up in the phone book and write a new note. |
| Cache stampede | The note falls off and thirty kids run to the phone book at the same moment. The phone book (database) is now crowded. |
| TTL | A "use by" date on the note. |
| Connection pool | A box of ten bicycles for a class of thirty. A kid takes a bike for a short ride and puts it back. If someone rides off for the afternoon, the others wait. |
| Pool exhaustion | All ten bikes are out and every kid is standing in line, even though the road is empty. |
| Pool timeout | "Wait at most five seconds; if no bike comes back, go home." It turns an endless queue into a quick no. |

## What to cache, and what not to

| Candidate | Cache? | Why |
|---|---|---|
| Completed idempotency responses | Yes, short TTL, as a fast path only | A hot replay can skip a database read. Correctness stays in `idempotency_record` (unique key), so a lost or stale entry costs only latency |
| Rate-limit counters and token buckets | Yes (shared store) | They are by nature approximate and time-bound; per-instance fallback is acceptable |
| Template versions by (code, locale, version) | Yes, and safely | A published version is immutable (ADR-0007), so there is nothing to invalidate. A mutable "latest" pointer needs a short TTL or explicit eviction |
| Recipient preferences and suppression | Careful | A stale opt-out is a compliance incident. Prefer short TTL plus a re-check at dispatch time against the database |
| Message status | No | It changes constantly and is the system's truth |
| Authorisation decisions | Only per request, never across users | A cross-tenant leak through a shared cache key is an OWASP API1 failure |

## Caching patterns

| Pattern | Read | Write | Failure mode |
|---|---|---|---|
| Cache-aside | App reads cache, on miss reads DB and fills | App writes DB, then deletes (or updates) the key | Race between a slow reader filling an old value and a writer deleting; mitigated by short TTL |
| Read-through | Cache library loads on miss | Same as above | Same, but loading logic is centralised |
| Write-through | Reads hit cache | Write goes to cache and DB together | Slower writes; the two can diverge if one fails |
| Write-behind | Reads hit cache | Write goes to cache, DB later | Data loss if the cache dies: never for money or messages |

### Stampede (thundering herd) defences

1. Single-flight: one loader per key; other callers wait for it (in Java, `ConcurrentHashMap.computeIfAbsent`, or a library loader such as Caffeine's `LoadingCache`).
2. TTL jitter: add a random 0 to 20 percent to each expiry so keys do not expire together.
3. Stale-while-revalidate: serve the expired value while one thread refreshes it.
4. Negative caching with a short TTL for "not found", so an enumeration attack cannot bypass the cache.
5. Bounded database pool as the last line: the stampede then becomes slow responses and `503`, not a database outage.

### Invalidation

"There are only two hard things in computer science: cache invalidation and naming things." The pragmatic rules: prefer immutable keys (version in the key), prefer a TTL over precise invalidation for non-critical data, and make the database the source of truth so a wrong cache entry can only be a latency or freshness problem, never a correctness problem.

```java
// Cache-aside with single-flight, jittered TTL (illustrative, Caffeine-style)
LoadingCache<TemplateKey, TemplateVersion> cache = Caffeine.newBuilder()
    .maximumSize(10_000)                       // bounded: an unbounded cache is a memory leak
    .expireAfterWrite(Duration.ofMinutes(10))  // published versions are immutable, so this is generous
    .build(key -> repository.findVersion(key.code(), key.locale(), key.version()));
```

> [!NOTE] In this project
> The project's real caching is deliberately small: `CoordinationService` (`backend/src/main/java/com/example/messaging/coordination/`) offers `getCached`/`putCached` for the idempotency fast path and rate-limit state. `RedisCoordination` is the production implementation, `InMemoryCoordination` the local one, and `ResilientCoordination` falls back to memory when Redis throws (cooldown, counter `messaging.redis.fallback`). `ReplayGuard` keeps a bounded in-memory map (`DEFAULT_CACHE_LIMIT = 50_000`) in front of the authoritative `replay_guard` table. Redis was not executed; the in-memory implementation is what the tests exercise.

## Redis as a dependency, not a database

| Question | Answer used by the platform |
|---|---|
| What if Redis is down? | Idempotency goes straight to the database; rate limits become per-instance; requests do not fail |
| What if Redis is flushed? | Cache misses; the unique constraint still prevents duplicates; the database sees extra load, bounded by the pool |
| What if two nodes disagree? | Only completed responses are cached, and the cached copy equals the database record, so disagreement is harmless |
| Latency budget | Short client timeouts (tens of milliseconds) so a slow Redis cannot add seconds to the accept path |

## Connection pools

### Why a pool exists

Opening a database connection costs a TCP and TLS handshake, authentication and a server process or thread. A pool keeps a small set open and lends them for the duration of a transaction. The pool size is a concurrency limit on the database, not a convenience setting.

### Sizing with Little's Law

`connections in use = throughput (transactions per second) x average hold time (seconds)`.

| Example | Throughput | Hold time | Connections in use |
|---|---|---|---|
| Accept path (one short transaction) | 500 tx/s | 8 ms | 4 |
| Accept path when the database is slow | 500 tx/s | 80 ms | 40 (the pool of 20 saturates) |
| Dispatch consumers, 8 workers, 2 short tx per message | 8 x 2 | 10 ms each | well under 8 |
| Bulk chunk writer, 4 workers | 4 | 1 to 2 s per chunk | 4 held for long stretches |

The second row is the lesson: a ten-fold slowdown in the database needs a ten-fold larger pool to keep the same throughput, which the database cannot give. A bigger pool therefore does not fix a slow database; it moves the queue from the pool to the database.

Rules of thumb:
1. Start small (for example 20 per instance), measure `hikaricp_connections_pending` and acquire time, then adjust.
2. `instances x pool size` must stay well below the database `max_connections` (about 70 percent), or put a pooler such as PgBouncer in front.
3. Never hold a connection across a remote call. Dispatch uses short transactions and calls the provider between them, so provider latency cannot drain the pool.
4. Use a short `connection-timeout` so exhaustion becomes a fast, retryable error instead of an unbounded wait.
5. Keep `max-lifetime` below any proxy or database idle kill timeout.
6. Give long-running background work (batch, retention) a small separate pool so it cannot starve the API.

### Reading a pool incident

| Symptom | Likely cause | First check |
|---|---|---|
| `pending` rises, `active` equals max, DB CPU low | Connections held across slow remote calls, or leaked | Thread dump: threads blocked in `HikariPool.getConnection` and the code holding connections |
| `pending` rises, DB CPU high | Slow queries (plan regression, missing index, lock waits) | Slow query log, `EXPLAIN`, lock waits |
| Periodic spikes | A scheduled job (retention, batch) taking connections | Correlate with cron times |
| Errors after idle periods | Connections killed by a firewall or proxy | `max-lifetime`, keepalive |
| Leaks after exceptions | `Connection` not closed outside Spring-managed code | `leak-detection-threshold` in non-production |

> [!NOTE] In this project
> `backend/src/main/resources/application-prod-like.yml` sets `hikari.maximum-pool-size: ${DB_POOL_SIZE:20}` and `connection-timeout: 5000`. `GlobalExceptionHandler` turns `CannotCreateTransactionException` and `DataAccessResourceFailureException` into `503` with `Retry-After: 5` and the advice to retry with the same idempotency key. `docs/database/transactions-and-connection-pooling.md` holds the sizing table. `spring.jpa.open-in-view` is `false`, so a connection is not held for the whole HTTP request. No pool metrics from a real database were captured.

## Where caching and pooling meet

A cache-expiry stampede, a retry storm and a pool of 20 form one failure chain: cache entries expire together, thirty requests miss at once, each takes a connection, the pool saturates, requests time out, clients retry, and the retries miss the cache as well. Breaking the chain needs three controls working together: single-flight and jittered TTL (fewer misses), bounded pool and short timeout (fail fast), and client backoff with jitter plus `Retry-After` (fewer retries).

## Interview Q and A snippets

**Q: Why not just increase the pool size when you see pending connections?**
A: Because pending connections usually mean connections are held too long, not that there are too few. By Little's Law the required pool grows with hold time; if the database is the slow part, extra connections add contention. I would find who holds connections (thread dump, slow queries), fix that, keep the pool bounded and make exhaustion fail fast with a retryable `503`.

**Q: Where would you put a cache in this platform and where would you refuse to?**
A: Immutable template versions and the idempotency fast path are safe because the database remains the truth. I would refuse to cache message status or authorisation across users, because a stale or shared entry becomes either a wrong answer or a cross-tenant leak.

## Key takeaways

- A cache is an optimisation whose failure must only cost latency, never correctness.
- Prefer immutable keys and TTLs over clever invalidation; bound every cache.
- Stampedes are fixed with single-flight, TTL jitter and a bounded database pool.
- Pool size is a concurrency limit; size it by Little's Law, fail fast on exhaustion, never hold a connection across a remote call.
- The project's cache and Redis fallback behaviour is real code; Redis and PostgreSQL pool behaviour under load were not executed.
