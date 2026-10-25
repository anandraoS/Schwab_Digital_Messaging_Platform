package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.BusDecision;
import com.example.messaging.messaging.DispatchEnvelope;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** At-least-once transport with an idempotent consumer: duplicates never cause a second provider call (ADR-0004). */
class IdempotentConsumerIT extends AbstractPipelineIT {

    private DispatchEnvelope envelopeFor(String messageId, int attemptNo) {
        return DispatchEnvelope.of(messageId, RETAIL, Channel.EMAIL, Priority.NORMAL, attemptNo, "corr-" + messageId,
                Instant.now());
    }

    private int processedRows(String dispatchId) {
        return jdbc.queryForObject("select count(*) from processed_message where dispatch_id = ?", Integer.class,
                dispatchId);
    }

    @Test
    void theSameEnvelopeDeliveredSeveralTimesProducesExactlyOneProviderCall() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        DispatchEnvelope env = envelopeFor(id, 1);
        bus.publish(env); // two more copies next to the one the outbox published
        bus.publish(env);
        assertThat(bus.stats().totalDepth()).isGreaterThanOrEqualTo(3);
        bus.resume();
        awaitStatus(id, "SENT_TO_PROVIDER");
        awaitIdle();
        assertThat(email.callCount()).isEqualTo(1);
        assertThat(attemptRows(id)).isEqualTo(1);
        assertThat(processedRows(id + ":1")).isEqualTo(1);
        assertThat(hook.deliveries.get(id).get()).isGreaterThanOrEqualTo(3); // all copies were really delivered
    }

    @Test
    void redeliveryAfterTheOutcomeWasPersistedIsAcknowledgedAndSkipped() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        DispatchEnvelope env = envelopeFor(id, 1);
        for (int i = 0; i < 3; i++) {
            assertThat(consumer.handle(env, 2 + i).type()).isEqualTo(BusDecision.Type.ACK);
        }
        bus.publish(env);
        awaitIdle();
        assertThat(email.callCount()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER");
        assertThat(attemptRows(id)).isEqualTo(1);
    }

    @Test
    void anEnvelopeOfAnEarlierAttemptIsStaleAndIgnored() throws Exception {
        faults.set("simulated-email", com.example.messaging.provider.simulator.FaultInjector.Mode.TRANSIENT, 1.0);
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "DEAD_LETTERED");
        assertThat(attemptCount(id)).isEqualTo(4);
        faults.reset();
        long calls = email.callCount();
        // a straggler envelope of attempt 1 shows up long after attempt 4: nothing may happen
        assertThat(consumer.handle(envelopeFor(id, 1), 1).type()).isEqualTo(BusDecision.Type.ACK);
        assertThat(consumer.handle(envelopeFor(id, 2), 1).type()).isEqualTo(BusDecision.Type.ACK);
        assertThat(email.callCount()).isEqualTo(calls);
        assertThat(statusOf(id)).isEqualTo("DEAD_LETTERED");
    }

    @Test
    void anOutboxRowPublishedTwiceAfterACrashBeforeMarkingItSentStillSendsOnce() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        // crash window: envelope published, outbox row not marked SENT: the row is picked up again
        jdbc.update("update outbox_event set status = 'NEW', sent_at = null where aggregate_id = ?", id);
        assertThat(outboxPublisher.publishBatch()).isGreaterThanOrEqualTo(1);
        assertThat(bus.stats().totalDepth()).isGreaterThanOrEqualTo(2);
        bus.resume();
        awaitStatus(id, "SENT_TO_PROVIDER");
        awaitIdle();
        assertThat(email.callCount()).isEqualTo(1);
        assertThat(attemptRows(id)).isEqualTo(1);
    }

    @Test
    void replayedOrDuplicatedEnvelopesForUnrelatedStatesAreSkipped() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        sendCallback(id, com.example.messaging.domain.DeliveryEventType.DELIVERED, uniqueEventId());
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        long calls = email.callCount();
        assertThat(consumer.handle(envelopeFor(id, 2), 1).type()).isEqualTo(BusDecision.Type.ACK);
        assertThat(email.callCount()).isEqualTo(calls);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }
}
