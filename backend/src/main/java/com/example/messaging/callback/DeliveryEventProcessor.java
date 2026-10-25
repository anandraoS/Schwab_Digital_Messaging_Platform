package com.example.messaging.callback;

import com.example.messaging.callback.ProviderPayloadMapper.NormalizedEvent;
import com.example.messaging.common.Ids;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DeliveryEvent;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.domain.MessageAttempt;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStateMachine;
import com.example.messaging.domain.MessageStateMachine.ReceiptResolution;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.Recipient;
import com.example.messaging.repository.DeliveryEventRepository;
import com.example.messaging.repository.MessageAttemptRepository;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.MessageStatusView;
import com.example.messaging.repository.RecipientPreferenceRepository;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.service.AuditService;
import com.example.messaging.service.NotificationService;
import com.example.messaging.service.SystemSuppressionService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one normalised provider receipt (CONTRACT 3.4, ADR-0004/0010): dedupe on {@code (provider_id,
 * provider_event_id)}, locate the message, decide with {@link MessageStateMachine#resolveReceipt} (monotonic rank
 * rule), perform the compare-and-set transition and record the {@code delivery_event} row, all in one transaction.
 * Provider-agnostic: it only sees {@link NormalizedEvent}.
 *
 * <p>Result vocabulary (also the {@code messaging.callbacks{result}} tag): {@code accepted}, {@code duplicate},
 * {@code out_of_order}, {@code stale}, {@code conflict}, {@code unknown_message}.
 */
