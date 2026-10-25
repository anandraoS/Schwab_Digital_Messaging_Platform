# 2. Missing Idempotency

Invariants: 2 (durable accept), 3 (idempotent consumer/callback).

## Flawed code
```java
@PostMapping("/messages")
public ResponseEntity<MessageAcceptedResponse> submit(@RequestBody SubmitMessageRequest req) {
    MessageRequest m = service.create(req);          // always creates
    bus.publish(DispatchEnvelope.of(m));             // publish immediately, outside the tx
    return ResponseEntity.accepted().body(MessageAcceptedResponse.of(m));
}
```
```java
// consumer
public void onMessage(DispatchEnvelope env) { provider.send(load(env)); }   // no dedupe
```
## Why it is wrong
- Client retry after timeout creates a second message; broker redelivery sends twice.
- Publish outside the transaction: crash after commit loses the message; publish before commit creates ghost messages.
- No `Idempotency-Key` handling; no consumer dedupe.

## Corrected version
```java
@PostMapping("/messages")
public ResponseEntity<?> submit(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SubmitMessageRequest req) {
    SubmitResult r = notificationService.submit(tenantContext.requireTenant(), key, req);
    return r.replay()
        ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(r.body())
        : ResponseEntity.accepted().location(r.location()).body(r.body());
}
// service (one @Transactional): claim key -> persist message(VALIDATED) + outbox + audit -> complete key
// consumer
public void onMessage(DispatchEnvelope env) {
    if (!processed.tryInsert(env.dispatchId())) { ack(); return; }
    if (repo.casStatus(env.messageId(), QUEUED, PROCESSING) == 0) { ack(); return; }
    ...
}
```
## Rationale
Duplicates are normal in distributed systems; every boundary needs its own dedupe (ADR-0004). Outbox removes dual write (ADR-0009).

## Regression tests
- `FailureRecoveryIT#scenario02_duplicateSubmission_sameKey_returnsOriginal_noSecondMessage` (sequential + 20 concurrent).
- `DispatchConsumerTest#redeliveredEnvelope_callsProviderOnce`.
- `NotificationServiceTest#sameKeyDifferentBody_returns422`.
