package com.example.messaging.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SecurityIT extends AbstractIntegrationTest {

    private MockHttpServletRequestBuilder devToken(Map<String, Object> body) throws Exception {
        return post("/dev/token").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
    }

    private String signedWith(RSAKey key, String sub, String tenant, List<String> roles, String issuer, long expiresInMs)
            throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(sub).issuer(issuer)
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + expiresInMs))
                .claim("tenant", tenant).claim("roles", roles).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("dev-key-1").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void devTokenEndpointValidatesItsInput() throws Exception {
        mvc.perform(devToken(Map.of("subject", "x", "tenant", "t1", "roles", List.of("GOD_MODE"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field", is("roles")));
        mvc.perform(devToken(Map.of("subject", "x", "tenant", "*", "roles", List.of("SENDER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field", is("tenant")));
        mvc.perform(devToken(Map.of("subject", "x", "tenant", "a b/c", "roles", List.of("SENDER"))))
                .andExpect(status().isBadRequest());
        mvc.perform(devToken(Map.of("subject", "bad subject\n", "tenant", "t1", "roles", List.of("SENDER"))))
                .andExpect(status().isBadRequest());
        mvc.perform(devToken(Map.of("subject", "x", "tenant", "t1", "scopes", List.of("not valid scope!"))))
                .andExpect(status().isBadRequest());
        mvc.perform(devToken(Map.of("tenant", "t1"))).andExpect(status().isBadRequest());
        mvc.perform(devToken(Map.of("subject", "root", "tenant", "*", "roles", List.of("PLATFORM_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType", is("Bearer")));
    }

    @Test
    void tokenLifetimeIsCappedAtEightHours() throws Exception {
        MvcResult r = mvc.perform(devToken(Map.of("subject", "x", "tenant", "t1", "ttlSeconds", 10_000_000)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(r).get("expiresIn").asLong()).isEqualTo(8 * 3600);
    }

    @Test
    void validTokenAuthenticatesAndMeReflectsRolesTenantAndScopes() throws Exception {
        MvcResult t = mvc.perform(devToken(Map.of("subject", "scoped-client", "tenant", RETAIL,
                "roles", List.of("SENDER", "OPERATOR"), "scopes", List.of("messages:bulk", "messages:read"))))
                .andExpect(status().isOk()).andReturn();
        String token = json(t).get("accessToken").asText();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject", is("scoped-client")))
                .andExpect(jsonPath("$.tenant", is(RETAIL)))
                .andExpect(jsonPath("$.roles[0]", is("OPERATOR")))
                .andExpect(jsonPath("$.roles[1]", is("SENDER")))
                .andExpect(jsonPath("$.scopes[0]", is("messages:bulk")))
                .andExpect(jsonPath("$.platformAdmin", is(false)));
    }

    @Test
    void tokensSignedByAnUnknownKeyAreRejected() throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("dev-key-1").generate();
        String forged = signedWith(attacker, "evil", RETAIL, List.of("PLATFORM_ADMIN"), "messaging-dev-issuer", 60_000);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @Test
    void unsignedAndTamperedTokensAreRejected() throws Exception {
        String none = new PlainJWT(new JWTClaimsSet.Builder().subject("evil").claim("tenant", "*")
                .claim("roles", List.of("PLATFORM_ADMIN")).issuer("messaging-dev-issuer")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build()).serialize();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + none)).andExpect(status().isUnauthorized());

        String valid = token("victim", RETAIL, "SENDER");
        String[] parts = valid.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"SENDER\"", "\"PLATFORM_ADMIN\"");
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + valid)).andExpect(status().isOk());
    }

    @Test
    void expiredTokensAreRejected() throws Exception {
        MvcResult t = mvc.perform(devToken(Map.of("subject", "short-lived", "tenant", RETAIL, "ttlSeconds", 1)))
                .andExpect(status().isOk()).andReturn();
        String token = json(t).get("accessToken").asText();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        Thread.sleep(4200); // 1 s lifetime + 2 s tolerated skew
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @Test
    void healthIsPublicButOtherActuatorEndpointsAreRestricted() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("UP")));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans").header("Authorization", admin(RETAIL))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void apiDocsAreServedInTestProfileAndDescribeBearerSecurity() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme", is("bearer")))
                .andExpect(jsonPath("$.info.title", containsString("Messaging")));
    }

    @Test
    void callbackPathsAreNotBlockedBySpringSecurityEvenWithoutAToken() throws Exception {
        // permitAll at the filter chain; the request reaches the callback controller, which authenticates the HMAC
        // signature itself. The answer is therefore INVALID_SIGNATURE from the application, not UNAUTHENTICATED from
        // Spring Security.
        MvcResult r = mvc.perform(post("/api/v1/callbacks/simulated-email").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"INVALID_SIGNATURE\"")
                .doesNotContain("UNAUTHENTICATED");
    }

    @Test
    void responsesCarryHardeningHeaders() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", sender(RETAIL)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("API-Version", "1"));
    }

    @Test
    void sessionsAreNeverCreatedAndCsrfIsNotRequiredForBearerApis() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/me").header("Authorization", sender(RETAIL))).andReturn();
        assertThat(r.getRequest().getSession(false)).isNull();
        assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void corsPreflightIsNotAllowed() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/messages")
                        .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().is4xxClientError());
    }
}
