package com.example.messaging.security;

import com.example.messaging.common.ApiException;
import com.example.messaging.config.JwtConfig;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.common.Ids;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV ONLY: mints RS256 JWTs for the demo personas. The bean does not exist unless
 * {@code messaging.security.dev-token-enabled=true}, which only the local and test profiles set.
 */
@RestController
@Hidden
@ConditionalOnProperty(name = "messaging.security.dev-token-enabled", havingValue = "true")
public class DevTokenController {

    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_.@-]{1,100}");
    private static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9:_.-]{1,64}");

    public record DevTokenRequest(
            @NotBlank @Size(max = 100) String subject,
            @Size(max = 10) List<String> roles,
            @NotBlank @Size(max = 64) String tenant,
            @Size(max = 20) List<String> scopes,
            Integer ttlSeconds) {}

    public record DevTokenResponse(String accessToken, String tokenType, long expiresIn, Map<String, Object> claims) {}

    private final JwtEncoder encoder;
    private final MessagingProperties props;
    private final Clock clock;

    public DevTokenController(JwtEncoder devJwtEncoder, MessagingProperties props, Clock clock) {
        this.encoder = devJwtEncoder;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping(value = "/dev/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DevTokenResponse token(@Valid @RequestBody DevTokenRequest req) {
        List<String> roles = req.roles() == null ? List.of() : req.roles();
        for (String r : roles) {
            if (!Roles.ALL.contains(r)) {
                throw new ApiException.ValidationException("roles", "Unknown role: " + r);
            }
        }
        if (!SUBJECT.matcher(req.subject()).matches()) {
            throw new ApiException.ValidationException("subject", "Subject contains unsupported characters");
        }
        boolean admin = roles.contains(Roles.PLATFORM_ADMIN);
        if (CurrentPrincipal.ANY_TENANT.equals(req.tenant())) {
            if (!admin) {
                throw new ApiException.ValidationException("tenant", "Wildcard tenant is only valid for PLATFORM_ADMIN");
            }
        } else if (!TENANT.matcher(req.tenant()).matches()) {
            throw new ApiException.ValidationException("tenant", "Invalid tenant id");
        }
        List<String> scopes = req.scopes() == null ? List.of() : req.scopes();
        for (String s : scopes) {
            if (!SCOPE.matcher(s).matches()) {
                throw new ApiException.ValidationException("scopes", "Invalid scope: " + s);
            }
        }
        Duration max = props.security().devTokenTtl();
        Duration ttl = req.ttlSeconds() == null ? max : Duration.ofSeconds(Math.max(1, req.ttlSeconds()));
        if (ttl.compareTo(Duration.ofHours(8)) > 0) {
            ttl = Duration.ofHours(8);
        }
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.security().devIssuer())
                .subject(req.subject())
                .id(Ids.newId())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("roles", roles)
                .claim("tenant", req.tenant())
                .claim("scope", String.join(" ", scopes))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(JwtConfig.DEV_KEY_ID).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new DevTokenResponse(token, "Bearer", ttl.toSeconds(), Map.of(
                "sub", req.subject(), "roles", roles, "tenant", req.tenant(), "scope", String.join(" ", scopes)));
    }
}
