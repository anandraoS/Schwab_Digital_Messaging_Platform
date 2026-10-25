# Debugging Workflows

Symptom-driven recipes for humans and assistants. Always: reproduce or capture evidence first, form a hypothesis, test it, change one thing, add a regression test. Never "fix" by loosening a security control or ADR invariant.

## W1: A message is stuck (not terminal)
1. `GET /api/v1/messages/{id}/history` and `/status`: note status, `status_reason`, attempts, `next_attempt_at`, `scheduled_at`.
2. By status:
   | Status | Likely cause | Next check |
   |---|---|---|
   | VALIDATED, scheduled future | not due / quiet-hours deferral | `scheduled_at`, recipient time zone |
   | VALIDATED, due | outbox publisher stalled | oldest `outbox_event` NEW age; scheduler logs; backpressure |
   | QUEUED | consumers paused/idle/saturated | `GET /ops/queues`, pause state, thread dump |
   | PROCESSING | stale lock/crash | `locked_until`; sweeper running? |
   | RETRY_PENDING | backoff or poller issue | `next_attempt_at` vs now; poller query/index |
   | SENT_TO_PROVIDER | no receipt | provider callbacks; `receipt-timeout`; signature failures |
   | DELIVERY_UNKNOWN | ambiguity | reconciliation lookup logs; `consumer-crash-ambiguous-outcomes.md` |
3. Fix cause; do not hand-edit status in DB (bypasses state machine and audit).

## W2: Duplicate message reported
Count `message_attempt` rows and `idempotencyRef`; check `Idempotent-Replay`; check two different `Idempotency-Key`s from client; check DELIVERY_UNKNOWN retry path and provider idempotency support (see TS-1 in `docs/leadership/troubleshooting-scenarios.md`).

## W3: Test fails intermittently
Suspect: real clock, random, shared H2 db, async not awaited, ordering. Reproduce with repeat (`-Dsurefire.rerunFailingTestsCount` is NOT a fix). Replace sleeps with await conditions; inject Clock; ensure unique db per context; check thread leaks between tests.

## W4: Cross-tenant leak suspected
Reproduce with tenant B token; find repository method without tenant parameter; check admin header path; add matrix test first, then fix.

## W5: Callback returns 401
Compare keyId, secret config, timestamp skew, raw body mutation by proxy; see `docs/operations/runbooks/callback-signature-failures.md`. Never disable verification.

## W6: Slow endpoint
Metric by uri, statement count test, `EXPLAIN`, Hikari metrics, thread dump (`docs/operations/performance-investigation-guide.md`).

## W7: Flyway failure
Read the failing version and error; on H2 vs PG difference check portable-SQL rule; never edit a merged migration: add a new one; local reset: delete local H2 file db if used (in-memory resets on restart).

## W8: Build/CI failure on Windows/Git Bash
Use full JDK path via session settings (never export JAVA_HOME globally); local app on port 18080 (8080 occupied); pass Windows-style paths to node with `cygpath -m`.

## AI-assisted debugging rules
- Provide the assistant: symptom, evidence (ids, metrics), relevant context modules only.
- Ask for hypotheses ranked with evidence needed, not immediate patches.
- Verify claims by running code/tests; reject fixes that add retries/sleeps/catch-all as a first move.
- Record the root cause and the regression test in the PR.
