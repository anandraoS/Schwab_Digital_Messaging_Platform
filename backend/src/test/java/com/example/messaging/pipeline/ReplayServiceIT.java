package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.common.Ids;
import com.example.messaging.provider.simulator.FaultInjector.Mode;
import com.example.messaging.service.DeadLetterOperations;
import com.example.messaging.service.ReplayService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Operator replay: policy and provider are consulted first, everything is audited, tenants are isolated. */
class ReplayServiceIT extends AbstractPipelineIT {

    @Autowired
    DeadLetterOperations operations;

    private String deadLetter(String rcp) throws Exception {
        faults.set("simulated-email", Mode.TRANSIENT, 1.0);
        String id = submit(rcp, "EMAIL");
        awaitStatus(id, "DEAD_LETTERED");
        faults.reset();
        return id;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder replay(String auth, String id) {
        return post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", auth);
    }

    @Test
    void theReplayServiceIsTheActiveDeadLetterImplementation() {
        assertThat(operations).isInstanceOf(ReplayService.class);
    }

    @Test
    void replayRefusesWhenPolicyNowForbidsTheSendAndLeavesTheMessageDeadLettered() throws Exception {
        String rcp = newRecipient(RETAIL);
        String id = deadLetter(rcp);
        String address = jdbc.queryForObject("select email from recipient where id = ?", String.class, rcp);
        jdbc.update("insert into suppression (id, tenant_id, channel, address_hash, reason, created_at) values "
                + "(?,?,?,?,?,?)", Ids.newId(), RETAIL, "EMAIL", Ids.addressHash(address), "COMPLAINT",
                utc(Instant.now()));
        mvc.perform(replay(operator(RETAIL), id)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POLICY_SUPPRESSED"));
        assertThat(statusOf(id)).isEqualTo("DEAD_LETTERED");
        assertThat(jdbc.queryForObject("select detail_json from audit_event where entity_id = ? and action = "
                + "'REPLAY_PRECHECK'", String.class, id)).contains("POLICY_REJECTED").doesNotContain("example.test");
        assertThat(auditCount(id, "MESSAGE_REPLAYED")).isZero();
    }

    @Test
    void replayRefusesWhenTheProviderAlreadyHoldsAnAmbiguousMessageButProceedsWhenItNeverSawIt() throws Exception {
        // the last attempt was ambiguous and the provider did accept it
        faults.set("simulated-email", Mode.AMBIGUOUS, 1.0);
        String held = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(held, "DELIVERY_UNKNOWN");
        faults.reset();
        jdbc.update("update message_request set status = 'DEAD_LETTERED', dlq_reason = 'OPERATOR' where id = ?", held);
        mvc.perform(replay(operator(RETAIL), held)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        assertThat(statusOf(held)).isEqualTo("DEAD_LETTERED");
        assertThat(email.callCount()).isEqualTo(1); // never resent

        // the last attempt timed out and the provider says it never received the message: replay may proceed
        jdbc.update("update provider_config set timeout_ms = 300 where provider_id = 'simulated-email'");
        registry.invalidate();
        faults.set("simulated-email", Mode.TIMEOUT, 1.0);
        String lost = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(lost, "DELIVERY_UNKNOWN");
        faults.reset();
        jdbc.update("update message_request set status = 'DEAD_LETTERED', dlq_reason = 'OPERATOR' where id = ?", lost);
        mvc.perform(replay(operator(RETAIL), lost)).andExpect(status().isOk());
        awaitStatus(lost, "SENT_TO_PROVIDER");
        assertThat(auditCount(lost, "MESSAGE_REPLAYED")).isEqualTo(1);
    }

    @Test
    void replayIsRestrictedByRoleStateAndTenant() throws Exception {
        String id = deadLetter(newRecipient(RETAIL));
        mvc.perform(replay(sender(RETAIL), id)).andExpect(status().isForbidden());
        mvc.perform(replay(admin(RETAIL), id)).andExpect(status().isForbidden());
        mvc.perform(replay(operator(WEALTH), id)).andExpect(status().isNotFound()); // other tenant: no existence leak
        mvc.perform(replay(operator(RETAIL), "no-such-message")).andExpect(status().isNotFound());
        // only DEAD_LETTERED messages can be replayed
        String sent = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(sent, "SENT_TO_PROVIDER");
        mvc.perform(replay(operator(RETAIL), sent)).andExpect(status().isConflict());
        // and the legitimate replay works, exactly once
        mvc.perform(replay(operator(RETAIL), id)).andExpect(status().isOk());
        awaitStatus(id, "SENT_TO_PROVIDER");
        mvc.perform(replay(operator(RETAIL), id)).andExpect(status().isConflict());
    }

    @Test
    void discardKeepsTheDeadLetterButFlagsItAndIsAudited() throws Exception {
        String id = deadLetter(newRecipient(RETAIL));
        mvc.perform(post("/api/v1/dead-letters/" + id + "/discard").header("Authorization", operator(RETAIL))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"customer called\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DEAD_LETTERED"));
        assertThat(jdbc.queryForObject("select discarded from message_request where id = ?", Boolean.class, id)).isTrue();
        assertThat(auditCount(id, "MESSAGE_DISCARDED")).isEqualTo(1);
    }
}
