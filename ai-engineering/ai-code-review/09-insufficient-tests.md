# 9. Insufficient (or Tautological) Tests

Invariants: all (tests are how they are enforced).

## Flawed tests
```java
@Test void retryWorks() {
    when(provider.send(any())).thenReturn(ProviderResult.transientFailure());
    dispatchService.process(envelope);
    verify(provider).send(any());                       // asserts only that a call happened
}

@Test void idempotent() {
    var r1 = service.submit(tenant, "k", req);
    var r2 = service.submit(tenant, "k", req);
    assertNotNull(r2);                                  // would pass even if two messages were created
}
```
## Why it is wrong
- Mocks the unit's own collaborators so the behavior (state, attempts, delay bounds, audit) is never asserted.
- `assertNotNull` proves nothing; no concurrency; no negative cases; no failure path; wall-clock dependent in places.
- A mutation (remove the unique check, change backoff to constant) would not fail these tests.

## Corrected tests
```java
@Test void transientFailure_schedulesBackoffWithinJitterBounds() {
    clock.set(T0); random.seed(42);
    given(faults.mode(TRANSIENT));
    var id = submitAndDispatch();
    var m = repo.findById(id).orElseThrow();
    assertThat(m.getStatus()).isEqualTo(RETRY_PENDING);
    long delay = Duration.between(T0, m.getNextAttemptAt()).toMillis();
    assertThat(delay).isBetween(base / 2, base);                       // attempt 1: [0.5, 1.0] * base
    assertThat(attempts.findByMessageId(id)).extracting(Attempt::outcome).containsExactly(TRANSIENT_FAILURE);
    assertThat(metrics.counter("messaging.retry.scheduled", "channel", "SMS")).isEqualTo(1);
}

@Test void sameKey_twentyConcurrentRequests_createOneMessage() throws Exception {
    var results = runConcurrently(20, () -> api.post(token, "k-1", body));
    assertThat(results).filteredOn(r -> r.status() == 202).hasSize(1);
    assertThat(messages.countByTenantIdAndIdempotencyKey(TENANT, "k-1")).isEqualTo(1);
}
```
## Rationale
Tests must fail when the invariant breaks. Technique: temporarily mutate the production code (remove the guard) and confirm the test fails ("test the test").

## Regression guardrails
- Checklist `ai-engineering/checklists/test-generation.md` mandatory for generated tests.
- Review rule: reject tests with only `verify(...)`/`assertNotNull`/`isNotNull` on the behavior under test.
- Optional PIT mutation run on `domain` and `RetryPolicy` (not executed here).
