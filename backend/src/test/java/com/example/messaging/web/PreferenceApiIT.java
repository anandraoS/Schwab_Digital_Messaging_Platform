package com.example.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PreferenceApiIT extends AbstractIntegrationTest {

    private MockHttpServletRequestBuilder putPrefs(String auth, String recipientId, Map<String, Object> body) throws Exception {
        return put("/api/v1/recipients/" + recipientId + "/preferences").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
    }

    private Map<String, Object> prefsBody(boolean marketing, Long version) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("marketingOptIn", marketing);
        b.put("emailEnabled", true);
        b.put("smsEnabled", true);
        b.put("pushEnabled", true);
        b.put("quietStart", "22:00");
        b.put("quietEnd", "06:30");
        b.put("fallbackOrder", List.of("SMS", "PUSH"));
        b.put("version", version);
        return b;
    }

    private JsonNode getPrefs(String auth, String id) throws Exception {
        return json(mvc.perform(get("/api/v1/recipients/" + id + "/preferences").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void readReturnsSeededPreferencesWithAVersionToken() throws Exception {
        // rcp-020: even (no marketing opt-in), push disabled (20 % 5 == 0), fallback SMS,EMAIL (20 % 4 == 0)
        JsonNode p = getPrefs(operator(RETAIL), "rcp-020");
        assertThat(p.get("marketingOptIn").asBoolean()).isFalse();
        assertThat(p.get("pushEnabled").asBoolean()).isFalse();
        assertThat(p.get("emailEnabled").asBoolean()).isTrue();
        assertThat(p.get("quietStart").asText()).isEqualTo("21:00");
        assertThat(p.get("quietEnd").asText()).isEqualTo("08:00");
        assertThat(p.get("fallbackOrder")).hasSize(2);
        assertThat(p.get("timeZone").asText()).isEqualTo("America/Chicago");
        assertThat(p.get("version").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void updateIncrementsTheVersionAndStaleWritesGet409() throws Exception {
        String admin = admin(RETAIL);
        JsonNode before = getPrefs(admin, "rcp-021");
        long v = before.get("version").asLong();
        mvc.perform(putPrefs(admin, "rcp-021", prefsBody(true, v))).andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingOptIn", is(true)))
                .andExpect(jsonPath("$.quietStart", is("22:00")))
                .andExpect(jsonPath("$.quietEnd", is("06:30")))
                .andExpect(jsonPath("$.fallbackOrder", contains("SMS", "PUSH")))
                .andExpect(jsonPath("$.version", is((int) v + 1)));
        // stale token
        mvc.perform(putPrefs(admin, "rcp-021", prefsBody(false, v))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONFLICT_OPTIMISTIC_LOCK")));
        // missing token on an existing row is also a conflict: the client must read first
        mvc.perform(putPrefs(admin, "rcp-021", prefsBody(false, null))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONFLICT_OPTIMISTIC_LOCK")));
        // the stale write changed nothing
        assertThat(getPrefs(admin, "rcp-021").get("marketingOptIn").asBoolean()).isTrue();
        // audit trail
        mvc.perform(get("/api/v1/audit-events").header("Authorization", admin).param("entityType", "RECIPIENT")
                        .param("entityId", "rcp-021").param("action", "PREFERENCE_UPDATED"))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void twoConcurrentWritersWithTheSameVersionExactlyOneWins() throws Exception {
        String admin = admin(RETAIL);
        for (String id : List.of("rcp-022", "rcp-023", "rcp-024", "rcp-025", "rcp-026")) {
            long v = getPrefs(admin, id).get("version").asLong();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Integer>> results = new ArrayList<>();
            for (boolean marketing : new boolean[] {true, false}) {
                Callable<Integer> task = () -> {
                    start.await();
                    return mvc.perform(putPrefs(admin, id, prefsBody(marketing, v))).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            pool.shutdown();
            assertThat(statuses).as("recipient " + id).containsExactlyInAnyOrder(200, 409);
            assertThat(getPrefs(admin, id).get("version").asLong()).isEqualTo(v + 1);
        }
    }

    @Test
    void invalidPreferenceValuesAreRejected() throws Exception {
        String admin = admin(RETAIL);
        long v = getPrefs(admin, "rcp-027").get("version").asLong();
        Map<String, Object> badTime = prefsBody(true, v);
        badTime.put("quietStart", "25:00");
        mvc.perform(putPrefs(admin, "rcp-027", badTime)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field", is("quietStart")));
        Map<String, Object> half = prefsBody(true, v);
        half.remove("quietEnd");
        half.put("quietEnd", null);
        mvc.perform(putPrefs(admin, "rcp-027", half)).andExpect(status().isBadRequest());
        Map<String, Object> dup = prefsBody(true, v);
        dup.put("fallbackOrder", List.of("SMS", "SMS"));
        mvc.perform(putPrefs(admin, "rcp-027", dup)).andExpect(status().isBadRequest());
        Map<String, Object> zone = prefsBody(true, v);
        zone.put("timeZone", "Mars/Olympus");
        mvc.perform(putPrefs(admin, "rcp-027", zone)).andExpect(status().isBadRequest());
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("version", v);
        mvc.perform(putPrefs(admin, "rcp-027", missing)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        Map<String, Object> channel = prefsBody(true, v);
        channel.put("fallbackOrder", List.of("PIGEON"));
        mvc.perform(putPrefs(admin, "rcp-027", channel)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_CHANNEL")));
        // and nothing was written
        assertThat(getPrefs(admin, "rcp-027").get("version").asLong()).isEqualTo(v);
    }

    @Test
    void timeZoneCanBeChangedAlongWithPreferences() throws Exception {
        String admin = admin(RETAIL);
        long v = getPrefs(admin, "rcp-028").get("version").asLong();
        Map<String, Object> body = prefsBody(true, v);
        body.put("timeZone", "Pacific/Auckland");
        mvc.perform(putPrefs(admin, "rcp-028", body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone", is("Pacific/Auckland")));
        assertThat(getPrefs(admin, "rcp-028").get("timeZone").asText()).isEqualTo("Pacific/Auckland");
    }

    @Test
    void rolesControlReadAndWriteAccess() throws Exception {
        long v = getPrefs(sender(RETAIL), "rcp-029").get("version").asLong(); // SENDER may read
        mvc.perform(putPrefs(sender(RETAIL), "rcp-029", prefsBody(true, v))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recipients/rcp-029/preferences").header("Authorization", bearer("aud", RETAIL, "AUDITOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recipients/rcp-029/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(putPrefs(operator(RETAIL), "rcp-029", prefsBody(true, v))).andExpect(status().isOk());
    }

    @Test
    void anotherTenantsRecipientIsNotFound() throws Exception {
        String wealth = bearer("w-admin", WEALTH, "TENANT_ADMIN", "OPERATOR", "SENDER");
        mvc.perform(get("/api/v1/recipients/rcp-001/preferences").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(putPrefs(wealth, "rcp-001", prefsBody(true, 0L))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/recipients/rcp-001/suppressions").header("Authorization", wealth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"EMAIL\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/recipients/rcp-001/suppressions/EMAIL").header("Authorization", wealth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recipients").header("Authorization", wealth).param("q", "Recipient 001"))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    void suppressionLifecycleBlocksDeliveryOnThatChannelOnly() throws Exception {
        String admin = admin(RETAIL);
        String sender = sender(RETAIL);
        Map<String, Object> sms = tradeConfirmation("rcp-030");
        sms.put("channel", "SMS");
        mvc.perform(submit(sender, newKey(), sms)).andExpect(jsonPath("$.status", is("VALIDATED")));

        mvc.perform(post("/api/v1/recipients/rcp-030/suppressions").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"SMS\",\"reason\":\"COMPLAINT\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.channel", is("SMS")))
                .andExpect(jsonPath("$.reason", is("COMPLAINT")));
        // idempotent: adding again returns the existing entry
        mvc.perform(post("/api/v1/recipients/rcp-030/suppressions").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"SMS\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reason", is("COMPLAINT")));
        mvc.perform(get("/api/v1/recipients/rcp-030/preferences").header("Authorization", admin))
                .andExpect(jsonPath("$.suppressedChannels", contains("SMS")));

        mvc.perform(submit(sender, newKey(), sms)).andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.statusReason", is("SUPPRESSED")));
        // email is unaffected
        mvc.perform(submit(sender, newKey(), tradeConfirmation("rcp-030"))).andExpect(jsonPath("$.status", is("VALIDATED")));

        mvc.perform(delete("/api/v1/recipients/rcp-030/suppressions/SMS").header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/recipients/rcp-030/suppressions/SMS").header("Authorization", admin))
                .andExpect(status().isNotFound());
        mvc.perform(submit(sender, newKey(), sms)).andExpect(jsonPath("$.status", is("VALIDATED")));
        mvc.perform(get("/api/v1/audit-events").header("Authorization", admin).param("entityId", "rcp-030"))
                .andExpect(jsonPath("$.content[*].action", hasItem("SUPPRESSION_ADDED")))
                .andExpect(jsonPath("$.content[*].action", hasItem("SUPPRESSION_REMOVED")));
    }

    @Test
    void suppressionNeedsAnAddressAndTheRightRole() throws Exception {
        // rcp-009 has no push token (9 % 9 == 0)
        mvc.perform(post("/api/v1/recipients/rcp-009/suppressions").header("Authorization", admin(RETAIL))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"PUSH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/recipients/rcp-009/suppressions").header("Authorization", sender(RETAIL))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"EMAIL\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/recipients/rcp-009/suppressions").header("Authorization", admin(RETAIL))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"FAX\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("UNSUPPORTED_CHANNEL")));
    }

    @Test
    void recipientSearchMasksAddressesAndPaginates() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/recipients").header("Authorization", operator(RETAIL))
                        .param("q", "recipient 00").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()", is(5)))
                .andExpect(jsonPath("$.totalElements", is(9)))
                .andExpect(jsonPath("$.content[0].maskedEmail", containsString("***@example.test")))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("recipient001@example.test")
                .doesNotContain("+15550100001").doesNotContain("push-token");
        mvc.perform(get("/api/v1/recipients").header("Authorization", operator(RETAIL)).param("q", "CUST-040"))
                .andExpect(jsonPath("$.content[0].deleted", is(true)));
        mvc.perform(get("/api/v1/recipients").header("Authorization", operator(RETAIL)).param("q", "%"))
                .andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/recipients").header("Authorization", bearer("aud", RETAIL, "AUDITOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recipients").header("Authorization", operator(RETAIL)).param("q", "Recipient"))
                .andExpect(jsonPath("$.totalElements", is(40)));
        mvc.perform(get("/api/v1/recipients").header("Authorization", platformAdmin()).param("q", "Recipient"))
                .andExpect(jsonPath("$.totalElements", is(60)));
        mvc.perform(get("/api/v1/recipients").header("Authorization", operator(RETAIL)).param("q", "Recipient")
                        .param("size", "10").param("page", "3"))
                .andExpect(jsonPath("$.content.length()", is(10))).andExpect(jsonPath("$.content[*].id", not(hasItem("rcp-001"))));
    }
}
