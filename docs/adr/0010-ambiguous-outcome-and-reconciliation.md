# ADR-0010: Ambiguous outcome handling and reconciliation

Status: Accepted (2026-10-18)

## Context
After a provider call times out, or the process crashes after the call but before persisting the result, we do not know whether the provider accepted the message. Retrying blindly may deliver a duplicate SMS (cost, customer annoyance, for security alerts possibly confusion); never retrying may drop an important message. Also, `SENT_TO_PROVIDER` messages may never get a delivery receipt.

## Decision
Introduce the explicit state `DELIVERY_UNKNOWN` and a reconciliation step; "unknown" is a first-class, visible, alertable state, not an error to hide.

### Triggers into DELIVERY_UNKNOWN
| Trigger | Mechanism |
|---|---|
| Provider `TIMEOUT` / `AMBIGUOUS` outcome | `DispatchService` PROCESSING to DELIVERY_UNKNOWN |
| Consumer crash after provider call | `message_attempt` row written `STARTED` before the call; `StuckMessageSweeper` finds PROCESSING with stale `locked_until` (`messaging.dispatch.lock-ttl`) |
| No receipt within `receipt-timeout` | Sweeper: SENT_TO_PROVIDER to DELIVERY_UNKNOWN |

### Resolution
1. **Late callback** wins first: `DeliveryEventProcessor` applies DELIVERED or FAILED to a DELIVERY_UNKNOWN message (monotonic rank rule).
2. After `messaging.dispatch.reconcile-after`, `ReconciliationService` calls `provider.lookup(idempotencyRef)` (`idempotencyRef = messageId:attemptGroup`):
   | Lookup result | Action |
   |---|---|
   | accepted/delivered | record, transition to DELIVERED (or leave SENT_TO_PROVIDER semantics via DELIVERED only on confirmation) |
   | failed/bounced | FAILED (+ suppression if hard bounce) |
   | not received | **safe retry**: new attempt (QUEUED path via operator-free internal requeue), same `idempotencyRef` group |
   | lookup itself fails/unsupported | stay DELIVERY_UNKNOWN, backoff re-check; escalate to operator after `reconcile.max-checks` (see source) |
3. Channel policy `ambiguousOutcomePolicy`: EMAIL and SMS keep DELIVERY_UNKNOWN until reconciliation; PUSH may retry immediately (collapse key makes it idempotent).
4. Never blind-retry. Never mark DELIVERED without a provider-originated confirmation.

### Conflicting evidence
If a retry was issued after "not received" and a late DELIVERED callback for the first attempt arrives, the first terminal wins; second-attempt callbacks are recorded `applied=false` and audited `DELIVERY_EVENT_CONFLICT`; the duplicate-send is counted for review (a rare, accepted residual risk given provider lookup inaccuracies).

## Alternatives considered
| Option | Why not |
|---|---|
| Always retry on timeout | Duplicate sends |
| Never retry on timeout | Message loss |
| Treat timeout as FAILED | Misleading; customers may still receive it |
| Two-phase send with provider "prepare/commit" | Not offered by real vendors |
| Assume delivered after N minutes | Invented data |

## Consequences
- Positive: honest status; bounded duplicate risk; operators have a filtered list (`GET /api/v1/failed-messages`).
- Negative: some messages sit in DELIVERY_UNKNOWN for minutes; depends on provider `lookup` fidelity.
- Metrics/alerts: transitions into DELIVERY_UNKNOWN (`messaging.transitions{to="DELIVERY_UNKNOWN"}`), age of oldest unknown (see slis-slos).

## Validation
- `FailureRecoveryIT#scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend`, `#scenario05_consumerCrashAfterProviderAccept_sweeperMarksDeliveryUnknown`.
- Flow: `docs/architecture/reconciliation-flow.md`; runbook `docs/operations/runbooks/consumer-crash-ambiguous-outcomes.md`.
