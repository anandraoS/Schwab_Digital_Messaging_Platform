# Ambiguous Outcome Reconciliation Flow

```mermaid
sequenceDiagram
  autonumber
  participant DS as DispatchService
  participant DB as Database
  participant SW as StuckMessageSweeper
  participant RS as ReconciliationService
  participant PR as Provider.lookup
  participant OP as Operator
  DS->>DB: attempt STARTED, status PROCESSING, locked_until = now + lock-ttl
  DS->>PR: send(idempotencyRef)
  Note over DS,PR: timeout, AMBIGUOUS, or process crash here
  alt timeout or AMBIGUOUS result
    DS->>DB: PROCESSING -> DELIVERY_UNKNOWN (attempt outcome TIMEOUT/AMBIGUOUS)
  else crash
    SW->>DB: find PROCESSING with locked_until < now
    SW->>DB: PROCESSING -> DELIVERY_UNKNOWN (audit STUCK_SWEEP)
  end
  opt SENT_TO_PROVIDER without receipt
    SW->>DB: after receipt-timeout: SENT_TO_PROVIDER -> DELIVERY_UNKNOWN
  end
  loop every sweep, for DELIVERY_UNKNOWN older than reconcile-after
    RS->>PR: lookup(idempotencyRef)
    alt delivered
      RS->>DB: DELIVERY_UNKNOWN -> DELIVERED (event source=reconciliation)
    else failed / bounced
      RS->>DB: DELIVERY_UNKNOWN -> FAILED
    else accepted, not yet delivered
      RS->>DB: stay DELIVERY_UNKNOWN, re-check later (backoff)
    else not received
      RS->>DB: new attempt, same idempotencyRef group -> QUEUED path
    else lookup error
      RS->>DB: stay, increment check count
    end
  end
  RS-->>OP: after max checks, appears in GET /failed-messages for operator decision
  OP->>DB: requeue (FAILED path only) or wait for callback
```

## Decision matrix
| Channel | After TIMEOUT/AMBIGUOUS | Why |
|---|---|---|
| EMAIL | wait for callback, then `lookup`, retry only if "not received" | duplicate emails are visible and reputational |
| SMS | same as EMAIL | duplicate cost and annoyance |
| PUSH | retry allowed (collapse key makes it idempotent) | duplicates collapse on the device |

## Invariants
1. A message in `DELIVERY_UNKNOWN` is never silently retried.
2. Reconciliation never creates DELIVERED without provider-originated evidence (callback or `lookup` result).
3. Late callbacks win over reconciliation (same rank rule); first terminal wins.
4. Every reconciliation action is audited with `lookup` result.

Tests: `FailureRecoveryIT#scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend`, `#scenario05_consumerCrashAfterProviderAccept_sweeperMarksDeliveryUnknown`. ADR-0010. Runbook: `docs/operations/runbooks/consumer-crash-ambiguous-outcomes.md`.
