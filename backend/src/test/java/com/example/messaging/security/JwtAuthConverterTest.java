package com.example.messaging.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtAuthConverterTest {

    private final JwtAuthConverter converter = new JwtAuthConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    @Test
    void mapsRolesScopesAndTenant() {
        var auth = converter.convert(jwt(Map.of("sub", "alice", "roles", List.of("SENDER", "ROLE_OPERATOR"),
                "tenant", "tenant-retail", "scope", "messages:bulk messages:read")));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_SENDER", "ROLE_OPERATOR", "SCOPE_messages:bulk", "SCOPE_messages:read");
        assertThat(auth.tenant()).isEqualTo("tenant-retail");
        assertThat(auth.getName()).isEqualTo("alice");
        CurrentPrincipal p = CurrentPrincipal.from(auth);
        assertThat(p.roles()).containsExactlyInAnyOrder("SENDER", "OPERATOR");
        assertThat(p.hasScope("messages:bulk")).isTrue();
        assertThat(p.isPlatformAdmin()).isFalse();
    }

    @Test
    void unknownRolesGrantNothing() {
        var auth = converter.convert(jwt(Map.of("sub", "a", "roles", List.of("ROOT", "SUPERUSER", "ROLE_ADMIN"),
                "tenant", "t1")));
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void wildcardTenantIsOnlyValidForPlatformAdmin() {
        assertThat(converter.convert(jwt(Map.of("sub", "root", "roles", List.of("PLATFORM_ADMIN"), "tenant", "*"))).tenant())
                .isEqualTo("*");
        assertThatThrownBy(() -> converter.convert(jwt(Map.of("sub", "x", "roles", List.of("SENDER"), "tenant", "*"))))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void missingOrMalformedTenantOrSubjectIsRejected() {
        assertThatThrownBy(() -> converter.convert(jwt(Map.of("sub", "x", "roles", List.of("SENDER")))))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> converter.convert(jwt(Map.of("sub", "x", "roles", List.of("SENDER"),
                "tenant", "bad tenant/../x")))).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> converter.convert(jwt(Map.of("roles", List.of("SENDER"), "tenant", "t1"))))
                .isInstanceOf(BadCredentialsException.class);
    }
}
