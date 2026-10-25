# Runbook: Consumer Crash and Ambiguous Outcomes (DELIVERY_UNKNOWN)

## Symptoms and alerts
Instances restarted/OOM-killed; growth in `messaging.transitions{to="DELIVERY_UNKNOWN"}`; `GET /api/v1/failed-messages` lists DELIVERY_UNKNOWN; `DeliveryUnknownAging`.

## Impact
Some messages may have been sent but state is unknown. Risk assessment: duplicates if retried blindly (we do not), delays if left unresolved.

## Diagnose
1. How many and since when: `GET $BASE/api/v1/failed-messages?status=DELIVERY_UNKNOWN` (filters: see OpenAPI).
2. Causes: crash (stale `locked_until`, attempt `STARTED` with no outcome), provider timeouts (attempt outcome TIMEOUT/AMBIGUOUS), missing receipts (SENT_TO_PROVIDER aged out).
3. For a sample: `GET /api/v1/messages/{id}/history` and `GET /api/v1/diagnostics/messages/{id}` (best-effort).
4. Instance cause: `kubectl logs --previous` / `cf logs --recent` (not executed here); OOM? (see `high-latency-and-resource-saturation.md`).

## Mitigate
- Fix the crash cause first (memory limits, poison, bug).
- Ensure the sweeper and reconciliation are running (`StuckMessageSweeper`, `ReconciliationService` logs, schedule enabled).
- Do NOT bulk-requeue DELIVERY_UNKNOWN as FAILED. The `requeue` endpoint applies to FAILED/DEAD_LETTERED only by design.

## Recover
1. Wait for callbacks (late receipts move to DELIVERED/FAILED).
2. After `reconcile-after`, reconciliation calls `lookup(idempotencyRef)`:
   | Result | Outcome |
   |---|---|
   | delivered / failed | state updated |
   | not received | automatic safe retry (single new attempt) |
   | unavailable | re-check with backoff |
3. Items still unknown after max checks: operator decides per business value (security alert: consider resend via a fresh message with explicit audit, accepting a duplicate risk; marketing: leave).
4. Record decisions in audit (`OPERATOR` actions).

## Verify
DELIVERY_UNKNOWN count falls; no spike in duplicate provider calls (compare `message_attempt` rows per message with same `idempotencyRef`); SLI 8 within target.

## Prevent
Attempt row before provider call, lock TTL tuned, graceful shutdown (30 s), memory limits and heap sizing, provider idempotency refs, runbook drills.

Proof: `FailureRecoveryIT#scenario04_...`, `#scenario05_consumerCrashAfterProviderAccept_sweeperMarksDeliveryUnknown`, ADR-0010.
