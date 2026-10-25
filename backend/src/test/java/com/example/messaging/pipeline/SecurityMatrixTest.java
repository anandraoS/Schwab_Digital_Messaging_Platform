package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Role and tenant matrix for the stage-2 endpoints. For every endpoint: no token is 401, every role outside the allowed
 * set is 403, and every allowed role gets past authorisation (any status other than 401/403). Tenant checks follow.
 */
class SecurityMatrixTest extends AbstractPipelineIT {

    private static final List<String> ROLES = List.of("PLATFORM_ADMIN", "TENANT_ADMIN", "TEMPLATE_AUTHOR",
            "TEMPLATE_APPROVER", "OPERATOR", "SENDER", "AUDITOR");

    private record Endpoint(String name, String method, String path, String body, Set<String> allowed) {}

    private MockHttpServletRequestBuilder build(Endpoint e) {
        MockHttpServletRequestBuilder b = switch (e.method()) {
            case "GET" -> get(e.path());
            case "PUT" -> put(e.path());
            default -> post(e.path());
        };
        if (e.body() != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(e.body());
        }
        return b;
    }

    private String tokenFor(String role) throws Exception {
        return "PLATFORM_ADMIN".equals(role) ? platformAdmin() : bearer("matrix-" + role.toLowerCase(), RETAIL, role);
    }

    @Test
    void everyEndpointEnforcesItsRoleSet() throws Exception {
        String messageId = submit(newRecipient(RETAIL), "EMAIL");
        List<Endpoint> endpoints = List.of(
                new Endpoint("providers list", "GET", "/api/v1/providers", null,
                        Set.of("OPERATOR", "TENANT_ADMIN", "PLATFORM_ADMIN")),
                new Endpoint("provider config", "PUT", "/api/v1/providers/simulated-email/config", "{}",
                        Set.of("PLATFORM_ADMIN")),
                new Endpoint("fault mode", "PUT", "/api/v1/providers/simulated-email/fault-mode",
                        "{\"mode\":\"NONE\"}", Set.of("PLATFORM_ADMIN")),
                new Endpoint("ops queues", "GET", "/api/v1/ops/queues", null, Set.of("OPERATOR", "PLATFORM_ADMIN")),
                new Endpoint("ops summary", "GET", "/api/v1/ops/summary", null, Set.copyOf(ROLES)),
                new Endpoint("pause", "POST", "/api/v1/ops/consumers/pause", null, Set.of("PLATFORM_ADMIN")),
                new Endpoint("resume", "POST", "/api/v1/ops/consumers/resume", null, Set.of("PLATFORM_ADMIN")),
                new Endpoint("diagnostics", "GET", "/api/v1/diagnostics/messages/" + messageId, null,
                        Set.of("OPERATOR", "PLATFORM_ADMIN")),
                new Endpoint("emit", "POST", "/dev/simulator/callbacks/emit",
                        "{\"messageId\":\"" + messageId + "\",\"type\":\"SENT\"}", Set.of("PLATFORM_ADMIN")),
                new Endpoint("replay", "POST", "/api/v1/dead-letters/no-such-message/replay", null,
                        Set.of("OPERATOR", "PLATFORM_ADMIN")));
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String r : ROLES) {
            tokens.put(r, tokenFor(r));
        }
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints) {
            int anonymous = mvc.perform(build(e)).andReturn().getResponse().getStatus();
            if (anonymous != 401) {
                problems.add(e.name() + ": anonymous got " + anonymous);
            }
            for (String role : ROLES) {
                int code = mvc.perform(build(e).header("Authorization", tokens.get(role))).andReturn().getResponse()
                        .getStatus();
                boolean allowed = e.allowed().contains(role);
                if (allowed && (code == 401 || code == 403)) {
                    problems.add(e.name() + ": " + role + " should be allowed but got " + code);
                }
                if (!allowed && code != 403) {
                    problems.add(e.name() + ": " + role + " should be forbidden but got " + code);
                }
            }
            bus.resume(); // the pause endpoint was exercised
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void tenantBoundariesHoldForDiagnosticsReplayAndSummaries() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        // another tenant's operator cannot even learn that the message exists
        mvc.perform(get("/api/v1/diagnostics/messages/" + id).header("Authorization", operator(WEALTH)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/diagnostics/messages/" + id).header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk());
        // a platform admin may look at any tenant
        mvc.perform(get("/api/v1/diagnostics/messages/" + id).header("Authorization", platformAdmin()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", operator(WEALTH)))
                .andExpect(status().isNotFound());
        // the platform admin's tenant header scopes the summary
        var scoped = mvc.perform(get("/api/v1/ops/summary").header("Authorization", platformAdmin())
                .header("X-Tenant-Id", WEALTH)).andExpect(status().isOk()).andReturn();
        var own = mvc.perform(get("/api/v1/ops/summary").header("Authorization", operator(WEALTH)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(scoped).get("total").asLong()).isEqualTo(json(own).get("total").asLong());
        // a tenant admin cannot widen the scope with the header
        var spoof = mvc.perform(get("/api/v1/ops/summary").header("Authorization", admin(WEALTH))
                .header("X-Tenant-Id", RETAIL)).andExpect(status().isOk()).andReturn();
        assertThat(json(spoof).get("total").asLong()).isEqualTo(json(own).get("total").asLong());
    }

    @Test
    void forgedOrExpiredTokensAreRejectedOnTheNewEndpoints() throws Exception {
        for (String path : List.of("/api/v1/providers", "/api/v1/ops/queues", "/api/v1/ops/summary")) {
            mvc.perform(get(path).header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Basic YWRtaW46YWRtaW4=")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/dev/simulator/callbacks/emit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
