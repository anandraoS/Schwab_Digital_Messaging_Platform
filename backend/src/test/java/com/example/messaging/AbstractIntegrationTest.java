package com.example.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for integration tests: full application context, profile {@code test} (isolated H2 database per context,
 * seeded synthetic data), MockMvc through the real security filter chain with real RS256 dev tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final String RETAIL = "tenant-retail";
    public static final String WEALTH = "tenant-wealth";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper om;
    @Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Test-only shortcut that bypasses the state machine to put a message into a given lifecycle state. */
    protected void forceStatus(String messageId, String status) {
        jdbc.update("update message_request set status = ?, status_changed_at = current_timestamp where id = ?",
                status, messageId);
    }

    protected String statusOf(String messageId) {
        return jdbc.queryForObject("select status from message_request where id = ?", String.class, messageId);
    }

    /** Mints a dev token through the real {@code /dev/token} endpoint. */
    protected String token(String subject, String tenant, String... roles) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("tenant", tenant);
        body.put("roles", List.of(roles));
        MvcResult r = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andReturn();
        if (r.getResponse().getStatus() != 200) {
            throw new IllegalStateException("token endpoint failed: " + r.getResponse().getContentAsString());
        }
        return om.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    protected String bearer(String subject, String tenant, String... roles) throws Exception {
        return "Bearer " + token(subject, tenant, roles);
    }

    protected String sender(String tenant) throws Exception {
        return bearer("svc-sender-" + tenant, tenant, "SENDER");
    }

    protected String admin(String tenant) throws Exception {
        return bearer("admin-" + tenant, tenant, "TENANT_ADMIN");
    }

    protected String operator(String tenant) throws Exception {
        return bearer("operator-" + tenant, tenant, "OPERATOR");
    }

    protected String platformAdmin() throws Exception {
        return bearer("platform-admin", "*", "PLATFORM_ADMIN");
    }

    protected JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    protected static String newKey() {
        return "it-" + UUID.randomUUID();
    }

    /** A valid EMAIL TRADE_CONFIRMATION submission for a seeded retail recipient. */
    protected Map<String, Object> tradeConfirmation(String recipientId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientReference", "order-" + UUID.randomUUID());
        body.put("recipientId", recipientId);
        body.put("channel", "EMAIL");
        body.put("category", "TRANSACTIONAL");
        body.put("priority", "NORMAL");
        body.put("template", Map.of("code", "TRADE_CONFIRMATION", "locale", "en-US"));
        body.put("variables", Map.of("accountLast4", "1234", "quantity", "10", "symbol", "ACME", "price", "250.00"));
        return body;
    }

    protected MockHttpServletRequestBuilder submit(String auth, String key, Object body) throws Exception {
        return post("/api/v1/messages").header("Authorization", auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
    }

    /** Submits and returns the accepted message id (asserts 202). */
    protected String submitOk(String auth, Map<String, Object> body) throws Exception {
        MvcResult r = mvc.perform(submit(auth, newKey(), body)).andReturn();
        if (r.getResponse().getStatus() != 202) {
            throw new IllegalStateException("submit failed " + r.getResponse().getStatus() + ": "
                    + r.getResponse().getContentAsString());
        }
        return json(r).get("id").asText();
    }
}
