package com.example.messaging.security;

import com.example.messaging.common.ApiException;
import com.example.messaging.repository.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the tenant a request operates on. Non-admins are pinned to their {@code tenant} claim (the
 * {@code X-Tenant-Id} header is ignored for them). PLATFORM_ADMIN may scope with {@code X-Tenant-Id}; without it, reads
 * span all tenants and writes are refused.
 */
@Component
public class TenantContext {

    public static final String HEADER = "X-Tenant-Id";
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final TenantRepository tenants;

    public TenantContext(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public Optional<CurrentPrincipal> principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        return Optional.of(CurrentPrincipal.from(auth));
    }

    public CurrentPrincipal requirePrincipal() {
        return principal().orElseThrow(() -> new IllegalStateException("No authenticated principal"));
    }

    /** Tenant filter for reads; {@code null} means "all tenants" (platform admin without header). */
    public String scopeTenant() {
        CurrentPrincipal p = requirePrincipal();
        if (!p.isPlatformAdmin()) {
            return p.tenant();
        }
        String header = headerTenant();
        if (header != null) {
            return header;
        }
        return CurrentPrincipal.ANY_TENANT.equals(p.tenant()) ? null : p.tenant();
    }

    /** Tenant for writes: must resolve to one concrete, existing tenant. */
    public String requireTenant() {
        String t = scopeTenant();
        if (t == null) {
            throw new ApiException.ValidationException(HEADER, "Platform administrators must supply the X-Tenant-Id header for this operation");
        }
        CurrentPrincipal p = requirePrincipal();
        if (p.isPlatformAdmin() && !tenants.existsById(t)) {
            throw new ApiException.ValidationException(HEADER, "Unknown tenant");
        }
        return t;
    }

    private String headerTenant() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        String h = req.getHeader(HEADER);
        if (h == null || h.isBlank()) {
            return null;
        }
        if (!TENANT.matcher(h).matches()) {
            throw new ApiException.ValidationException(HEADER, "Invalid tenant id");
        }
        return h;
    }
}
