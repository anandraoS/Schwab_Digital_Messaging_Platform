package com.example.messaging.service;

import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.MessageAttempt;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.IllegalStateTransitionException;
import com.example.messaging.domain.Recipient;
import com.example.messaging.provider.ProviderOutcome;
import com.example.messaging.provider.ProviderRegistry;
import com.example.messaging.provider.ProviderStatus;
import com.example.messaging.provider.ResilientProviderInvoker;
import com.example.messaging.repository.MessageAttemptRepository;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.security.AuthorizationService;
import com.example.messaging.web.dto.MessageDtos.MessageStatusResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Operator replay of dead letters (ADR-0006). On top of the plain state transition it re-runs the delivery policy
 * (a replay never bypasses consent or suppression) and, when the last attempt ended without a clear outcome, asks
 * the provider first (ADR-0010) so an already-accepted message is not sent twice. The checks and their result are
 * audited as {@code REPLAY_PRECHECK}; the transition itself and the actor are audited by
 * {@link NotificationService#replay}. The resulting QUEUED message is published through the outbox with a new
 * {@code dispatchId} and a fresh attempt budget.
 */
@Service
@Primary
public class ReplayService implements DeadLetterOperations {

    private final NotificationService notifications;
    private final MessageRequestRepository messages;
    private final MessageAttemptRepository attempts;
    private final RecipientRepository recipients;
    private final PolicyService policy;
    private final ProviderRegistry registry;
    private final ResilientProviderInvoker invoker;
    private final AuditService audit;
    private final AuthorizationService authz;
    private final Clock clock;

    public ReplayService(NotificationService notifications, MessageRequestRepository messages,
            MessageAttemptRepository attempts, RecipientRepository recipients, PolicyService policy,
            ProviderRegistry registry, ResilientProviderInvoker invoker, AuditService audit,
            AuthorizationService authz, Clock clock) {
        this.notifications = notifications;
        this.messages = messages;
        this.attempts = attempts;
        this.recipients = recipients;
        this.policy = policy;
        this.registry = registry;
        this.invoker = invoker;
        this.audit = audit;
        this.authz = authz;
        this.clock = clock;
    }

    @Override
    public MessageStatusResponse replay(String messageId) {
        MessageRequest m = messages.findById(messageId).orElseThrow(() -> new ApiException.NotFoundException("Message"));
        authz.assertTenantAccess(m.getTenantId(), "Message");
        if (m.getStatus() != MessageStatus.DEAD_LETTERED) {
            throw new IllegalStateTransitionException(m.getStatus(), "replay");
        }
        Channel channel = m.getChannelUsed() != null ? m.getChannelUsed() : m.getChannel();
        Recipient recipient = recipients.findById(m.getRecipientId()).orElse(null);
        PolicyDecision decision = recipient == null ? PolicyDecision.reject(PolicyReason.RECIPIENT_DELETED)
                : policy.evaluate(recipient, channel, m.getCategory(), m.getPriority(), clock.instant());
        if (decision.isRejected()) {
            audit(m, "POLICY_REJECTED", Map.of("reason", decision.reason().name()));
            throw new ApiException.UnprocessableException(ErrorCode.POLICY_SUPPRESSED,
                    "Replay refused by delivery policy: " + decision.reason());
        }
        Optional<MessageAttempt> last = attempts.findFirstByMessageIdOrderByAttemptNoDesc(messageId);
        if (last.isPresent() && isUncertain(last.get()) && last.get().getProviderId() != null) {
            Optional<ProviderRegistry.Selected> provider = registry.byId(last.get().getProviderId());
            if (provider.isPresent()) {
                String ref = messageId + ":" + (m.getFallbackIndex() + 1);
                var lookup = invoker.lookup(provider.get().provider(), ref, Duration.ofSeconds(2));
                ProviderStatus.State state = lookup.status().map(ProviderStatus::state).orElse(null);
                if (state == ProviderStatus.State.ACCEPTED || state == ProviderStatus.State.DELIVERED) {
                    audit(m, "PROVIDER_ALREADY_HAS_MESSAGE", Map.of("providerState", state.name()));
                    throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                            "Replay refused: the provider already holds this message (" + state
                                    + "); reconcile instead of re-sending");
                }
                audit(m, "LOOKUP_" + (state == null ? "UNAVAILABLE" : state.name()), Map.of());
            }
        }
        audit(m, "OK", Map.of());
        return notifications.replay(messageId);
    }

    @Override
    public MessageStatusResponse discard(String messageId, String reason) {
        return notifications.discard(messageId, reason);
    }

    private static boolean isUncertain(MessageAttempt a) {
        return a.getOutcome() == null || a.getOutcome() == ProviderOutcome.TIMEOUT
                || a.getOutcome() == ProviderOutcome.AMBIGUOUS;
    }

    private void audit(MessageRequest m, String result, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>(extra);
        detail.put("result", result);
        audit.record(m.getTenantId(), AuditAction.REPLAY_PRECHECK, NotificationService.ENTITY_MESSAGE, m.getId(), detail);
    }
}
