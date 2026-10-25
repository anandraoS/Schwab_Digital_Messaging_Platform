# Runbook: Provider Outage

## Symptoms and alerts
`ProviderCircuitOpen`, `ProviderSubmissionSuccessLow`; `messaging.provider.calls{outcome=TRANSIENT_FAILURE|TIMEOUT}` up; `messaging.retry.scheduled` rising; `GET /api/v1/providers` shows `circuitState=OPEN`.

## Impact
Messages on that channel are delayed; CRITICAL may fall back to another channel only if the request listed `fallbackChannels` and consent allows. No data loss: messages sit `RETRY_PENDING`.

## Diagnose
1. `curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/v1/providers` : which provider, `lastError`, `successRate`, `p95LatencyMs`.
2. Provider status page / vendor support channel (real environments).
3. Distinguish: our network (timeouts everywhere) vs vendor (5xx) vs auth (permanent failures, 401/403 mapped to PERMANENT_FAILURE: this would be FAILED, not retry; check `errorCode`).
4. `GET $BASE/api/v1/failed-messages?...` sample reasons.
5. Check credentials expiry/rotation recently done.

## Mitigate
- If another provider exists for the channel: raise its `priority_order` or disable the failing one: `PUT /api/v1/providers/{providerId}/config` (PLATFORM_ADMIN) `{ "enabled": false }`. In-flight retries pick the next provider via `ProviderRegistry`.
- Do not raise concurrency or lower backoff: that worsens the outage (see `retry-storm.md`).
- If outage is long and messages are time-sensitive (OTP): consider operational messaging to stakeholders; expired messages are not sent (expiry).
- Pause bulk jobs (`POST /api/v1/bulk-jobs/{id}/stop`) to preserve capacity for critical traffic.

## Recover
1. When the provider is healthy the breaker half-opens and closes automatically; confirm `circuit.state` 0.
2. Messages in `RETRY_PENDING` continue. Messages that exhausted retries are `DEAD_LETTERED`: list `GET /api/v1/dead-letters`, filter reason `RETRIES_EXHAUSTED`, replay in batches with rate awareness: `POST /api/v1/dead-letters/{messageId}/replay` (consent and expiry re-evaluated; see `data-retention-and-replay-policy.md`).
3. Check `DELIVERY_UNKNOWN` created during the outage; let reconciliation work (`consumer-crash-ambiguous-outcomes.md`).

## Verify
Success rate >= SLO; backlog draining; no growth in DLQ; sample messages reach DELIVERED.

## Escalate
Provider vendor support; platform on-call lead if > 30 min for CRITICAL traffic.

## Prevent
Multi-provider per channel, tested failover, synthetic probes, SLO alerts, vendor SLAs (real project).

Proof of behavior: `FailureRecoveryIT#scenario01_providerOutage_retriesWithBackoff_thenDeadLetters_thenReplay`.
