# Context: Lifecycle
Load when: touching status, retries, DLQ, callbacks, reconciliation, cancel/replay. Authority: CONTRACT 3.1/3.4, ADR-0003/0006/0010.

States: ACCEPTED, VALIDATED, QUEUED, PROCESSING, SENT_TO_PROVIDER, DELIVERED, REJECTED, RETRY_PENDING, FAILED, DEAD_LETTERED, CANCELLED, DELIVERY_UNKNOWN. (`SCHEDULED` is not a state: VALIDATED + future `scheduled_at`.)

| From | To |
|---|---|
| ACCEPTED | VALIDATED, REJECTED |
| VALIDATED | QUEUED, CANCELLED, REJECTED |
| QUEUED | PROCESSING, CANCELLED |
| PROCESSING | SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED |
| RETRY_PENDING | QUEUED, DEAD_LETTERED, CANCELLED |
| SENT_TO_PROVIDER | DELIVERED, FAILED, DELIVERY_UNKNOWN |
| DELIVERY_UNKNOWN | DELIVERED, FAILED |
| FAILED | DEAD_LETTERED, QUEUED (operator) |
| DEAD_LETTERED | QUEUED (operator replay) |
| DELIVERED, REJECTED, CANCELLED | terminal |

Rules:
- Transitions only through `MessageStateMachine` + CAS `UPDATE ... WHERE status=:from`.
- Outcomes: ACCEPTED->SENT_TO_PROVIDER; TRANSIENT/RATE_LIMITED->RETRY_PENDING (backoff `min(cap, base*2^(n-1))*U(0.5,1)`); PERMANENT->FAILED; TIMEOUT/AMBIGUOUS->DELIVERY_UNKNOWN (EMAIL/SMS no blind retry; PUSH may retry).
- Provider acceptance != delivery. Absence of receipt != delivery. Timeout != failure.
- Callback rank: PROVIDER_ACCEPTED/SENT/DEFERRED=1, DELIVERED=3, BOUNCED/FAILED/REJECTED=3; lower never overwrites higher; first terminal wins; conflict audited `DELIVERY_EVENT_CONFLICT`.
- Cancel only from VALIDATED, QUEUED, RETRY_PENDING.
- Crash handling: attempt `STARTED` before provider call; `StuckMessageSweeper` -> DELIVERY_UNKNOWN.
- Poison: DLQ `POISON_MESSAGE` after `poison-threshold`; replay only by operator.
- Every transition: audit + `messaging.transitions{from,to}`.
