# Performance Review Checklist

Guide: `docs/operations/performance-investigation-guide.md`.

## Request path (POST /messages)
- [ ] No provider/remote call on the request thread; DB round trips counted and minimal (target <= ~6 statements; verify via statement-count test).
- [ ] No N+1; list endpoints use projections/EntityGraph and paging; no unbounded `findAll`.
- [ ] Indexes support each new query (plan reviewed; index cost on `message_request` justified).
- [ ] JSON payload bounded (256 KB); no per-request heavy allocation or regex with backtracking risk.
## Dispatch path
- [ ] Transactions short; none across provider calls; pool usage within `dispatch.concurrency` + margin.
- [ ] Provider executor bounded with timeouts; rejection mapped to retry without losing messages.
- [ ] Rate limiter and retries do not amplify load (jitter, shared bucket).
- [ ] Per-message work O(1) in table size.
## Memory/GC
- [ ] No unbounded caches/maps/queues; caches have size+TTL; streaming for CSV; no large byte[] retention.
- [ ] Logging cost acceptable (no string building when level disabled; async appender if needed).
## Threads
- [ ] Named bounded pools; no blocking calls on scheduler threads; no lock held across I/O.
## Database
- [ ] Hikari pool sizing vs instances x pool <= max connections headroom; `connection-timeout` fail-fast.
- [ ] Retention chunked; large deletes avoided in peak.
## Queue
- [ ] Prefetch/concurrency consistent; priority behavior and starvation considered (aging).
## Measurement
- [ ] Before/after numbers from an executed local harness (record command/hardware) or state "not measured".
- [ ] Alerts/dashboards for new bottleneck indicators.
