package com.example.messaging.scheduler;

import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.OutboxEvent;
import com.example.messaging.messaging.BusRejectedException;
import com.example.messaging.messaging.DispatchEnvelope;
import com.example.messaging.messaging.MessageBus;
import com.example.messaging.messaging.OutboxWriter;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.MessageStatusView;
import com.example.messaging.repository.OutboxEventRepository;
import com.example.messaging.service.NotificationService;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox relay (ADR-0009). Every 200 ms it takes due {@code NEW} rows (rows of scheduled or deferred
 * messages stay untouched until their {@code available_at}) and, per row and inside one transaction: claims it with
 * the compare-and-set {@code NEW -> SENT}, moves the message {@code VALIDATED -> QUEUED} (a message that is already
 * QUEUED, e.g. replay or fallback, is simply published; cancelled messages are skipped) and publishes the envelope.
 * If the publish fails (backpressure, broker down) the whole transaction rolls back and the row is retried on the
 * next tick, so delivery is at-least-once; consumers de-duplicate on {@code dispatchId}. Because the claim is a row
 * lock held until commit, several instances can run this safely.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String ACTOR = "outbox-publisher";

    private final OutboxEventRepository outbox;
    private final MessageRequestRepository messages;
    private final NotificationService notifications;
    private final MessageBus bus;
    private final OutboxWriter writer;
    private final PipelineProperties pipeline;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OutboxPublisher(OutboxEventRepository outbox, MessageRequestRepository messages,
            NotificationService notifications, MessageBus bus, OutboxWriter writer, PipelineProperties pipeline,
            Clock clock, PlatformTransactionManager tm) {
        this.outbox = outbox;
        this.messages = messages;
        this.notifications = notifications;
        this.bus = bus;
        this.writer = writer;
        this.pipeline = pipeline;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${messaging.pipeline.scheduler.outbox-interval-ms:200}", initialDelay = 1000)
    public void tick() {
        if (!pipeline.scheduler().enabled()) {
            return;
        }
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.warn("Outbox publish cycle failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One publish cycle; returns the number of envelopes handed to the bus. Safe to call concurrently. */
    public int publishBatch() {
        List<OutboxEvent> due = outbox.findByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(OutboxEvent.NEW,
                clock.instant(), PageRequest.of(0, Math.max(1, pipeline.scheduler().outboxBatch())));
        int published = 0;
        for (OutboxEvent e : due) {
            try {
                if (publishOne(e.getId())) {
                    published++;
                }
            } catch (BusRejectedException ex) {
                log.debug("Bus refused publish ({}); outbox keeps the remaining {} rows", ex.getMessage(), due.size());
                break;
            } catch (RuntimeException ex) {
                log.warn("Outbox row {} could not be published: {}", e.getId(), ex.getClass().getSimpleName());
            }
        }
        return published;
    }

    boolean publishOne(String outboxId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (outbox.markSent(outboxId, clock.instant()) == 0) {
                return false; // another instance won the claim
            }
            OutboxEvent row = outbox.findById(outboxId).orElse(null);
            if (row == null) {
                return false;
            }
            DispatchEnvelope envelope;
            try {
                envelope = writer.parse(row.getPayload());
            } catch (IllegalArgumentException ex) {
                quarantine(row, "unreadable payload");
                return false;
            }
            MessageStatusView view = messages.findStatusViewById(envelope.messageId()).orElse(null);
            if (view == null) {
                quarantine(row, "unknown message");
                return false;
            }
            MessageStatus st = view.getStatus();
            if (st == MessageStatus.VALIDATED) {
                if (!notifications.transition(envelope.messageId(), MessageStatus.VALIDATED, MessageStatus.QUEUED,
                        "published by outbox", ACTOR)) {
                    st = messages.findStatusViewById(envelope.messageId()).map(MessageStatusView::getStatus)
                            .orElse(MessageStatus.CANCELLED);
                } else {
                    st = MessageStatus.QUEUED;
                }
            }
            if (st != MessageStatus.QUEUED) {
                log.debug("Outbox event {} skipped: message is {}", outboxId, st);
                return false;
            }
            bus.publish(envelope); // BusRejectedException rolls the claim back
            return true;
        }));
    }

    private void quarantine(OutboxEvent row, String why) {
        log.error("Outbox event {} quarantined: {}", row.getId(), why);
        row.setStatus("FAILED");
        outbox.save(row);
    }
}
