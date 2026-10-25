package com.example.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.example.messaging.domain.DeliveryEvent;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.repository.DeliveryEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Cross-cutting tenant isolation: other tenants' resources are 404 (never 403) and never appear in lists. */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired
    DeliveryEventRepository events;

    private String wealthAll() throws Exception {
        return bearer("wealth-all", WEALTH, "TENANT_ADMIN", "OPERATOR", "SENDER", "AUDITOR", "TEMPLATE_AUTHOR",
                "TEMPLATE_APPROVER");
    }

    private String retailTemplateId() throws Exception {
        return json(mvc.perform(get("/api/v1/templates").header("Authorization", admin(RETAIL))
                .param("code", "LOGIN_ALERT")).andReturn()).get("content").get(0).get("id").asText();
    }

    @Test
    void templatesOfAnotherTenantAreInvisibleAndImmutable() throws Exception {
        String id = retailTemplateId();
        String wealth = wealthAll();
        mvc.perform(get("/api/v1/templates/" + id).header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/templates/" + id + "/history").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/templates/" + id + "/versions").header("Authorization", wealth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("locale", "en", "body", "x", "variables", List.of()))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/retire").header("Authorization", wealth))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/approve").header("Authorization", wealth))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/templates/" + id + "/versions/1").header("Authorization", wealth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("body", "x", "variables", List.of(), "version", 0))))
                .andExpect(status().isNotFound());
        // LOGIN_ALERT exists only for retail
        mvc.perform(get("/api/v1/templates").header("Authorization", wealth).param("code", "LOGIN_ALERT"))
                .andExpect(jsonPath("$.totalElements", is(0)));
        // and retail's version is still PUBLISHED
        mvc.perform(get("/api/v1/templates/" + id).header("Authorization", admin(RETAIL)))
                .andExpect(jsonPath("$.versions[0].status", is("PUBLISHED")));
        // a wealth sender cannot use a retail-only template either
        Map<String, Object> body = Map.of("recipientId", "rcp-041", "channel", "SMS", "category", "SECURITY_ALERT",
                "template", Map.of("code", "LOGIN_ALERT"), "variables", Map.of("time", "10:00"));
        mvc.perform(submit(wealth, newKey(), body)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sameTemplateCodeInTwoTenantsResolvesPerTenant() throws Exception {
        // both tenants have TRADE_CONFIRMATION (email) with different bodies; each only ever sees its own
        String retail = json(mvc.perform(get("/api/v1/templates").header("Authorization", admin(RETAIL))
                .param("code", "TRADE_CONFIRMATION").param("channel", "EMAIL")).andReturn()).get("content").get(0).get("id").asText();
        String wealth = json(mvc.perform(get("/api/v1/templates").header("Authorization", wealthAll())
                .param("code", "TRADE_CONFIRMATION").param("channel", "EMAIL")).andReturn()).get("content").get(0).get("id").asText();
        assertThat(retail).isNotEqualTo(wealth);
        mvc.perform(get("/api/v1/templates/" + retail).header("Authorization", wealthAll())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/templates/" + wealth).header("Authorization", admin(RETAIL))).andExpect(status().isNotFound());
    }

    @Test
    void auditEventsAreScopedToTheCallersTenant() throws Exception {
        String retailMsg = submitOk(sender(RETAIL), tradeConfirmation("rcp-001"));
        mvc.perform(get("/api/v1/audit-events").header("Authorization", admin(RETAIL)).param("entityId", retailMsg))
                .andExpect(jsonPath("$.totalElements", is(1)));
        mvc.perform(get("/api/v1/audit-events").header("Authorization", wealthAll()).param("entityId", retailMsg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/audit-events").header("Authorization", platformAdmin()).param("entityId", retailMsg))
                .andExpect(jsonPath("$.totalElements", is(1)));
        mvc.perform(get("/api/v1/audit-events").header("Authorization", platformAdmin()).header("X-Tenant-Id", WEALTH)
                        .param("entityId", retailMsg))
                .andExpect(jsonPath("$.totalElements", is(0)));
        // roles: a sender or operator cannot read the audit trail at all
        mvc.perform(get("/api/v1/audit-events").header("Authorization", sender(RETAIL))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events").header("Authorization", operator(RETAIL))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events").header("Authorization", bearer("aud", RETAIL, "AUDITOR")))
                .andExpect(status().isOk());
    }

    @Test
    void deliveryEventsAreScopedThroughTheirMessage() throws Exception {
        String id = submitOk(sender(RETAIL), tradeConfirmation("rcp-001"));
        DeliveryEvent e = new DeliveryEvent();
        e.setId(UUID.randomUUID().toString());
        e.setMessageId(id);
        e.setProviderId("simulated-email");
        e.setProviderEventId("evt-" + UUID.randomUUID());
        e.setType(DeliveryEventType.DELIVERED);
        e.setOccurredAt(Instant.now());
        e.setReceivedAt(Instant.now());
        e.setApplied(true);
        events.save(e);
        mvc.perform(get("/api/v1/delivery-events").header("Authorization", operator(RETAIL)).param("messageId", id))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].type", is("DELIVERED")));
        mvc.perform(get("/api/v1/delivery-events").header("Authorization", wealthAll()).param("messageId", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/delivery-events").header("Authorization", sender(RETAIL))).andExpect(status().isForbidden());
        // the message detail of the other tenant is a 404 even though events exist
        mvc.perform(get("/api/v1/messages/" + id).header("Authorization", wealthAll())).andExpect(status().isNotFound());
    }

    @Test
    void deadLetterAndFailedListsAreScoped() throws Exception {
        String id = submitOk(sender(RETAIL), tradeConfirmation("rcp-001"));
        forceStatus(id, "DEAD_LETTERED");
        String failed = submitOk(sender(RETAIL), tradeConfirmation("rcp-001"));
        forceStatus(failed, "FAILED");
        mvc.perform(get("/api/v1/dead-letters").header("Authorization", operator(RETAIL)).param("size", "200"))
                .andExpect(jsonPath("$.content[*].messageId", hasItem(id)));
        mvc.perform(get("/api/v1/dead-letters").header("Authorization", wealthAll()).param("size", "200"))
                .andExpect(jsonPath("$.content[*].messageId", not(hasItem(id))));
        mvc.perform(get("/api/v1/failed-messages").header("Authorization", wealthAll()).param("size", "200"))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(failed))));
        mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", wealthAll()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/dead-letters/" + id + "/discard").header("Authorization", wealthAll()))
                .andExpect(status().isNotFound());
        assertThat(statusOf(id)).isEqualTo("DEAD_LETTERED");
    }

    @Test
    void platformAdminSeesEverythingAndCanScopeWithTheHeader() throws Exception {
        MvcResult all = mvc.perform(get("/api/v1/recipients").header("Authorization", platformAdmin()).param("size", "200"))
                .andExpect(jsonPath("$.totalElements", is(60))).andReturn();
        assertThat(json(all).get("content").size()).isEqualTo(60);
        mvc.perform(get("/api/v1/recipients").header("Authorization", platformAdmin()).header("X-Tenant-Id", WEALTH))
                .andExpect(jsonPath("$.totalElements", is(20)));
        mvc.perform(get("/api/v1/me").header("Authorization", platformAdmin()))
                .andExpect(jsonPath("$.platformAdmin", is(true))).andExpect(jsonPath("$.tenant", is("*")))
                .andExpect(jsonPath("$.effectiveTenant").doesNotExist());
        mvc.perform(get("/api/v1/me").header("Authorization", platformAdmin()).header("X-Tenant-Id", RETAIL))
                .andExpect(jsonPath("$.effectiveTenant", is(RETAIL)));
        // a non-admin's header is ignored
        mvc.perform(get("/api/v1/me").header("Authorization", admin(RETAIL)).header("X-Tenant-Id", WEALTH))
                .andExpect(jsonPath("$.effectiveTenant", is(RETAIL)));
    }
}
