package com.example.messaging.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Simulates the production wiring: dev tokens disabled, tokens validated against an external JWKS (unreachable
 * here, so no token can ever be valid). Proves the dev issuer is really absent and cannot be re-enabled by accident.
 */
@TestPropertySource(properties = {
        "messaging.security.dev-token-enabled=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/unreachable-jwks"})
class DevTokenDisabledIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theDevIssuerBeansDoNotExist() {
        assertThat(context.getBeanNamesForType(DevTokenController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JwtEncoder.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JwtDecoder.class)).hasSize(1);
    }

    @Test
    void devTokenEndpointIsNotServed() throws Exception {
        MvcResult r = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"x\",\"tenant\":\"t\",\"roles\":[\"PLATFORM_ADMIN\"]}")).andReturn();
        assertThat(r.getResponse().getStatus()).isIn(404, 405);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("accessToken");
    }

    @Test
    void noTokenIsAcceptedAndTheApiStaysClosed() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        // a well-formed token cannot be verified because the JWKS endpoint is unreachable: denied (fail closed),
        // reported as 503 rather than leaking a stack trace or authenticating anyone
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.e30.c2ln"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code", is("SERVICE_UNAVAILABLE")))
                .andExpect(header().string("Retry-After", "5"));
        mvc.perform(get("/api/v1/messages").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
    }
}
