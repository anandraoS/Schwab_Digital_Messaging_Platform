package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.provider.simulator.FaultInjector.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Provider health, configuration, fault injection, ops summary and the dev callback emitter over HTTP. */
class ProviderApiIT extends AbstractPipelineIT {

    private JsonNode provider(JsonNode list, String id) {
        for (JsonNode n : list) {
            if (id.equals(n.get("providerId").asText())) {
                return n;
            }
        }
        throw new AssertionError("provider " + id + " not listed");
    }

    private JsonNode providers() throws Exception {
        return json(mvc.perform(get("/api/v1/providers").header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void providerHealthShowsConfigurationCircuitMetricsAndTheLastError() throws Exception {
        JsonNode before = providers();
        assertThat(before).hasSize(4);
        JsonNode emailHealth = provider(before, "simulated-email");
        assertThat(emailHealth.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(emailHealth.get("enabled").asBoolean()).isTrue();
        assertThat(emailHealth.get("circuitState").asText()).isEqualTo("CLOSED");
        assertThat(emailHealth.get("simulated").asBoolean()).isTrue();
        assertThat(emailHealth.get("faultMode").asText()).isEqualTo("NONE");
        assertThat(emailHealth.get("ratePerSec").asInt()).isEqualTo(100);
        assertThat(emailHealth.get("rateLimitPerSec").asInt()).isEqualTo(100);
        assertThat(emailHealth.get("timeoutMs").asInt()).isEqualTo(2000);
        assertThat(provider(before, "acme-sms").get("enabled").asBoolean()).isFalse();

        // traffic changes success rate, latency percentile and last error
        faults.set("simulated-email", Mode.TRANSIENT, 1.0);
        String failing = submit(newRecipient(RETAIL), "EMAIL");
        awaitAttempts(failing, 2);
        faults.reset();
        awaitStatus(failing, "SENT_TO_PROVIDER");
        JsonNode after = provider(providers(), "simulated-email");
        assertThat(after.get("successRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(after.get("lastError").asText()).isEqualTo("TRANSIENT_FAILURE:SIM_UNAVAILABLE");
        assertThat(after.get("p95LatencyMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(after.get("faultMode").asText()).isEqualTo("NONE");
    }

    @Test
    void platformAdminChangesConfigurationWhichTakesEffectImmediatelyAndIsAudited() throws Exception {
        String admin = platformAdmin();
        // alias used by the Angular console is accepted
        mvc.perform(put("/api/v1/providers/simulated-sms/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"rateLimitPerSec\":7,\"timeoutMs\":1500}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ratePerSec").value(7))
                .andExpect(jsonPath("$.rateLimitPerSec").value(7)).andExpect(jsonPath("$.timeoutMs").value(1500));
        assertThat(jdbc.queryForObject("select rate_limit_per_sec from provider_config where provider_id = "
                + "'simulated-sms'", Integer.class)).isEqualTo(7);

        mvc.perform(put("/api/v1/providers/simulated-sms/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.ratePerSec").value(7)); // omitted fields keep their value
        String parked = submit(newRecipient(RETAIL), "SMS");
        Thread.sleep(600);
        assertThat(statusOf(parked)).isEqualTo("QUEUED"); // no enabled SMS provider: parked, no attempt consumed
        mvc.perform(put("/api/v1/providers/simulated-sms/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
        awaitStatus(parked, "SENT_TO_PROVIDER");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_id = 'simulated-sms' and "
                + "action = 'PROVIDER_CONFIG_UPDATED'", Integer.class)).isEqualTo(3);

        // validation and unknown ids
        for (String bad : List.of("{\"ratePerSec\":0}", "{\"timeoutMs\":50}", "{\"ratePerSec\":\"fast\"}")) {
            mvc.perform(put("/api/v1/providers/simulated-sms/config").header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/providers/nope/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isNotFound());
    }

    @Test
    void faultModeEndpointDrivesTheSimulatorAndShowsUpInHealth() throws Exception {
        String admin = platformAdmin();
        mvc.perform(put("/api/v1/providers/simulated-email/fault-mode").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"PERMANENT\",\"probability\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("PERMANENT"));
        assertThat(faults.get("simulated-email").mode()).isEqualTo(Mode.PERMANENT);
        JsonNode h = provider(providers(), "simulated-email");
        assertThat(h.get("faultMode").asText()).isEqualTo("PERMANENT");
        assertThat(h.get("faultProbability").asDouble()).isEqualTo(1.0);
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "FAILED");
        mvc.perform(put("/api/v1/providers/simulated-email/fault-mode").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NONE\"}")).andExpect(status().isOk());
        assertThat(faults.get("simulated-email").mode()).isEqualTo(Mode.NONE);

        for (String bad : List.of("{\"mode\":\"EXPLODE\"}", "{}", "{\"mode\":\"TRANSIENT\",\"probability\":2}")) {
            mvc.perform(put("/api/v1/providers/simulated-email/fault-mode").header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/providers/nope/fault-mode").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NONE\"}")).andExpect(status().isNotFound());
    }

    @Test
    void opsSummaryIsTenantScopedAndCountsTheLast24Hours() throws Exception {
        String retail = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(retail, "SENT_TO_PROVIDER");
        String wealth = submitOk(sender(WEALTH), tradeConfirmation("rcp-041"));
        awaitStatus(wealth, "SENT_TO_PROVIDER");

        long retailTotal = jdbc.queryForObject("select count(*) from message_request where tenant_id = ? and created_at "
                + ">= dateadd('HOUR', -24, current_timestamp)", Long.class, RETAIL);
        long wealthTotal = jdbc.queryForObject("select count(*) from message_request where tenant_id = ? and created_at "
                + ">= dateadd('HOUR', -24, current_timestamp)", Long.class, WEALTH);
        JsonNode r = json(mvc.perform(get("/api/v1/ops/summary").header("Authorization", sender(RETAIL)))
                .andExpect(status().isOk()).andReturn());
        JsonNode w = json(mvc.perform(get("/api/v1/ops/summary").header("Authorization", admin(WEALTH)))
                .andExpect(status().isOk()).andReturn());
        assertThat(r.get("windowHours").asInt()).isEqualTo(24);
        assertThat(r.get("total").asLong()).isEqualTo(retailTotal);
        assertThat(w.get("total").asLong()).isEqualTo(wealthTotal);
        assertThat(w.get("byStatus").get("SENT_TO_PROVIDER").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(w.get("byChannel").get("EMAIL").asLong()).isEqualTo(wealthTotal);
        assertThat(r.get("byStatus").size()).isEqualTo(12);
        assertThat(r.get("successRate").asDouble()).isBetween(0.0, 1.0);
        assertThat(r.get("p95DispatchDelayMs").asLong()).isGreaterThanOrEqualTo(0);
        // a platform admin without a tenant header sees everything
        JsonNode all = json(mvc.perform(get("/api/v1/ops/summary").header("Authorization", platformAdmin()))
                .andExpect(status().isOk()).andReturn());
        assertThat(all.get("total").asLong()).isGreaterThanOrEqualTo(retailTotal + wealthTotal);
    }

    @Test
    void devEmitterPostsSignedCallbacksWithDelayDuplicateAndOutOfOrderOptions() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        mvc.perform(post("/dev/simulator/callbacks/emit").header("Authorization", platformAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("messageId", id, "type",
                        DeliveryEventType.DELIVERED.name(), "delayMs", 100, "duplicate", true, "outOfOrder", true))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.scheduled", hasSize(3)));
        awaitStatus(id, "DELIVERED");
        // the DELIVERED applies, the duplicate (same X-Event-Id) is a replay, the older SENT arrives out of order
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> eventList(id).size() == 2);
        assertThat(eventList(id)).anyMatch(e -> "DELIVERED".equals(e.get("type")) && Boolean.TRUE.equals(e.get("applied")))
                .anyMatch(e -> "SENT".equals(e.get("type")) && Boolean.FALSE.equals(e.get("applied"))
                        && "OUT_OF_ORDER".equals(e.get("ignored_reason")));
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        // not for messages that never reached a provider, and not for unknown ones
        mvc.perform(post("/dev/simulator/callbacks/emit").header("Authorization", platformAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("messageId", "nope",
                        "type", "DELIVERED")))).andExpect(status().isNotFound());
    }

    @Test
    void failedMessagesAndDeadLettersListsUseTheDocumentedShapes() throws Exception {
        faults.set("simulated-email", Mode.TRANSIENT, 1.0);
        String dead = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(dead, "DEAD_LETTERED");
        mvc.perform(get("/api/v1/dead-letters").param("size", "200").header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.messageId=='" + dead + "')].reason", hasItem("RETRIES_EXHAUSTED")))
                .andExpect(jsonPath("$.content[?(@.messageId=='" + dead + "')].deadLetteredAt").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.messageId=='" + dead + "')].attemptCount", hasItem(4)));
        faults.reset();
        faults.set("simulated-email", Mode.AMBIGUOUS, 1.0);
        String unknown = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(unknown, "DELIVERY_UNKNOWN");
        mvc.perform(get("/api/v1/failed-messages").param("size", "200").header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + unknown + "')].status", hasItem("DELIVERY_UNKNOWN")))
                .andExpect(jsonPath("$.content[?(@.id=='" + unknown + "')].channelUsed", hasItem("EMAIL")))
                .andExpect(jsonPath("$.content[?(@.id=='" + unknown + "')].templateCode", hasItem("TRADE_CONFIRMATION")));
    }
}
