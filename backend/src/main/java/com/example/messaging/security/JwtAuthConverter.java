package com.example.messaging.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * Maps JWT claims to authorities: {@code roles} (array) to {@code ROLE_*}, {@code scope}/{@code scp} to
 * {@code SCOPE_*}, and exposes the {@code tenant} claim. Tokens without a valid tenant claim are rejected (401);
 * only PLATFORM_ADMIN may carry the wildcard tenant {@code *}.
 */
public class JwtAuthConverter implements Converter<Jwt, JwtAuthConverter.TenantJwtAuthenticationToken> {

    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public TenantJwtAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object roles = jwt.getClaims().get("roles");
        boolean platformAdmin = false;
        if (roles instanceof Collection<?> c) {
            for (Object r : c) {
                String role = String.valueOf(r).trim();
                if (role.isEmpty() || !Roles.ALL.contains(role.startsWith("ROLE_") ? role.substring(5) : role)) {
                    continue; // unknown roles grant nothing
                }
                String bare = role.startsWith("ROLE_") ? role.substring(5) : role;
                platformAdmin |= Roles.PLATFORM_ADMIN.equals(bare);
                authorities.add(new SimpleGrantedAuthority("ROLE_" + bare));
            }
        }
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String s) {
            for (String one : s.split("\\s+")) {
                if (!one.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + one));
                }
            }
        } else if (scope instanceof Collection<?> c) {
            c.forEach(one -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + one)));
        }
        String tenant = jwt.getClaimAsString("tenant");
        boolean valid = tenant != null
                && ((CurrentPrincipal.ANY_TENANT.equals(tenant) && platformAdmin) || TENANT.matcher(tenant).matches());
        if (!valid) {
            throw new BadCredentialsException("Token has no valid tenant claim");
        }
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new BadCredentialsException("Token has no subject");
        }
        return new TenantJwtAuthenticationToken(jwt, authorities, tenant);
    }

    public static class TenantJwtAuthenticationToken extends AbstractOAuth2TokenAuthenticationToken<Jwt>
            implements TenantAwareAuthentication {

        private static final long serialVersionUID = 1L;
        private final String tenant;

        TenantJwtAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities, String tenant) {
            super(jwt, jwt.getSubject(), jwt, authorities);
            this.tenant = tenant;
            setAuthenticated(true);
        }

        @Override
        public java.util.Map<String, Object> getTokenAttributes() {
            return getToken().getClaims();
        }

        @Override
        public String tenant() {
            return tenant;
        }

        @Override
        public String getName() {
            return getToken().getSubject();
        }
    }
}
