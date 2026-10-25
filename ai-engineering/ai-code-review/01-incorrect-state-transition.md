# 1. Incorrect State Transition

Invariants: 1 (state machine + CAS), 5 (acceptance != delivery).

## Flawed code
```java
// CallbackService
public void onReceipt(DeliveryEvent e) {
    MessageRequest m = repo.findById(e.messageId()).orElseThrow();
    if (e.type() == DeliveryEventType.SENT) {
        m.setStatus(MessageStatus.DELIVERED);      // "sent" treated as delivered
    }
    repo.save(m);
}
```
## Why it is wrong
- `SENT`/`PROVIDER_ACCEPTED` are rank 1: they mean provider acceptance, not delivery.
- Direct `setStatus` bypasses `MessageStateMachine` (a message in `CANCELLED` or already `FAILED` can become DELIVERED), no audit, no metric.
- No dedupe on `(provider_id, provider_event_id)`.

## Corrected version
```java
@Transactional
public ApplyResult onReceipt(DeliveryEvent e) {
    if (!deliveryEvents.insertIfAbsent(e)) return ApplyResult.duplicate();
    MessageRequest m = repo.findByIdAndProvider(e.messageRef()).orElse(null);
    if (m == null) return deliveryEvents.markIgnored(e, "UNKNOWN_MESSAGE");
    Optional<MessageStatus> target = eventRules.targetStatus(m.getStatus(), e); // rank rule + table
    if (target.isEmpty()) return deliveryEvents.markIgnored(e, "OUT_OF_ORDER");
    int updated = repo.casStatus(m.getId(), m.getStatus(), target.get(), clock.instant());
    if (updated == 0) return reevaluate(e);   // lost a race: reload and re-apply rank rule once
    audit.transition(m, target.get(), e); metrics.transition(m.getStatus(), target.get());
    return ApplyResult.applied();
}
```
`eventRules.targetStatus` returns DELIVERED only for DELIVERED events from `SENT_TO_PROVIDER`, `PROCESSING` or `DELIVERY_UNKNOWN`; SENT/ACCEPTED events never produce DELIVERED.

## Rationale
The transition table is the contract between actors; centralizing it makes illegal states unrepresentable and gives audit/metrics for free.

## Regression tests
- `MessageStateMachineTest#allPairsMatchContractTable` (matrix).
- `DeliveryEventProcessorTest#sentEvent_doesNotMakeDelivered`, `#deliveredAfterCancelled_isIgnored`.
- `FailureRecoveryIT#scenario03_duplicateAndOutOfOrderCallbacks_applyMonotonically`.
- Structural: a test that fails if any class outside the transition service calls `setStatus`.
