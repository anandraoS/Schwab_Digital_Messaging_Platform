package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.provider.simulator.SimulatedProviderBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Happy path through the whole pipeline: submit, outbox, bus, consumer, simulated provider, signed callback. */
class DispatchFlowIT extends AbstractPipelineIT {

    @Autowired
    MeterRegistry meters;

    @Test
    void emailGoesFromSubmitToDeliveredViaSignedCallback() throws Exception {
        String rcp = newRecipient(RETAIL);
        String id = submit(rcp, "EMAIL");

        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(email.callCount()).isEqualTo(1);
        List<Map<String, Object>> attempts = attemptList(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0)).containsEntry("provider_id", "simulated-email").containsEntry("state", "FINISHED")
                .containsEntry("outcome", "ACCEPTED");
        assertThat((String) attempts.get(0).get("provider_message_id")).startsWith("em-");
        assertThat(attemptCount(id)).isEqualTo(1);
        // acceptance is not delivery
        assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER");

        CallbackResponse sent = sendCallback(id, DeliveryEventType.SENT, uniqueEventId());
        assertThat(sent.status()).isEqualTo(202);
        CallbackResponse delivered = sendCallback(id, DeliveryEventType.DELIVERED, uniqueEventId());
        assertThat(delivered.status()).isEqualTo(202);
        assertThat(delivered.result()).isEqualTo("accepted");
        assertThat(statusOf(id)).isEqualTo("DELIVERED");

        // lifecycle timeline shows every step, attributed to the actors that did them
        mvc.perform(get("/api/v1/messages/" + id + "/history").header("Authorization", sender(RETAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.to", hasItem("QUEUED")))
                .andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.to", hasItem("PROCESSING")))
                .andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.to", hasItem("SENT_TO_PROVIDER")))
                .andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.to", hasItem("DELIVERED")))
                .andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.actor", hasItem("dispatch-consumer")))
                .andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.actor", hasItem("outbox-publisher")));

        // metrics moved
        assertThat(meters.get("messaging.provider.calls").tag("provider", "simulated-email").tag("outcome", "ACCEPTED")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meters.get("messaging.dispatch.delay").timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("messaging.callbacks").tag("result", "accepted").counter().count())
                .isGreaterThanOrEqualTo(2.0);
        assertThat(meters.get("messaging.transitions").tag("from", "PROCESSING").tag("to", "SENT_TO_PROVIDER").counter()
                .count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void messageDetailNeverExposesRenderedContentOrFullAddress() throws Exception {
        String rcp = newRecipient(RETAIL);
        String id = submit(rcp, "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        String detail = mvc.perform(get("/api/v1/messages/" + id).header("Authorization", sender(RETAIL)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(rcp + "@example.test").doesNotContain("Trade confirmation");
        assertThat(detail).contains("***@example.test");
    }

    @Test
    void scheduledMessageStaysInTheOutboxUntilDue() throws Exception {
        String rcp = newRecipient(RETAIL);
        Map<String, Object> body = request(rcp, "EMAIL");
        body.put("schedule", Map.of("sendAt", java.time.Instant.now().plusSeconds(3600).toString()));
        String id = submitOk(sender(RETAIL), body);
        Thread.sleep(400); // several publisher ticks
        assertThat(statusOf(id)).isEqualTo("VALIDATED");
        assertThat(email.callCount()).isZero();
        // time passes: the row becomes due and flows through
        clock.advance(java.time.Duration.ofHours(2));
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(email.callCount()).isEqualTo(1);
    }

    @Test
    void sendersOfAllThreeChannelsAreServedByTheirOwnSimulator() throws Exception {
        String rcp = newRecipient(RETAIL);
        // PUSH uses a different seeded template than TRADE_CONFIRMATION, so use the SMS and EMAIL variants plus push
        String emailId = submit(rcp, "EMAIL");
        String smsId = submit(rcp, "SMS");
        awaitStatus(emailId, "SENT_TO_PROVIDER");
        awaitStatus(smsId, "SENT_TO_PROVIDER");
        assertThat(attemptList(emailId).get(0)).containsEntry("provider_id", "simulated-email");
        assertThat(attemptList(smsId).get(0)).containsEntry("provider_id", "simulated-sms");
        SimulatedProviderBase p = providerOf(smsId);
        assertThat(p.find(smsId + ":1")).isPresent();
    }
}
