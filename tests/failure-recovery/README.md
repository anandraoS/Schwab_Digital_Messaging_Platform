# Failure-recovery scripts

Black-box counterparts of the in-JVM `FailureRecoveryIT` scenarios (see `docs/architecture/reliability-scenarios.md`).
They drive a **running** application through its public API and the local-only simulator controls, so they work against
`--spring.profiles.active=local` (API on `http://localhost:18080`, dev token endpoint, simulators, in-memory bus).

| Script | Scenario | What it proves |
|---|---|---|
| `provider-outage.mjs` | 1 | transient failures are retried with backoff, then dead-lettered (`RETRIES_EXHAUSTED`), and an operator replay after recovery succeeds |
| `callback-replay.mjs` | 3 | out-of-order, replayed, stale and conflicting callbacks never move a message backwards; forged signatures are rejected |
| `provider-timeout.mjs` | 4 | a provider timeout leaves the message `DELIVERY_UNKNOWN` with exactly one attempt (no blind resend) |

Scenarios 2, 5, 6, 7, 8 and 10 need in-JVM fault injection (clock, datasource, coordination, worker death) and exist only
as `FailureRecoveryIT` tests; scenario 9 (bulk restart) belongs to the Spring Batch module.

```bash
# start the backend (JDK 21): java -jar backend/target/messaging-platform.jar --spring.profiles.active=local
# optional, faster retries: --messaging.dispatch.backoff-base-ms=100 --messaging.dispatch.backoff-cap-ms=1000
node tests/failure-recovery/provider-outage.mjs
node tests/failure-recovery/callback-replay.mjs
node tests/failure-recovery/provider-timeout.mjs      # WAIT_FOR_RECONCILIATION=1 to include the reconciliation pass
```

`BASE_URL` overrides the target. Callbacks are signed with the dummy dev secret from `application-local.yml`
(`CALLBACK_SECRET` overrides it); no real credentials are involved. Nothing here has been run against anything but the
local synthetic environment; results belong in `docs/validation/VALIDATION_REPORT.md` only if executed.
