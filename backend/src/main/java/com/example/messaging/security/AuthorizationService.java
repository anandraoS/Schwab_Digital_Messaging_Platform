package com.example.messaging.security;

import com.example.messaging.common.ApiException;
import org.springframework.stereotype.Service;

/** Resource-level authorization (complements method-level role checks). */
@Service
public class AuthorizationService {

    private final TenantContext tenantContext;

    public AuthorizationService(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    /**
     * Tenant isolation: a resource of another tenant is reported as 404, never 403, so its existence is not leaked.
     */
    public void assertTenantAccess(String ownerTenantId, String what) {
        String scope = tenantContext.scopeTenant();
        if (scope != null && !scope.equals(ownerTenantId)) {
            throw new ApiException.NotFoundException(what);
        }
    }

    public String actor() {
        return tenantContext.principal().map(CurrentPrincipal::subject).orElse("system");
    }

    public String actorRoles() {
        return tenantContext.principal().map(p -> String.join(",", p.roles().stream().sorted().toList()))
                .orElse("SYSTEM");
    }

    public boolean hasRole(String role) {
        return tenantContext.principal().map(p -> p.hasRole(role)).orElse(false);
    }
}
