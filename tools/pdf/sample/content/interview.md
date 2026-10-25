## Messaging Reliability Questions

### Q01. How do you guarantee a notification is not sent twice when the client retries a submission?
**Tags:** Idempotency, Medium, Design
**Analogy:** Think of a restaurant order slip with a number on it. If the waiter hands in the same numbered slip twice, the kitchen recognises the number and cooks only one meal.
**Model answer:**
The client sends an `Idempotency-Key` header. The service stores the key together with a hash of the request body inside the same transaction that creates the message.

1. Fast path: look the key up in Redis (`SET key value NX EX 86400`).
2. Authoritative path: a unique constraint on `(tenant_id, idempotency_key)` in PostgreSQL.
3. If the key exists and the body hash matches, return the original response (HTTP 202 with the same `messageId`).
4. If the key exists but the body differs, return HTTP 409 – a *key reuse* error.

```java
@Transactional
public SubmissionResult submit(SubmitRequest req, String key) {
    var existing = idempotency.find(req.tenant(), key);
    if (existing.isPresent()) {
        return existing.get().matches(req) ? existing.get().result() : throw new KeyReuseException(key);
    }
    var msg = messages.save(Message.accepted(req));
    idempotency.record(req.tenant(), key, req.hash(), msg.id());
    return SubmissionResult.accepted(msg.id());
}
```

| Layer | Purpose | Failure mode |
|---|---|---|
| Redis | Fast duplicate check | Falls back to the database |
| Unique constraint | Authoritative guard | Race loser gets a constraint violation and re-reads |

**Follow-ups:**
- What happens if Redis is down?
- How long should keys be retained, and why?
- How do you make the consumer side idempotent too?
**Pitfalls:** Checking the key *outside* the transaction creates a race window; two concurrent requests can both pass the check. Always rely on the unique constraint for correctness.

### Q02. Explain at-least-once delivery and how consumers cope with duplicates.
**Tags:** Messaging, Hard, Concept
**Analogy:** A courier who is paid per signature will knock again if no one answers, so you might receive two parcels of the same item – you keep a checklist of tracking numbers already received.
**Model answer:**
With manual acknowledgements a message is only removed after the consumer confirms. If the consumer crashes after sending but before acking, the broker redelivers.

- Make the handler **idempotent**: record the `messageId` and processing outcome atomically.
- Treat status changes as *monotonic*: a lower-rank status never overwrites a higher one.
- Use a dead-letter queue after the retry budget is exhausted.

**Follow-ups:** Compare with exactly-once semantics in Kafka transactions.
**Pitfalls:** Acking before the side effect is committed turns at-least-once into at-most-once and silently loses messages.

### Q03. When would you choose Kafka over RabbitMQ for a notification platform?
**Tags:** Architecture, Easy, Trade-off
**Analogy:** RabbitMQ is a post office that delivers letters and throws away the envelope; Kafka is a library where every book stays on the shelf so anyone can re-read it later.
**Model answer:**
Choose Kafka when you need replay, stream analytics or very large fan-out. Choose RabbitMQ when you need per-message priority, TTL and dead-lettering with simple operations.

**Follow-ups:**
- How would partition keys affect ordering per recipient?
- What changes in the `MessageBus` port?
**Pitfalls:** Picking a technology for hype rather than for the access pattern.
