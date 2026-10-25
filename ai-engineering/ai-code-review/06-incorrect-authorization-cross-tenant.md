# 6. Incorrect Authorization (cross-tenant)

Invariants: 8 (tenant from JWT, scoped access, 404).

## Flawed code
```java
@PostMapping("/messages/{id}/cancel")
@PreAuthorize("hasAnyRole('SENDER','TENANT_ADMIN','OPERATOR','PLATFORM_ADMIN')")
public MessageStatusResponse cancel(@PathVariable String id, @RequestParam(required = false) String tenantId) {
    MessageRequest m = repo.findById(id).orElseThrow(() -> new NotFoundException("NOT_FOUND"));
    String tenant = tenantId != null ? tenantId : m.getTenantId();    // tenant taken from the request
    return service.cancel(tenant, m);
}
```
## Why it is wrong
- Role check only; no ownership check: tenant B can cancel tenant A's message by id (BOLA).
- `tenantId` request parameter is attacker-controlled.
- If the message belongs to another tenant, a 403 would leak existence; here `findById` has no tenant guard.

## Corrected version
```java
@PostMapping("/messages/{id}/cancel")
@PreAuthorize("hasAnyRole('SENDER','TENANT_ADMIN','OPERATOR','PLATFORM_ADMIN')")
public MessageStatusResponse cancel(@PathVariable String id) {
    String tenant = tenantContext.requireTenantOrScoped();            // JWT claim; admin may scope via X-Tenant-Id only if tenant=*
    return service.cancel(tenant, id);                                // repo.findByIdAndTenantId -> NotFoundException (404) if foreign
}
```
## Rationale
Authorization is role AND resource ownership; foreign resources are indistinguishable from missing ones (ADR-0011).

## Regression tests
- `tests/security`: tenant B token on tenant A message id for GET, status, history, cancel, requeue, diagnostics: all 404.
- `SecurityMatrixTest#everyIdEndpoint_foreignTenant_returns404` (parameterized over the controller mappings so new endpoints cannot be forgotten).
- `TenantContextTest#adminScopingRequiresWildcardTenant`.
