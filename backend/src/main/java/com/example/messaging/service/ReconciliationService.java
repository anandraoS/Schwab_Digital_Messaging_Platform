package com.example.messaging.service;

import com.example.messaging.common.Ids;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.AuditEvent;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DeliveryEvent;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.domain.MessageAttempt;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.messaging.DispatchEnvelope;
import com.example.messaging.messaging.OutboxWriter;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.provider.ProviderRegistry;
import com.example.messaging.provider.ProviderStatus;
import com.example.messaging.provider.ResilientProviderInvoker;
import com.example.messaging.repository.AuditEventRepository;
import com.example.messaging.repository.DeliveryEventRepository;
import com.example.messaging.repository.MessageAttemptRepository;
import com.example.messaging.repository.MessageRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves {@code DELIVERY_UNKNOWN} messages by asking the provider what it knows about the idempotency reference
 * ({@code messageId:attemptGroup}, ADR-0010). Only messages older than {@code messaging.dispatch.reconcile-after} are
 * looked at, checks are spaced by that interval and bounded by {@code reconcile-max-checks} (afterwards the message
 * stays visible in {@code GET /failed-messages} for an operator). Every check is audited with its lookup result.
 *
 * <p>Lookup results: DELIVERED and FAILED are applied (with a {@code delivery_event} recorded as evidence);
 * ACCEPTED leaves the message unknown (the receipt will come); NOT_RECEIVED triggers a safe re-queue as a new attempt
 * with the same idempotency reference (FAILED then QUEUED, both table-legal, in one transaction); a failed or
 * unsupported lookup changes nothing. Idempotent and multi-instance safe through compare-and-set transitions.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String ACTOR = "reconciliation";

    /** What one reconciliation pass did. */
    public record Summary(int checked, int delivered, int failed, int requeued, int stillUnknown, int lookupFailures) {}

    private final MessageRequestRepository messages;
    private final MessageAttemptRepository attempts;
    private final DeliveryEventRepository events;
    private final AuditEventRepository auditEvents;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ProviderRegistry registry;
    private final ResilientProviderInvoker invoker;
    private final OutboxWriter outbox;
    private final MessagingMetrics metrics;
    private final MessagingProperties props;
    private final PipelineProperties pipeline;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ReconciliationService(MessageRequestRepository messages, MessageAttemptRepository attempts,
            DeliveryEventRepository events, AuditEventRepository auditEvents, NotificationService notifications,
            AuditService audit, ProviderRegistry registry, ResilientProviderInvoker invoker, OutboxWriter outbox,
            MessagingMetrics metrics, MessagingProperties props, PipelineProperties pipeline, Clock clock,
            PlatformTransactionManager tm) {
        this.messages = messages;
        this.attempts = attempts;
        this.events = events;
        this.auditEvents = auditEvents;
        this.notifications = notifications;
        this.audit = audit;
        this.registry = registry;
        this.invoker = invoker;
        this.outbox = outbox;
        this.metrics = metrics;
        this.props = props;
        this.pipeline = pipeline;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelayString = "${messaging.pipeline.scheduler.reconcile-interval-ms:15000}", initialDelay = 7000)
    public void tick() {
        if (!pipeline.scheduler().enabled()) {
            return;
        }
        try {
            reconcileDue();
        } catch (RuntimeException e) {
            log.warn("Reconciliation pass failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One bounded pass over the DELIVERY_UNKNOWN messages that are old enough. */
    public Summary reconcileDue() {
        Instant cutoff = clock.instant().minus(props.dispatch().reconcileAfter());
        List<MessageRequest> due = messages.findByStatusAndStatusChangedAtBefore(MessageStatus.DELIVERY_UNKNOWN, cutoff,
                PageRequest.of(0, Math.max(1, pipeline.scheduler().reconcileBatch())));
        int checked = 0;
        int delivered = 0;
        int failed = 0;
        int requeued = 0;
        int unknown = 0;
        int lookupFailures = 0;
        for (MessageRequest m : due) {
            String r;
            try {
                r = reconcileOne(m.getId());
            } catch (RuntimeException e) {
                log.warn("Reconciliation of message {} failed: {}", m.getId(), e.getClass().getSimpleName());
                continue;
            }
            switch (r) {
                case "DELIVERED" -> {
                    checked++;
                    delivered++;
                }
                case "FAILED" -> {
                    checked++;
                    failed++;
                }
                case "NOT_RECEIVED" -> {
                    checked++;
                    requeued++;
                }
                case "ACCEPTED" -> {
                    checked++;
                    unknown++;
                }
                case "LOOKUP_UNAVAILABLE" -> {
                    checked++;
                    lookupFailures++;
                }
                default -> { /* SKIPPED, RACE */ }
            }
        }
        return new Summary(checked, delivered, failed, requeued, unknown, lookupFailures);
    }

    /**
     * Reconciles one message now (also used by operators' tooling and tests).
     *
     * @return DELIVERED, FAILED, NOT_RECEIVED, ACCEPTED, LOOKUP_UNAVAILABLE, SKIPPED (not due / not unknown / checks
     *         exhausted) or RACE (lost a compare-and-set)
     */
    public String reconcileOne(String messageId) {
        MessageRequest m = messages.findById(messageId).orElse(null);
        if (m == null || m.getStatus() != MessageStatus.DELIVERY_UNKNOWN) {
            return "SKIPPED";
        }
        Instant now = clock.instant();
        long checks = auditEvents.countByEntityTypeAndEntityIdAndAction(NotificationService.ENTITY_MESSAGE, messageId,
                AuditAction.RECONCILIATION_CHECK);
        if (checks >= pipeline.scheduler().reconcileMaxChecks()) {
            return "SKIPPED";
        }
        Optional<AuditEvent> last = auditEvents.findFirstByEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(
                NotificationService.ENTITY_MESSAGE, messageId, AuditAction.RECONCILIATION_CHECK);
        if (last.isPresent() && last.get().getOccurredAt().isAfter(now.minus(props.dispatch().reconcileAfter()))) {
            return "SKIPPED"; // checked recently: spacing between re-checks
        }
        Optional<MessageAttempt> attempt = attempts.findFirstByMessageIdOrderByAttemptNoDesc(messageId);
        String ref = messageId + ":" + (m.getFallbackIndex() + 1);
        ProviderStatus status = null;
        boolean lookupFailed = false;
        if (attempt.isPresent() && attempt.get().getProviderId() != null) {
            Optional<ProviderRegistry.Selected> provider = registry.byId(attempt.get().getProviderId());
            if (provider.isEmpty()) {
                lookupFailed = true;
            } else {
                ResilientProviderInvoker.LookupResult lr = invoker.lookup(provider.get().provider(), ref,
                        Duration.ofMillis(Math.max(100, provider.get().config().getTimeoutMs())));
                if (lr.failed() || lr.status().isEmpty()) {
                    lookupFailed = true; // failed, timed out or the provider has no lookup capability
                } else {
                    status = lr.status().get();
                }
            }
        } else {
            // no attempt ever reached a provider (e.g. crash between claim and call): nothing can have been sent
            status = new ProviderStatus(ProviderStatus.State.NOT_RECEIVED, null, null);
        }
        long checkNo = checks + 1;
        String result = lookupFailed ? "LOOKUP_UNAVAILABLE" : status.state().name();
        ProviderStatus finalStatus = status;
        Boolean applied = tx.execute(s -> apply(m, attempt.orElse(null), ref, finalStatus, result, checkNo));
        String outcome = Boolean.TRUE.equals(applied) ? result : "RACE";
        metrics.registry().counter("messaging.reconciliation", "result", outcome).increment();
        return outcome;
    }

    private boolean apply(MessageRequest m, MessageAttempt attempt, String ref, ProviderStatus status, String result,
            long checkNo) {
        String id = m.getId();
        if (status != null) {
            switch (status.state()) {
                case DELIVERED -> {
                    if (!notifications.transition(id, MessageStatus.DELIVERY_UNKNOWN, MessageStatus.DELIVERED,
                            "reconciliation: provider confirms delivery", ACTOR)) {
                        return false;
                    }
                    recordEvidence(m, attempt, DeliveryEventType.DELIVERED, checkNo);
                }
                case FAILED -> {
                    if (!notifications.transition(id, MessageStatus.DELIVERY_UNKNOWN, MessageStatus.FAILED,
                            "reconciliation: provider reports failure", ACTOR)) {
                        return false;
                    }
                    recordEvidence(m, attempt, DeliveryEventType.FAILED, checkNo);
                }
                case NOT_RECEIVED -> {
                    // safe re-queue as a NEW attempt with the same idempotency reference group
                    if (!notifications.transition(id, MessageStatus.DELIVERY_UNKNOWN, MessageStatus.FAILED,
                            "reconciliation: provider never received it", ACTOR)
                            || !notifications.transition(id, MessageStatus.FAILED, MessageStatus.QUEUED,
                                    "reconciliation: safe retry as a new attempt", ACTOR)) {
                        return false;
                    }
                    Channel ch = m.getChannelUsed() != null ? m.getChannelUsed() : m.getChannel();
                    Instant now = clock.instant();
                    outbox.enqueue(DispatchEnvelope.of(id, m.getTenantId(), ch, m.getPriority(), m.getAttemptCount() + 1,
                            m.getCorrelationId(), now), now);
                }
                default -> { /* ACCEPTED: the receipt is still to come; stay DELIVERY_UNKNOWN */ }
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", result);
        detail.put("check", checkNo);
        detail.put("idempotencyRef", ref);
        detail.put("providerId", attempt == null ? null : attempt.getProviderId());
        audit.recordAs(ACTOR, "SYSTEM", m.getTenantId(), AuditAction.RECONCILIATION_CHECK,
                NotificationService.ENTITY_MESSAGE, id, detail);
        return true;
    }

    private void recordEvidence(MessageRequest m, MessageAttempt attempt, DeliveryEventType type, long checkNo) {
        if (attempt == null || attempt.getProviderId() == null) {
            return;
        }
        DeliveryEvent e = new DeliveryEvent();
        e.setId(Ids.newId());
        e.setMessageId(m.getId());
        e.setProviderId(attempt.getProviderId());
        e.setProviderEventId("recon:" + m.getId() + ":" + checkNo);
        e.setType(type);
        e.setOccurredAt(clock.instant());
        e.setReceivedAt(clock.instant());
        e.setApplied(true);
        events.save(e);
    }
}
