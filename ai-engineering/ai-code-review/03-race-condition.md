# 3. Race Condition (read-modify-write on status)

Invariants: 1, 3.

## Flawed code
```java
// operator cancel API
MessageRequest m = repo.findByIdAndTenantId(id, tenant).orElseThrow(NotFoundException::new);
if (m.getStatus() == MessageStatus.QUEUED) {
    m.setStatus(MessageStatus.CANCELLED);
    repo.save(m);                       // meanwhile the consumer moved it to PROCESSING and called the provider
}
```
## Why it is wrong
Check-then-act: between the read and the save the consumer can win; the message is both sent and marked CANCELLED. Even with `@Version`, a swallowed `ObjectOptimisticLockingFailureException` or a `merge` of a stale detached entity can overwrite.

## Corrected version
```java
@Transactional
public MessageStatus cancel(String tenant, String id) {
    MessageRequest m = repo.findByIdAndTenantId(id, tenant).orElseThrow(NotFoundException::new);
    MessageStatus from = m.getStatus();
    stateMachine.require(from, MessageStatus.CANCELLED);          // 409 ILLEGAL_STATE_TRANSITION if not allowed
    if (repo.casStatus(id, from, MessageStatus.CANCELLED, clock.instant()) == 0) {
        throw new IllegalStateTransitionException(from, MessageStatus.CANCELLED); // lost race: 409, client re-reads
    }
    audit.transition(...); return MessageStatus.CANCELLED;
}
```
Consumer does `casStatus(QUEUED, PROCESSING)`: exactly one of cancel/consumer wins.

## Rationale
The DB serializes the two `UPDATE ... WHERE status=:from`; the loser sees 0 rows and takes the safe path (consumer acks and skips; API returns 409).

## Regression tests
- `CancelRaceIT#cancelVsConsume_exactlyOneWins_providerCalledAtMostOnceOrCancelled` (latch gate, 50 iterations): assert final status is CANCELLED with zero provider calls, or SENT_TO_PROVIDER with API 409, never both.
- `MessageStateMachineTest#cancelOnlyFromValidatedQueuedRetryPending`.
