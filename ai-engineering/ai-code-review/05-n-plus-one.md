# 5. N+1 Queries and DB Performance

Invariants: 9 (privacy in mapping), performance checklist.

## Flawed code
```java
@GetMapping("/messages")
public PageResponse<MessageSummary> list(Pageable p) {
    Page<MessageRequest> page = repo.findAll(p);                       // also: no tenant filter
    return PageResponse.of(page.map(m -> new MessageSummary(
        m.getId(), m.getStatus(),
        m.getRecipient().getEmail(),                                    // lazy load per row + unmasked PII
        m.getAttempts().size())));                                      // lazy collection per row
}
```
## Why it is wrong
1 + N (recipient) + N (attempts) queries per page; unfiltered `findAll` (cross-tenant!); email unmasked; sorting by non-indexed property.

## Corrected version
```java
@Query("""
  select new com.example.messaging.web.dto.MessageSummary(m.id, m.status, m.channel, m.attemptCount,
         r.externalRef, m.createdAt)
  from MessageRequest m join m.recipient r
  where m.tenantId = :tenant and (:status is null or m.status = :status)
  order by m.createdAt desc""")
Page<MessageSummary> findSummaries(String tenant, MessageStatus status, Pageable p);
```
Masked address computed by `Redaction.maskEmail` only where needed (detail view); `attemptCount` is a column on the row; the query matches index `(tenant_id, status, created_at DESC)`.

## Rationale
Projection fetches exactly what the screen needs in one statement, uses the index, and removes both the leak and the loop.

## Regression tests
- `MessageQueryIT#listPage_executesAtMostThreeStatements` (Hibernate `Statistics#getPrepareStatementCount`, page size 50, 200 rows seeded).
- `MessageControllerTest#list_neverReturnsFullAddress`, `#list_crossTenantRowsNotVisible`.
- Optional: plan review in `docs/database/query-plans-and-indexes.md`.
