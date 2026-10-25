# Runbook: Dead-Letter Queue Growth

## Symptoms and alerts
`DlqGrowth`, `PoisonMessages`; `messaging.queue.dlq.depth` rising; `messaging.deadletter.total{reason}`.

## Impact
Messages not delivered and waiting for human decision. For `RETRIES_EXHAUSTED`, customers missed notifications.

## Diagnose by reason
| Reason | Typical cause | Check |
|---|---|---|
| `RETRIES_EXHAUSTED` | provider outage longer than retry horizon | provider history, `provider-outage.md` |
| `POISON_MESSAGE` | bug, bad deployment, corrupt envelope, message row missing | recent deploy; logs by `messageId`; `GET /api/v1/diagnostics/messages/{id}` |
| `PERMANENT_FAILURE` | invalid addresses, suppressed or auth errors | error code distribution; `GET /api/v1/dead-letters?...` |
| `OPERATOR` | manual action | audit events |
1. `GET $BASE/api/v1/dead-letters?page=0&size=100` then group by reason and provider.
2. `GET $BASE/api/v1/messages/{id}/history` for representative samples.
3. Correlate start time with deploys/config changes.

## Mitigate
- Stop the source: roll back a bad release (`rollback-plan.md`), re-enable provider, fix config.
- For poison caused by a bug: do NOT replay until fixed.

## Recover (replay policy: `data-retention-and-replay-policy.md`)
1. Decide per reason: replay, discard, or leave.
2. Replay in small batches (e.g., 50) with rate awareness: `POST /api/v1/dead-letters/{messageId}/replay` ; verify first batch outcome before continuing.
3. Messages past `expires_at` or whose consent changed will be rejected at replay (policy re-evaluation): expected.
4. Discard (`POST .../discard`) with a reason recorded for unfixable cases (stays `DEAD_LETTERED` with discarded flag).
5. For `DELIVERY_UNKNOWN`-origin messages ensure reconciliation was attempted before replay to avoid duplicates.

## Verify
DLQ depth back to baseline; replayed messages progress to SENT_TO_PROVIDER/DELIVERED; audit shows actor and reason.

## Prevent
Alert on reason mix, canary releases, schema-tolerant envelope, contract tests between publisher and consumer.

Proof: `FailureRecoveryIT#scenario08_poisonMessage_goesToDlq_noInfiniteLoop`, `#scenario01_...`.