@Service
public class DeliveryEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventProcessor.class);
    private static final Set<MessageStatus> RECEIPT_FINAL = Set.of(MessageStatus.DELIVERED, MessageStatus.FAILED,
            MessageStatus.DEAD_LETTERED, MessageStatus.REJECTED, MessageStatus.CANCELLED);

    public record Result(String result, String messageId) {}

    private record Outcome(boolean applied, String ignoredReason, String result) {}

    private final DeliveryEventRepository events;
    private final MessageAttemptRepository attempts;
    private final MessageRequestRepository messages;
    private final RecipientRepository recipients;
    private final RecipientPreferenceRepository preferences;
    private final NotificationService notifications;
    private final SystemSuppressionService suppressions;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public DeliveryEventProcessor(DeliveryEventRepository events, MessageAttemptRepository attempts,
            MessageRequestRepository messages, RecipientRepository recipients,
            RecipientPreferenceRepository preferences, NotificationService notifications,
            SystemSuppressionService suppressions, AuditService audit, Clock clock, PlatformTransactionManager tm) {
        this.events = events;
        this.attempts = attempts;
        this.messages = messages;
        this.recipients = recipients;
        this.preferences = preferences;
        this.notifications = notifications;
        this.suppressions = suppressions;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    public Result apply(NormalizedEvent e, String rawRef) {
        try {
            return tx.execute(s -> doApply(e, rawRef));
        } catch (DataIntegrityViolationException ex) {
            // a concurrent identical event committed first (unique provider_id + provider_event_id)
            log.debug("Concurrent duplicate callback event for provider {}", e.providerId());
            return new Result("duplicate", null);
        }
    }

    private Result doApply(NormalizedEvent e, String rawRef) {
        Optional<DeliveryEvent> existing = events.findByProviderIdAndProviderEventId(e.providerId(), e.providerEventId());
        if (existing.isPresent()) {
            return new Result("duplicate", existing.get().getMessageId());
        }
        String messageId = locate(e);
        MessageStatusView view = messageId == null ? null : messages.findStatusViewById(messageId).orElse(null);
        if (view == null) {
            return new Result("unknown_message", null);
        }
        Outcome o = decide(view, e, messageId);
        DeliveryEvent row = new DeliveryEvent();
        row.setId(Ids.newId());
        row.setMessageId(messageId);
        row.setProviderId(e.providerId());
        row.setProviderEventId(e.providerEventId());
        row.setType(e.type());
        row.setOccurredAt(e.occurredAt());
        row.setReceivedAt(clock.instant());
        row.setApplied(o.applied());
        row.setIgnoredReason(o.ignoredReason());
        row.setRawRef(rawRef);
        events.saveAndFlush(row);
        return new Result(o.result(), messageId);
    }

    private String locate(NormalizedEvent e) {
        if (e.providerMessageId() != null) {
            Optional<MessageAttempt> a = attempts.findFirstByProviderIdAndProviderMessageIdOrderByStartedAtDesc(
                    e.providerId(), e.providerMessageId());
            if (a.isPresent()) {
                return a.get().getMessageId();
            }
        }
        if (e.idempotencyRef() != null) {
            int colon = e.idempotencyRef().indexOf(':');
            String candidate = colon > 0 ? e.idempotencyRef().substring(0, colon) : e.idempotencyRef();
            if (messages.existsById(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Outcome decide(MessageStatusView view, NormalizedEvent e, String messageId) {
        DeliveryEventType type = e.type();
        String actor = "callback:" + e.providerId();
        if (type.rank() == 2) { // engagement (opened, clicked, unsubscribed): informational, never moves the status
            if (type == DeliveryEventType.UNSUBSCRIBED) {
                optOut(messageId, actor);
            }
            return new Outcome(true, null, "accepted");
        }
        MessageStatus current = view.getStatus();
        for (int attempt = 0; attempt < 3; attempt++) {
            ReceiptResolution r = MessageStateMachine.resolveReceipt(current, type);
            switch (r) {
                case APPLY_DELIVERED, APPLY_FAILED -> {
                    MessageStatus to = r == ReceiptResolution.APPLY_DELIVERED ? MessageStatus.DELIVERED
                            : MessageStatus.FAILED;
                    String reason = "receipt " + type + " from " + e.providerId()
                            + (safeCode(e.reasonCode()) == null ? "" : " (" + safeCode(e.reasonCode()) + ")");
                    if (notifications.transition(messageId, current, to, reason, actor, true)) {
                        if (to == MessageStatus.FAILED) {
                            failureSideEffects(messageId, e, actor);
                        }
                        return new Outcome(true, null, "accepted");
                    }
                    current = messages.findStatusViewById(messageId).map(MessageStatusView::getStatus).orElse(current);
                }
                case IGNORE_LOWER_RANK -> {
                    if (type.rank() == 3) { // same terminal receipt arriving again under a new event id
                        return new Outcome(false, "STALE", "stale");
                    }
                    if (RECEIPT_FINAL.contains(current)) { // SENT/ACCEPTED after the outcome is known
                        return new Outcome(false, "OUT_OF_ORDER", "out_of_order");
                    }
                    if (current == MessageStatus.PROCESSING && (type == DeliveryEventType.PROVIDER_ACCEPTED
                            || type == DeliveryEventType.SENT)) {
                        // the receipt overtook the consumer's own persist: move on, the consumer tolerates losing the CAS
                        notifications.transition(messageId, MessageStatus.PROCESSING, MessageStatus.SENT_TO_PROVIDER,
                                "receipt " + type + " from " + e.providerId(), actor);
                    }
                    return new Outcome(true, null, "accepted");
                }
                case CONFLICT -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("providerId", e.providerId());
                    detail.put("providerEventId", e.providerEventId());
                    detail.put("eventType", type.name());
                    detail.put("currentStatus", current.name());
                    audit.recordAs(actor, "SYSTEM", view.getTenantId(), AuditAction.DELIVERY_EVENT_CONFLICT,
                            NotificationService.ENTITY_MESSAGE, messageId, detail);
                    return new Outcome(false, "CONFLICT", "conflict");
                }
                case IGNORE_STATE -> {
                    return new Outcome(false, "STATE_NOT_RECEIVABLE", "out_of_order");
                }
                default -> throw new IllegalStateException("unhandled resolution " + r);
            }
        }
        return new Outcome(false, "STATE_CHANGED", "out_of_order");
    }

    /** Only code-like reasons (e.g. {@code MAILBOX_FULL}) are kept; free text may carry personal data. */
    private static String safeCode(String s) {
        return s != null && s.matches("[A-Z][A-Z0-9_]{0,39}") ? s : null;
    }

    private void failureSideEffects(String messageId, NormalizedEvent e, String actor) {
        boolean hardBounce = e.type() == DeliveryEventType.BOUNCED;
        boolean tokenInvalid = "TOKEN_INVALID".equals(e.reasonCode());
        if (!hardBounce && !tokenInvalid) {
            return;
        }
        MessageRequest m = messages.findById(messageId).orElse(null);
        if (m == null) {
            return;
        }
        Recipient r = recipients.findById(m.getRecipientId()).orElse(null);
        if (r == null) {
            return;
        }
        Channel channel = tokenInvalid ? Channel.PUSH : (m.getChannelUsed() != null ? m.getChannelUsed() : m.getChannel());
        suppressions.suppress(m.getTenantId(), channel, r.addressFor(channel),
                tokenInvalid ? "TOKEN_INVALID" : "HARD_BOUNCE", actor, messageId);
    }

    private void optOut(String messageId, String actor) {
        MessageRequest m = messages.findById(messageId).orElse(null);
        if (m == null) {
            return;
        }
        preferences.findById(m.getRecipientId()).ifPresent(p -> {
            if (p.isMarketingOptIn()) {
                p.setMarketingOptIn(false);
                p.setUpdatedAt(clock.instant());
                preferences.save(p);
                audit.recordAs(actor, "SYSTEM", m.getTenantId(), AuditAction.PREFERENCE_UPDATED, "RECIPIENT",
                        m.getRecipientId(), Map.of("marketingOptIn", false, "source", "UNSUBSCRIBE_CALLBACK"));
            }
        });
    }
}
