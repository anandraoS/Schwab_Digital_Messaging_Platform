package com.example.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.example.messaging.domain.DeliveryEvent;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.IllegalStateTransitionException;
import com.example.messaging.domain.MessageAttempt;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.OutboxEvent;
import com.example.messaging.domain.ProcessedMessage;
import com.example.messaging.provider.ProviderOutcome;
import com.example.messaging.repository.DeliveryEventRepository;
import com.example.messaging.repository.MessageAttemptRepository;
import com.example.messaging.repository.OutboxEventRepository;
import com.example.messaging.repository.ProcessedMessageRepository;
import com.example.messaging.domain.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The lifecycle contract that stage 2 (dispatch consumer, callbacks) builds on. */
class MessageLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    NotificationService notifications;
    @Autowired
    MeterRegistry meters;
    @Autowired
    DeliveryEventRepository deliveryEvents;
    @Autowired
    MessageAttemptRepository attempts;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    ProcessedMessageRepository processed;

    private String newMessage() throws Exception {
        return submitOk(sender(RETAIL), tradeConfirmation("rcp-001"));
    }

    @Test
    void transitionIsACompareAndSetThatEnforcesTheStateTable() throws Exception {
        String id = newMessage();
        assertThat(notifications.transition(id, MessageStatus.VALIDATED, MessageStatus.QUEUED, "published", "outbox-publisher"))
                .isTrue();
        assertThat(statusOf(id)).isEqualTo("QUEUED");
        // a stale caller (still believes VALIDATED) loses the race without error
        assertThat(notifications.transition(id, MessageStatus.VALIDATED, MessageStatus.QUEUED, "again", "outbox-publisher"))
                .isFalse();
        // an illegal pair always throws, whatever the current state is
        assertThatThrownBy(() -> notifications.transition(id, MessageStatus.QUEUED, MessageStatus.DELIVERED, "x", "t"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> notifications.transition(id, MessageStatus.VALIDATED, MessageStatus.DELIVERED, "x", "t"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(statusOf(id)).isEqualTo("QUEUED");

        assertThat(notifications.transition(id, MessageStatus.QUEUED, MessageStatus.PROCESSING, "picked up", "consumer-1")).isTrue();
        assertThat(notifications.transition(id, MessageStatus.PROCESSING, MessageStatus.SENT_TO_PROVIDER, "accepted", "consumer-1")).isTrue();
        assertThat(notifications.transition(id, MessageStatus.SENT_TO_PROVIDER, MessageStatus.DELIVERED, "receipt", "callback")).isTrue();
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertThatThrownBy(() -> notifications.transition(id, MessageStatus.DELIVERED, MessageStatus.FAILED, "late", "callback"))
                .isInstanceOf(IllegalStateTransitionException.class);

        // each transition left an audit entry attributed to its actor, visible in the history timeline
        mvcHistory(id).andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.actor", hasItem("consumer-1")))
                .andExpect(jsonPath("$[?(@.type=='MESSAGE_TRANSITION')].detail.to", hasItem("DELIVERED")));
        assertThat(meters.get("messaging.transitions").tag("from", "SENT_TO_PROVIDER").tag("to", "DELIVERED").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    private org.springframework.test.web.servlet.ResultActions mvcHistory(String id) throws Exception {
        return mvc.perform(get("/api/v1/messages/" + id + "/history").header("Authorization", sender(RETAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentConsumersCannotBothWinTheQueuedToProcessingTransition() throws Exception {
        for (int round = 0; round < 3; round++) {
            String id = newMessage();
            notifications.transition(id, MessageStatus.VALIDATED, MessageStatus.QUEUED, "p", "t");
            int n = 6;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> wins = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Boolean> task = () -> {
                    start.await();
                    return notifications.transition(id, MessageStatus.QUEUED, MessageStatus.PROCESSING, "c", "consumer");
                };
                wins.add(pool.submit(task));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> f : wins) {
                if (f.get()) {
                    winners++;
                }
            }
            pool.shutdown();
            assertThat(winners).as("round " + round).isEqualTo(1);
            assertThat(statusOf(id)).isEqualTo("PROCESSING");
        }
    }

    @Test
    void receiptsMayOvertakeTheProviderAcceptedUpdateButOnlyViaTheReceiptPath() throws Exception {
        String id = newMessage();
        notifications.transition(id, MessageStatus.VALIDATED, MessageStatus.QUEUED, "p", "t");
        notifications.transition(id, MessageStatus.QUEUED, MessageStatus.PROCESSING, "c", "t");
        assertThatThrownBy(() -> notifications.transition(id, MessageStatus.PROCESSING, MessageStatus.DELIVERED, "r", "cb"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(notifications.transition(id, MessageStatus.PROCESSING, MessageStatus.DELIVERED, "receipt overtook", "cb", true))
                .isTrue();
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    void deadLetterRecordsTheReasonAndOperatorReplayClearsIt() throws Exception {
        String id = newMessage();
        notifications.transition(id, MessageStatus.VALIDATED, MessageStatus.QUEUED, "p", "t");
        notifications.transition(id, MessageStatus.QUEUED, MessageStatus.PROCESSING, "c", "t");
        notifications.transition(id, MessageStatus.PROCESSING, MessageStatus.RETRY_PENDING, "transient", "t");
        assertThat(notifications.deadLetter(id, MessageStatus.RETRY_PENDING, DlqReason.RETRIES_EXHAUSTED, "5 attempts", "consumer"))
                .isTrue();
        assertThat(statusOf(id)).isEqualTo("DEAD_LETTERED");
        assertThat(jdbc.queryForObject("select dlq_reason from message_request where id = ?", String.class, id))
                .isEqualTo("RETRIES_EXHAUSTED");
        mvc.perform(get("/api/v1/dead-letters").header("Authorization", operator(RETAIL)).param("size", "200"))
                .andExpect(jsonPath("$.content[?(@.messageId=='" + id + "')].reason", hasItem("RETRIES_EXHAUSTED")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select dlq_reason from message_request where id = ?", String.class, id)).isNull();
    }

    @Test
    void attemptsAndDeliveryEventsAppearInDetailAndInTheMergedTimeline() throws Exception {
        String id = newMessage();
        Instant t0 = Instant.now();
        MessageAttempt a = new MessageAttempt();
        a.setId(UUID.randomUUID().toString());
        a.setMessageId(id);
        a.setAttemptNo(1);
        a.setChannel(Channel.EMAIL);
        a.setProviderId("simulated-email");
        a.setState(MessageAttempt.FINISHED);
        a.setOutcome(ProviderOutcome.ACCEPTED);
        a.setProviderMessageId("prov-123");
        a.setLatencyMs(42L);
        a.setStartedAt(t0.plusSeconds(1));
        a.setFinishedAt(t0.plusSeconds(2));
        attempts.save(a);
        DeliveryEvent e = new DeliveryEvent();
        e.setId(UUID.randomUUID().toString());
        e.setMessageId(id);
        e.setProviderId("simulated-email");
        e.setProviderEventId("evt-" + UUID.randomUUID());
        e.setType(DeliveryEventType.DELIVERED);
        e.setOccurredAt(t0.plusSeconds(3));
        e.setReceivedAt(t0.plusSeconds(3));
        e.setApplied(true);
        deliveryEvents.save(e);

        mvc.perform(get("/api/v1/messages/" + id).header("Authorization", sender(RETAIL)))
                .andExpect(jsonPath("$.attempts[0].providerId", is("simulated-email")))
                .andExpect(jsonPath("$.attempts[0].outcome", is("ACCEPTED")))
                .andExpect(jsonPath("$.attempts[0].latencyMs", is(42)))
                .andExpect(jsonPath("$.deliveryEvents[0].type", is("DELIVERED")));
        var result = mvc.perform(get("/api/v1/messages/" + id + "/history").header("Authorization", sender(RETAIL)))
                .andReturn();
        List<String> kinds = new ArrayList<>();
        json(result).forEach(n -> kinds.add(n.get("kind").asText()));
        assertThat(kinds).containsSubsequence("AUDIT", "ATTEMPT", "DELIVERY_EVENT");
    }

    @Test
    void deliveryEventsAreDeduplicatedByProviderAndEventId() throws Exception {
        String eventId = "dup-" + UUID.randomUUID();
        String msg = newMessage();
        DeliveryEvent first = event(msg, eventId);
        deliveryEvents.saveAndFlush(first);
        assertThatThrownBy(() -> deliveryEvents.saveAndFlush(event(msg, eventId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deliveryEvents.existsByProviderIdAndProviderEventId("simulated-email", eventId)).isTrue();
        // the same event id from a different provider is a different event
        DeliveryEvent other = event(msg, eventId);
        other.setProviderId("simulated-sms");
        deliveryEvents.saveAndFlush(other);
    }

    private DeliveryEvent event(String messageId, String eventId) {
        DeliveryEvent e = new DeliveryEvent();
        e.setId(UUID.randomUUID().toString());
        e.setMessageId(messageId);
        e.setProviderId("simulated-email");
        e.setProviderEventId(eventId);
        e.setType(DeliveryEventType.SENT);
        e.setOccurredAt(Instant.now());
        e.setReceivedAt(Instant.now());
        e.setApplied(false);
        return e;
    }

    @Test
    void outboxClaimAndConsumerDedupeAreCompareAndSet() throws Exception {
        newMessage();
        List<OutboxEvent> due = outbox.findByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
                OutboxEvent.NEW, Instant.now(), org.springframework.data.domain.PageRequest.of(0, 5));
        assertThat(due).isNotEmpty().allMatch(o -> o.getAvailableAt().isBefore(Instant.now().plusSeconds(1)));
        OutboxEvent first = due.get(0);
        assertThat(outbox.markSent(first.getId(), Instant.now())).isEqualTo(1);
        assertThat(outbox.markSent(first.getId(), Instant.now())).as("second publisher loses the claim").isZero();

        ProcessedMessage p = new ProcessedMessage();
        p.setId(UUID.randomUUID().toString());
        p.setDispatchId("dispatch-" + UUID.randomUUID());
        p.setMessageId("m-1");
        p.setProcessedAt(Instant.now());
        processed.saveAndFlush(p);
        ProcessedMessage dup = new ProcessedMessage();
        dup.setId(UUID.randomUUID().toString());
        dup.setDispatchId(p.getDispatchId());
        dup.setMessageId("m-1");
        dup.setProcessedAt(Instant.now());
        assertThatThrownBy(() -> processed.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(processed.existsByDispatchId(p.getDispatchId())).isTrue();
    }

    @Test
    void auditEventsAreAppendOnlyAtTheEntityLevel() throws Exception {
        String id = newMessage();
        String auditId = jdbc.queryForObject("select id from audit_event where entity_id = ?", String.class, id);
        assertThat(auditId).isNotBlank();
        // the entity is @Immutable: Hibernate never emits an UPDATE for it
        assertThat(com.example.messaging.domain.AuditEvent.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
    }
}
