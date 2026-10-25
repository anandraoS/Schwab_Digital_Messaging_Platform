# Mentoring Examples

Short dialogues between a senior engineer (S) and a developer (D), each with a code snippet. Goal: teach reasoning, not rules.

## 1. SOLID: open/closed with providers
D: I'll add `if (providerId.equals("acme-sms"))` in `DispatchService` for the Acme quirks.
S: What happens when we add the fifth vendor?
D: Another `if`.
S: Then `DispatchService` changes for every vendor and every change risks the retry logic. Where does vendor-specific knowledge belong?
D: In an adapter behind `SmsProvider`.
S: And selection?
D: `ProviderRegistry`, driven by `provider_config`.
```java
public interface SmsProvider { ProviderResult send(SmsRequest r); Optional<ProviderStatus> lookup(String idempotencyRef); String id(); }
@Component class AcmeSmsProvider implements SmsProvider { /* maps Acme errors to ProviderOutcome */ }
```
Takeaway: open for extension via new bean; closed for modification of orchestration (ADR-0002).

## 2. Patterns: why outbox, not "publish then save"
D: Can't I just call `bus.publish()` in the service method after `save()`?
S: What if the publish throws? What if the app dies between the two lines?
D: The client has a 202 but nothing is sent.
S: And publishing inside the transaction?
D: Rolled back DB, but message already on the bus.
S: So we make the DB the single commit point.
```java
@Transactional
public MessageAcceptedResponse submit(...) {
  var msg = repo.save(MessageRequest.validated(...));
  outbox.save(OutboxEvent.dispatch(msg.getId()));   // same transaction, published later by OutboxPublisher
  return MessageAcceptedResponse.of(msg);
}
```
Takeaway: ADR-0009; at-least-once publish + idempotent consumer.

## 3. Testing: proving a race fix
D: I added a unique index; tests pass.
S: Do they exercise concurrency?
D: No, sequential.
S: A sequential test passes even without the index if the code checks first. Make the race real.
```java
var start = new CountDownLatch(1);
var results = IntStream.range(0, 20).mapToObj(i -> pool.submit(() -> { start.await(); return post(key, body); })).toList();
start.countDown();
assertThat(results.stream().map(this::get).filter(r -> r.status()==202).count()).isEqualTo(1);
assertThat(messageRepo.countByTenantIdAndIdempotencyKey(tenant, key)).isEqualTo(1);
```
Takeaway: assert the invariant (one row), not just the status codes.

## 4. Security: tenant from claim, never from body
D: The request has `tenantId`, I'll use it so admins can send for any tenant.
S: What stops a tenant A sender writing `tenant-b`?
D: ...nothing.
S: Where is trusted identity?
D: The JWT claim in `TenantContext`.
```java
String tenant = tenantContext.requireTenant();      // from verified JWT
var recipient = recipientRepo.findByIdAndTenantId(req.recipientId(), tenant)
    .orElseThrow(() -> new NotFoundException("RECIPIENT_NOT_FOUND"));   // 404, not 403
```
Takeaway: ADR-0011; foreign resource looks nonexistent.

## 5. DB performance: the index that makes writes slower
D: The list is slow, I'll add indexes on every filter column.
S: What does each index cost on `message_request`?
D: Extra write per insert and per status change.
S: And status changes about 5 times per message. Which query matters?
D: `(tenant_id, status, created_at)` for the console list.
S: Prove with a plan and a statement count; add one index per named query.
```sql
CREATE INDEX ix_msg_tenant_status_created ON message_request (tenant_id, status, created_at DESC);
```
Takeaway: `docs/database/query-plans-and-indexes.md`; every index has an owner query.

## 6. Production troubleshooting: queue depth rising
D: Queue depth is rising, I'll double the consumers.
S: Before that: are consumers the bottleneck or the provider?
D: How do I know?
S: Compare in-flight with concurrency, look at provider latency and `RATE_LIMITED`. If workers are all waiting on a slow provider, more workers only add load.
```
GET /api/v1/ops/queues   -> inflight=8/8 (saturated)
messaging_provider_latency_seconds p95 = 9s (was 0.4s)  -> provider slow; follow provider-outage runbook
```
Takeaway: Little's law: throughput = concurrency / latency; fix the constraint, not the symptom.

## 7. Honest reporting
D: I'll mark the Helm chart "tested".
S: Did you run `helm lint` or install it?
D: I only read it.
S: Then the doc says "not executed". Trust is the product.
Takeaway: documentation honesty rules (CONTRACT section 12).
