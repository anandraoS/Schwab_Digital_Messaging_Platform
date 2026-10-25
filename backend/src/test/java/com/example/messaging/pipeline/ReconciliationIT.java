package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.provider.simulator.FaultInjector.Mode;
import com.example.messaging.service.ReconciliationService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Reconciliation of DELIVERY_UNKNOWN messages: bounded, spaced, audited and honest about what it cannot know. */
class ReconciliationIT extends AbstractPipelineIT {

    private String unknownMessage() throws Exception {
        faults.set("simulated-email", Mode.AMBIGUOUS, 1.0);
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "DELIVERY_UNKNOWN");
        faults.reset();
        return id;
    }

    @Test
    void checksAreSpacedByReconcileAfterAndBoundedByMaxChecks() throws Exception {
        String id = unknownMessage();
        assertThat(reconciliation.reconcileOne(id)).isEqualTo("ACCEPTED");
        assertThat(reconciliation.reconcileOne(id)).as("checked a moment ago").isEqualTo("SKIPPED");
        assertThat(auditCount(id, "RECONCILIATION_CHECK")).isEqualTo(1);
        // the clock moves by more than reconcile-after (5 min) between checks; the scheduler may help, the bound holds
        String last = null;
        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofMinutes(6));
            last = reconciliation.reconcileOne(id);
            Thread.sleep(40);
        }
        assertThat(last).as("after max-checks (5) the message is left to an operator").isEqualTo("SKIPPED");
        assertThat(auditCount(id, "RECONCILIATION_CHECK")).isEqualTo(5);
        assertThat(statusOf(id)).isEqualTo("DELIVERY_UNKNOWN"); // still visible in /failed-messages
        assertThat(email.callCount()).isEqualTo(1);
    }

    @Test
    void anUnreachableProviderLeavesTheMessageUnknownAndIsAudited() throws Exception {
        String id = unknownMessage();
        jdbc.update("update message_attempt set provider_id = 'ghost-provider' where message_id = ?", id);
        assertThat(reconciliation.reconcileOne(id)).isEqualTo("LOOKUP_UNAVAILABLE");
        assertThat(statusOf(id)).isEqualTo("DELIVERY_UNKNOWN");
        assertThat(jdbc.queryForObject("select detail_json from audit_event where entity_id = ? and action = "
                + "'RECONCILIATION_CHECK'", String.class, id)).contains("LOOKUP_UNAVAILABLE");
    }

    @Test
    void aMessageThatNeverReachedAProviderIsSafelyRequeued() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        // crash between claim and provider call, no attempt row, then the sweeper: nothing can have been sent
        forceStatus(id, "DELIVERY_UNKNOWN");
        jdbc.update("update message_request set locked_until = null where id = ?", id);
        assertThat(reconciliation.reconcileOne(id)).isEqualTo("NOT_RECEIVED");
        assertThat(statusOf(id)).isEqualTo("QUEUED");
        bus.resume();
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(email.callCount()).isEqualTo(1);
        assertThat(attemptList(id).get(0)).containsEntry("attempt_no", 1);
    }

    @Test
    void theScheduledPassOnlyConsidersMessagesOlderThanReconcileAfter() throws Exception {
        String id = unknownMessage();
        ReconciliationService.Summary fresh = reconciliation.reconcileDue();
        assertThat(fresh.checked()).isZero();
        assertThat(auditCount(id, "RECONCILIATION_CHECK")).isZero();
        clock.advance(Duration.ofMinutes(6));
        // scheduler or explicit call: exactly one check happens for this message
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> auditCount(id, "RECONCILIATION_CHECK") >= 1);
        assertThat(auditCount(id, "RECONCILIATION_CHECK")).isEqualTo(1);
    }

    @Test
    void aLateCallbackWinsOverReconciliationAndFirstTerminalStaysFinal() throws Exception {
        String id = unknownMessage();
        assertThat(sendCallback(id, com.example.messaging.domain.DeliveryEventType.DELIVERED, uniqueEventId()).status())
                .isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertThat(reconciliation.reconcileOne(id)).isEqualTo("SKIPPED"); // no longer unknown
        assertThat(email.callCount()).isEqualTo(1);
    }
}
