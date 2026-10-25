package com.example.messaging.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Immutable view of the authenticated JWT principal. Roles are stored without the {@code ROLE_} prefix. */
public record CurrentPrincipal(String subject, String tenant, Set<String> roles, Set<String> scopes) {

    public static final String ANY_TENANT = "*";

    public static CurrentPrincipal from(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).collect(Collectors.toSet());
        Set<String> scopes = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_")).map(a -> a.substring(6)).collect(Collectors.toSet());
        String tenant = null;
        if (auth instanceof TenantAwareAuthentication t) {
            tenant = t.tenant();
        }
        return new CurrentPrincipal(auth.getName(), tenant, roles, scopes);
    }

    public boolean isPlatformAdmin() {
        return roles.contains(Roles.PLATFORM_ADMIN);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
