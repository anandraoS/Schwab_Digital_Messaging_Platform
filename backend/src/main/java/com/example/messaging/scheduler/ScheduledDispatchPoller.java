package com.example.messaging.scheduler;

import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.messaging.BusRejectedException;
import com.example.messaging.messaging.DispatchEnvelope;
import com.example.messaging.messaging.MessageBus;
import com.example.messaging.messaging.OutboxWriter;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.OutboxEventRepository;
import com.example.messaging.repository.ProcessedMessageRepository;
import com.example.messaging.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Safety nets for dispatches that the normal paths missed (dispatch-retry-flow.md):
 * <ul>
 *   <li>{@code RETRY_PENDING} messages whose backoff elapsed but whose delayed envelope never arrived (bus
 *       restarted, redelivery lost): CAS RETRY_PENDING &rarr; QUEUED and publish a new envelope. Only rows overdue by
 *       {@code retry-grace} are taken so the poller does not race the bus's own delayed redelivery; if both fire the
 *       consumer's dedupe keeps it to one send.</li>
 *   <li>{@code VALIDATED} messages that are due but have no pending outbox row (should not happen, repaired
 *       anyway): a new outbox row is written.</li>
 *   <li>{@code QUEUED} messages older than {@code messaging.pipeline.queued-stale-after} that have no
 *       {@code processed_message} row for their next dispatch and no pending outbox row (the envelope was lost,
 *       typically with a restarted in-memory bus): the staleness clock is moved forward with a CAS (so concurrent
 *       pollers publish once) and the envelope is published again. A duplicate of an envelope that is still alive is
 *       harmless, the consumer dedupes on {@code dispatchId}.</li>
 * </ul>
 */
@Component
public class ScheduledDispatchPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledDispatchPoller.class);
    private static final String ACTOR = "scheduled-poller";

    private final MessageRequestRepository messages;
    private final OutboxEventRepository outbox;
    private final ProcessedMessageRepository processed;
    private final NotificationService notifications;
    private final MessageBus bus;
    private final OutboxWriter writer;
    private final PipelineProperties pipeline;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ScheduledDispatchPoller(MessageRequestRepository messages, OutboxEventRepository outbox,
            ProcessedMessageRepository processed, NotificationService notifications, MessageBus bus, OutboxWriter writer, PipelineProperties pipeline,
            Clock clock, PlatformTransactionManager tm) {
        this.messages = messages;
        this.outbox = outbox;
        this.processed = processed;
        this.notifications = notifications;
        this.bus = bus;
        this.writer = writer;
        this.pipeline = pipeline;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${messaging.pipeline.scheduler.poller-interval-ms:1000}", initialDelay = 2000)
    public void tick() {
        if (!pipeline.scheduler().enabled()) {
            return;
        }
        try {
            pollRetries();
            pollOrphans();
            pollStaleQueued();
        } catch (RuntimeException e) {
            log.warn("Scheduled dispatch poll failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return number of retries re-enqueued */
    public int pollRetries() {
        Instant overdue = clock.instant().minus(pipeline.scheduler().retryGrace());
        List<MessageRequest> rows = messages.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                MessageStatus.RETRY_PENDING, overdue, PageRequest.of(0, Math.max(1, pipeline.scheduler().sweepBatch())));
        int n = 0;
        for (MessageRequest m : rows) {
            try {
                Boolean moved = tx.execute(s -> {
                    if (!notifications.transition(m.getId(), MessageStatus.RETRY_PENDING, MessageStatus.QUEUED,
                            "retry due (poller)", ACTOR)) {
                        return false;
                    }
                    Channel ch = m.getChannelUsed() != null ? m.getChannelUsed() : m.getChannel();
                    bus.publish(DispatchEnvelope.of(m.getId(), m.getTenantId(), ch, m.getPriority(),
                            m.getAttemptCount() + 1, m.getCorrelationId(), clock.instant()));
                    return true;
                });
                if (Boolean.TRUE.equals(moved)) {
                    n++;
                }
            } catch (BusRejectedException e) {
                log.debug("Bus refused retry publish; will retry on the next poll");
                break;
            } catch (RuntimeException e) {
                log.warn("Retry re-enqueue failed for message {}: {}", m.getId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    /** @return number of outbox rows written for orphaned VALIDATED messages */
    public int pollOrphans() {
        Instant now = clock.instant();
        List<MessageRequest> rows = messages.findByStatusAndStatusChangedAtBefore(MessageStatus.VALIDATED,
                now.minus(pipeline.scheduler().orphanAge()), PageRequest.of(0, Math.max(1, pipeline.scheduler().sweepBatch())));
        int n = 0;
        for (MessageRequest m : rows) {
            if (m.getScheduledAt() != null && m.getScheduledAt().isAfter(now)) {
                continue; // scheduled for later: its outbox row is intentionally not due yet
            }
            if (outbox.existsByAggregateIdAndStatus(m.getId(), "NEW")) {
                continue;
            }
            try {
                tx.executeWithoutResult(s -> writer.enqueue(DispatchEnvelope.of(m.getId(), m.getTenantId(), m.getChannel(),
                        m.getPriority(), m.getAttemptCount() + 1, m.getCorrelationId(), now), now));
                n++;
            } catch (RuntimeException e) {
                log.warn("Orphan repair failed for message {}: {}", m.getId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    /**
     * Re-publishes QUEUED messages whose envelope was lost (see class comment).
     *
     * @return number of envelopes published again
     */
    public int pollStaleQueued() {
        Instant now = clock.instant();
        Instant before = now.minus(pipeline.queuedStaleAfter());
        List<MessageRequest> rows = messages.findByStatusAndStatusChangedAtBefore(MessageStatus.QUEUED, before,
                PageRequest.of(0, Math.max(1, pipeline.scheduler().sweepBatch())));
        int n = 0;
        for (MessageRequest m : rows) {
            int attemptNo = m.getAttemptCount() + 1;
            if (processed.existsByDispatchId(m.getId() + ":" + attemptNo)) {
                continue; // being (or already) processed by a consumer
            }
            if (outbox.existsByAggregateIdAndStatus(m.getId(), "NEW")) {
                continue; // deliberately parked in the outbox (quiet hours, requeue)
            }
            try {
                Boolean published = tx.execute(s -> {
                    if (messages.touchQueued(m.getId(), m.getAttemptCount(), before, now) != 1) {
                        return false; // lost the race: state changed or another poller took it
                    }
                    Channel ch = m.getChannelUsed() != null ? m.getChannelUsed() : m.getChannel();
                    bus.publish(DispatchEnvelope.of(m.getId(), m.getTenantId(), ch, m.getPriority(), attemptNo,
                            m.getCorrelationId(), now));
                    return true;
                });
                if (Boolean.TRUE.equals(published)) {
                    n++;
                    log.info("Re-published stale QUEUED message {} (attempt {})", m.getId(), attemptNo);
                }
            } catch (BusRejectedException e) {
                log.debug("Bus refused stale-queued publish; will retry on the next poll");
                break;
            } catch (RuntimeException e) {
                log.warn("Stale QUEUED re-publish failed for message {}: {}", m.getId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }
}
