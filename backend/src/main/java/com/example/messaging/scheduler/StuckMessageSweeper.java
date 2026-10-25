package com.example.messaging.scheduler;

import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.service.AuditService;
import com.example.messaging.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repairs the two states a crash or a lost receipt can leave behind (ADR-0010), always towards
 * {@code DELIVERY_UNKNOWN} and never by blindly retrying:
 * <ul>
 *   <li>{@code PROCESSING} with an expired {@code locked_until}: the consumer died after claiming (possibly after the
 *       provider accepted). The attempt row stays {@code STARTED} so reconciliation can see it.</li>
 *   <li>{@code SENT_TO_PROVIDER} older than {@code messaging.dispatch.receipt-timeout}: no delivery receipt arrived.</li>
 * </ul>
 * Idempotent and multi-instance safe: each move is a compare-and-set, the loser simply skips.
 */
@Component
public class StuckMessageSweeper {

    private static final Logger log = LoggerFactory.getLogger(StuckMessageSweeper.class);
    private static final String ACTOR = "stuck-sweeper";

    private final MessageRequestRepository messages;
    private final NotificationService notifications;
    private final AuditService audit;
    private final MessagingProperties props;
    private final PipelineProperties pipeline;
    private final Clock clock;
    private final TransactionTemplate tx;

    public StuckMessageSweeper(MessageRequestRepository messages, NotificationService notifications,
            AuditService audit, MessagingProperties props, PipelineProperties pipeline, Clock clock,
            PlatformTransactionManager tm) {
        this.messages = messages;
        this.notifications = notifications;
        this.audit = audit;
        this.props = props;
        this.pipeline = pipeline;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${messaging.pipeline.scheduler.sweeper-interval-ms:10000}", initialDelay = 5000)
    public void tick() {
        if (!pipeline.scheduler().enabled()) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Stuck-message sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return number of messages moved to DELIVERY_UNKNOWN */
    public int sweep() {
        Instant now = clock.instant();
        int batch = Math.max(1, pipeline.scheduler().sweepBatch());
        int moved = 0;
        List<MessageRequest> stuck = messages.findByStatusAndLockedUntilBefore(MessageStatus.PROCESSING, now,
                PageRequest.of(0, batch));
        for (MessageRequest m : stuck) {
            moved += markUnknown(m, MessageStatus.PROCESSING, "lock expired (consumer presumed dead)");
        }
        List<MessageRequest> silent = messages.findByStatusAndStatusChangedAtBefore(MessageStatus.SENT_TO_PROVIDER,
                now.minus(props.dispatch().receiptTimeout()), PageRequest.of(0, batch));
        for (MessageRequest m : silent) {
            moved += markUnknown(m, MessageStatus.SENT_TO_PROVIDER, "no delivery receipt within the receipt timeout");
        }
        return moved;
    }

    private int markUnknown(MessageRequest m, MessageStatus from, String why) {
        try {
            Boolean ok = tx.execute(s -> {
                if (!notifications.transition(m.getId(), from, MessageStatus.DELIVERY_UNKNOWN, "STUCK_SWEEP: " + why,
                        ACTOR)) {
                    return false;
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("from", from.name());
                detail.put("reason", why);
                audit.recordAs(ACTOR, "SYSTEM", m.getTenantId(), AuditAction.STUCK_SWEEP,
                        NotificationService.ENTITY_MESSAGE, m.getId(), detail);
                return true;
            });
            if (Boolean.TRUE.equals(ok)) {
                log.info("Message {} moved {} -> DELIVERY_UNKNOWN by the sweeper", m.getId(), from);
                return 1;
            }
        } catch (RuntimeException e) {
            log.warn("Sweep of message {} failed: {}", m.getId(), e.getClass().getSimpleName());
        }
        return 0;
    }
}
