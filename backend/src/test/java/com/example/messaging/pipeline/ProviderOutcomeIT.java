package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.messaging.provider.ResilientProviderInvoker;
import com.example.messaging.provider.simulator.FaultInjector.Mode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/** How each provider outcome is mapped by the consumer (ADR-0006, ADR-0010) and how the circuit breaker behaves. */
class ProviderOutcomeIT extends AbstractPipelineIT {

    @Test
    void rateLimitedByTheProviderIsRetriedLikeATransientFailureAndConsumesAnAttempt() throws Exception {
        faults.set("simulated-email", Mode.RATE_LIMIT, 1.0);
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "DEAD_LETTERED");
        assertThat(attemptList(id)).hasSize(4).allSatisfy(a -> assertThat(a).containsEntry("outcome", "RATE_LIMITED"));
        assertThat(row(id)).containsEntry("dlq_reason", "RETRIES_EXHAUSTED");
    }

    @Test
    void permanentFailureFailsImmediatelyAndAnOperatorCanRequeueAfterTheCauseIsFixed() throws Exception {
        faults.set("simulated-email", Mode.PERMANENT, 1.0);
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "FAILED");
        assertThat(attemptList(id)).hasSize(1);
        assertThat(attemptList(id).get(0)).containsEntry("outcome", "PERMANENT_FAILURE").containsEntry("error_code",
                "SIM_REJECTED");
        assertThat(String.valueOf(row(id).get("status_reason"))).contains("PERMANENT_FAILURE");
        assertThat(email.callCount()).isEqualTo(1); // never retried automatically

        faults.reset();
        mvc.perform(post("/api/v1/messages/" + id + "/requeue").header("Authorization", operator(RETAIL)))
                .andExpect(MockMvcResultMatchers.status().isOk());
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(attemptRows(id)).isEqualTo(2);
    }

    @Test
    void timeoutOnSmsIsUnknownNotFailedAndNeverResent() throws Exception {
        jdbc.update("update provider_config set timeout_ms = 300 where provider_id = 'simulated-sms'");
        registry.invalidate();
        faults.set("simulated-sms", Mode.TIMEOUT, 1.0);
        String id = submit(newRecipient(RETAIL), "SMS");
        awaitStatus(id, "DELIVERY_UNKNOWN");
        assertThat(attemptList(id).get(0)).containsEntry("outcome", "TIMEOUT");
        Thread.sleep(400);
        assertThat(sms.callCount()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DELIVERY_UNKNOWN");
    }

    @Test
    void alternatingFailuresRecoverOnTheRetry() throws Exception {
        faults.set("simulated-email", Mode.ALTERNATING, 1.0);
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(attemptList(id)).hasSize(2);
        assertThat(attemptList(id).get(0)).containsEntry("outcome", "TRANSIENT_FAILURE");
        assertThat(attemptList(id).get(1)).containsEntry("outcome", "ACCEPTED");
    }

    @Test
    void sustainedOutageOpensTheCircuitWhichStopsCallsWithoutConsumingAttemptsAndRecoversThroughHalfOpen()
            throws Exception {
        faults.set("simulated-email", Mode.TRANSIENT, 1.0);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(submit(newRecipient(RETAIL), "EMAIL"));
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> invoker.state("simulated-email")
                == ResilientProviderInvoker.CircuitState.OPEN);
        assertThat(meters.get("messaging.provider.circuit.state").tag("provider", "simulated-email").gauge().value())
                .isIn(1.0, 2.0);
        // while the circuit is open, deliveries are requeued before any provider call
        long callsWhileOpen = email.callCount();
        Thread.sleep(150);
        assertThat(email.callCount() - callsWhileOpen).isLessThanOrEqualTo(3); // at most half-open probes

        faults.reset(); // provider recovers; the breaker probes in half-open state and closes again
        for (String id : ids) {
            awaitStatus(id, "SENT_TO_PROVIDER");
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> invoker.state("simulated-email")
                == ResilientProviderInvoker.CircuitState.CLOSED);
        // requeues caused by the open circuit never consumed an attempt: nobody got dead-lettered
        for (String id : ids) {
            assertThat(attemptCount(id)).as("attempts of %s", id).isLessThanOrEqualTo(4); // budget is 4
            assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER");
        }
        assertThat(bus.deadLetters()).isEmpty();
    }

    @Test
    void ambiguousOnSmsKeepsTheMessageUnknownUntilReconciledAndNeverDuplicates() throws Exception {
        faults.set("simulated-sms", Mode.AMBIGUOUS, 1.0);
        String id = submit(newRecipient(RETAIL), "SMS");
        awaitStatus(id, "DELIVERY_UNKNOWN");
        Thread.sleep(300);
        assertThat(sms.callCount()).isEqualTo(1);
        assertThat(sms.acceptedCount()).isEqualTo(1);
        assertThat(attemptList(id).get(0)).containsEntry("outcome", "AMBIGUOUS").containsEntry("error_code",
                "SIM_LOST_RESPONSE");
    }

    @Test
    void disablingTheOnlyProviderOfAChannelParksDispatchWithoutConsumingAttempts() throws Exception {
        jdbc.update("update provider_config set enabled = false where provider_id = 'simulated-sms'");
        registry.invalidate();
        String id = submit(newRecipient(RETAIL), "SMS");
        Thread.sleep(800);
        assertThat(statusOf(id)).isEqualTo("QUEUED");
        assertThat(attemptRows(id)).isZero();
        jdbc.update("update provider_config set enabled = true where provider_id = 'simulated-sms'");
        registry.invalidate();
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(attemptRows(id)).isEqualTo(1);
        Map<String, Object> r = row(id);
        assertThat(((Number) r.get("attempt_count")).intValue()).isEqualTo(1);
    }
}
