package com.example.messaging.messaging;

import com.example.messaging.common.Ids;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.PipelineProperties;
import com.example.messaging.coordination.CoordinationService;
import com.example.messaging.coordination.RateLimitDecision;
import com.example.messaging.diagnostics.ProviderDiagnosticsStore;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.MessageAttempt;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.ProcessedMessage;
import com.example.messaging.domain.Recipient;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.provider.ChannelProvider;
import com.example.messaging.provider.ProviderOutcome;
import com.example.messaging.provider.ProviderRegistry;
import com.example.messaging.provider.ProviderRequest;
import com.example.messaging.provider.ProviderResult;
import com.example.messaging.provider.ResilientProviderInvoker;
import com.example.messaging.repository.MessageAttemptRepository;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.ProcessedMessageRepository;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.service.AuditService;
import com.example.messaging.service.NotificationService;
import com.example.messaging.service.PolicyDecision;
import com.example.messaging.service.PolicyService;
import com.example.messaging.service.RetryPolicy;
import com.example.messaging.service.SystemSuppressionService;
import com.example.messaging.service.TemplateService;
import com.example.messaging.template.RenderedContent;
import com.example.messaging.template.TemplateRenderException;
import com.example.messaging.template.TemplateRenderer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dispatch consumer (CONTRACT 6, docs/architecture/dispatch-retry-flow.md). For every delivery it:
 * <ol>
 *   <li>dedupes ({@code processed_message}) and checks the message is still dispatchable (cancelled / stale
 *       envelopes are acknowledged and skipped);</li>
 *   <li>re-evaluates expiry and policy (suppression / opt-out &rArr; REJECTED, quiet hours &rArr; deferred through the
 *       outbox);</li>
 *   <li>checks the circuit and the provider token bucket <em>before</em> claiming, so back-pressure requeues never
 *       consume an attempt;</li>
 *   <li>claims in ONE transaction: unique {@code processed_message} row + compare-and-set QUEUED&rarr;PROCESSING +
 *       lock deadline + attempt row {@code STARTED} (a crash after this point is repaired by the sweeper);</li>
 *   <li>renders with the pinned template version, calls the provider through {@link ResilientProviderInvoker} and maps
 *       the outcome (ADR-0003/0006/0010).</li>
 * </ol>
 * Message content and addresses are never logged.
 */
@Component
public class DispatchConsumer implements DispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(DispatchConsumer.class);
    public static final String ACTOR = "dispatch-consumer";
    private static final int MAX_VALIDATED_WAITS = 40;
    private static final Set<String> HARD_BOUNCE_CODES = Set.of("HARD_BOUNCE", "INVALID_NUMBER", "MAILBOX_NOT_FOUND");
    private static final Duration MAX_RETRY_WAIT = Duration.ofMinutes(10);

    private enum Claim { WON, DUPLICATE, LOST }

    private final MessageRequestRepository messages;
    private final MessageAttemptRepository attempts;
    private final ProcessedMessageRepository processed;
    private final RecipientRepository recipients;
    private final NotificationService notifications;
    private final PolicyService policy;
    private final TemplateService templates;
    private final TemplateRenderer renderer;
    private final ProviderRegistry registry;
    private final ResilientProviderInvoker invoker;
    private final CoordinationService coordination;
    private final MessageBus bus;
    private final RetryPolicy retry;
    private final MessagingMetrics metrics;
    private final MessagingProperties props;
    private final PipelineProperties pipeline;
    private final ProviderDiagnosticsStore diagnostics;
    private final OutboxWriter outbox;
    private final AuditService audit;
    private final SystemSuppressionService suppressions;
    private final ObjectProvider<DispatchHook> hooks;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public DispatchConsumer(MessageRequestRepository messages, MessageAttemptRepository attempts,
            ProcessedMessageRepository processed, RecipientRepository recipients, NotificationService notifications,
            PolicyService policy, TemplateService templates, TemplateRenderer renderer, ProviderRegistry registry,
            ResilientProviderInvoker invoker, CoordinationService coordination, MessageBus bus, RetryPolicy retry,
            MessagingMetrics metrics, MessagingProperties props, PipelineProperties pipeline,
            ProviderDiagnosticsStore diagnostics, OutboxWriter outbox, AuditService audit,
            SystemSuppressionService suppressions, ObjectProvider<DispatchHook> hooks, ObjectMapper json, Clock clock,
            PlatformTransactionManager tm) {
        this.messages = messages;
        this.attempts = attempts;
        this.processed = processed;
        this.recipients = recipients;
        this.notifications = notifications;
        this.policy = policy;
        this.templates = templates;
        this.renderer = renderer;
        this.registry = registry;
        this.invoker = invoker;
        this.coordination = coordination;
        this.bus = bus;
        this.retry = retry;
        this.metrics = metrics;
        this.props = props;
        this.pipeline = pipeline;
        this.diagnostics = diagnostics;
        this.outbox = outbox;
        this.audit = audit;
        this.suppressions = suppressions;
        this.hooks = hooks;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    // ================================================================== entry point

    @Override
    public BusDecision handle(DispatchEnvelope env, int deliveryCount) {
        if (env == null || env.messageId() == null || env.dispatchId() == null || env.attemptNo() < 1) {
            log.warn("Unreadable dispatch envelope: dead-lettering as poison");
            return BusDecision.deadLetter(DlqReason.POISON_MESSAGE);
        }
        if (env.correlationId() != null) {
            MDC.put("correlationId", env.correlationId());
        }
        MDC.put("messageId", env.messageId());
        if (env.tenantId() != null) {
            MDC.put("tenant", env.tenantId());
        }
        try {
            hooks.orderedStream().forEach(h -> h.beforeProcess(env, deliveryCount));
            return process(env, deliveryCount);
        } finally {
            MDC.remove("messageId");
            MDC.remove("tenant");
            MDC.remove("correlationId");
        }
    }

    private BusDecision process(DispatchEnvelope env, int deliveryCount) {
        MessageRequest m = messages.findById(env.messageId()).orElse(null);
        if (m == null) {
            log.warn("Dispatch {} refers to an unknown message: dead-lettering as poison", env.dispatchId());
            return BusDecision.deadLetter(DlqReason.POISON_MESSAGE);
        }
        if (processed.existsByDispatchId(env.dispatchId())) {
            log.debug("Dispatch {} already processed: skipping duplicate delivery", env.dispatchId());
            return BusDecision.ack();
        }
        Instant now = clock.instant();
        switch (m.getStatus()) {
            case QUEUED -> { /* dispatchable */ }
            case VALIDATED -> {
                // the envelope overtook the outbox publisher's commit of VALIDATED -> QUEUED
                if (deliveryCount > MAX_VALIDATED_WAITS) {
                    log.warn("Dispatch {} still VALIDATED after {} deliveries: dead-lettering", env.dispatchId(),
                            deliveryCount);
                    return BusDecision.deadLetter(DlqReason.POISON_MESSAGE);
                }
                return BusDecision.requeue(pipeline.validatedRequeueDelay());
            }
            case RETRY_PENDING -> {
                if (env.attemptNo() != m.getAttemptCount() + 1) {
                    return BusDecision.ack();
                }
                Instant due = m.getNextAttemptAt();
                if (due != null && due.isAfter(now.plusMillis(250))) {
                    Duration rest = Duration.between(now, due);
                    return BusDecision.requeue(rest.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : rest);
                }
                notifications.transition(m.getId(), MessageStatus.RETRY_PENDING, MessageStatus.QUEUED, "retry due",
                        ACTOR);
                m = messages.findById(env.messageId()).orElse(null);
                if (m == null || m.getStatus() != MessageStatus.QUEUED) {
                    return BusDecision.ack();
                }
            }
            default -> {
                log.debug("Dispatch {} skipped: message is {}", env.dispatchId(), m.getStatus());
                return BusDecision.ack();
            }
        }
        if (env.attemptNo() != m.getAttemptCount() + 1) {
            log.debug("Dispatch {} is stale (attemptCount={})", env.dispatchId(), m.getAttemptCount());
            return BusDecision.ack();
        }
        Channel channel = m.getChannelUsed() != null ? m.getChannelUsed() : m.getChannel();
        Recipient recipient = recipients.findById(m.getRecipientId()).orElse(null);

        // ---- expiry and policy re-evaluation (cancel is already covered by the status check above)
        if (m.getExpiresAt() != null && !now.isBefore(m.getExpiresAt())) {
            return terminalWithoutAttempt(env, m, channel, MessageStatus.FAILED, "EXPIRED");
        }
        PolicyDecision decision = recipient == null ? PolicyDecision.reject(
                com.example.messaging.service.PolicyReason.RECIPIENT_DELETED)
                : policy.evaluate(recipient, channel, m.getCategory(), m.getPriority(), now);
        if (decision.isRejected()) {
            return terminalWithoutAttempt(env, m, channel, MessageStatus.REJECTED, decision.reason().name());
        }
        if (decision.isDeferred()) {
            if (m.getExpiresAt() != null && !m.getExpiresAt().isAfter(decision.until())) {
                return terminalWithoutAttempt(env, m, channel, MessageStatus.REJECTED, "EXPIRES_BEFORE_DEFERRED_SEND");
            }
            outbox.enqueue(env, decision.until()); // message stays QUEUED; the publisher re-publishes at the window end
            log.info("Dispatch {} deferred until {} (quiet hours)", env.dispatchId(), decision.until());
            return BusDecision.ack();
        }

        // ---- provider, circuit, rate limit: all before the claim so none of them consumes an attempt
        Optional<ProviderRegistry.Selected> selected = registry.select(channel);
        if (selected.isEmpty()) {
            log.warn("No enabled provider for channel {}: requeueing without consuming an attempt", channel);
            return BusDecision.requeue(Duration.ofSeconds(2));
        }
        ChannelProvider provider = selected.get().provider();
        int rate = Math.max(1, selected.get().config().getRateLimitPerSec());
        if (invoker.isOpen(provider.providerId())) {
            return BusDecision.requeue(clamp(pipeline.resilience().openStateWait().dividedBy(2),
                    Duration.ofMillis(250), Duration.ofSeconds(5)));
        }
        // reserve the circuit-breaker permit before the claim: a refused reservation must not cost an attempt
        Optional<ResilientProviderInvoker.Permit> permit = invoker.acquire(provider.providerId());
        if (permit.isEmpty()) {
            return BusDecision.requeue(clamp(pipeline.resilience().openStateWait().dividedBy(4),
                    Duration.ofMillis(100), Duration.ofSeconds(2)));
        }
        RateLimitDecision limit = coordination.tryAcquire("provider:" + provider.providerId(), rate, rate);
        if (!limit.allowed()) {
            invoker.release(permit.get());
            metrics.rateLimitRejected("provider");
            Duration wait = Duration.ofMillis(Math.max(limit.retryAfterMillis(), 1));
            return BusDecision.requeue(wait.compareTo(pipeline.rateLimitRequeueDelay()) < 0
                    ? pipeline.rateLimitRequeueDelay() : wait);
        }

        // ---- atomic claim
        Claim claim;
        try {
            claim = claim(env, m, channel, provider.providerId(), true);
        } catch (RuntimeException e) {
            invoker.release(permit.get());
            throw e;
        }
        if (claim != Claim.WON) {
            invoker.release(permit.get());
            log.debug("Dispatch {} not claimed ({}): acknowledging", env.dispatchId(), claim);
            return BusDecision.ack();
        }
        return dispatch(env, m, channel, recipient, selected.get(), permit.get());
    }

    // ================================================================== claim

    private Claim claim(DispatchEnvelope env, MessageRequest m, Channel channel, String providerId,
            boolean countAttempt) {
        try {
            Claim result = tx.execute(status -> {
                if (processed.existsByDispatchId(env.dispatchId())) {
                    return Claim.DUPLICATE;
                }
                ProcessedMessage pm = new ProcessedMessage();
                pm.setId(Ids.newId());
                pm.setDispatchId(env.dispatchId());
                pm.setMessageId(m.getId());
                pm.setProcessedAt(clock.instant());
                processed.saveAndFlush(pm);
                boolean won = notifications.transition(m.getId(), MessageStatus.QUEUED, MessageStatus.PROCESSING,
                        "claimed by dispatch consumer", ACTOR);
                if (!won) {
                    status.setRollbackOnly();
                    return Claim.LOST;
                }
                messages.markClaimed(m.getId(), countAttempt ? env.attemptNo() : m.getAttemptCount(),
                        clock.instant().plus(props.dispatch().lockTtl()), channel);
                if (providerId != null) {
                    MessageAttempt a = new MessageAttempt();
                    a.setId(Ids.newId());
                    a.setMessageId(m.getId());
                    a.setAttemptNo(env.attemptNo());
                    a.setChannel(channel);
                    a.setProviderId(providerId);
                    a.setState(MessageAttempt.STARTED);
                    a.setStartedAt(clock.instant());
                    attempts.saveAndFlush(a);
                }
                return Claim.WON;
            });
            return result == null ? Claim.LOST : result;
        } catch (DataIntegrityViolationException e) {
            return Claim.DUPLICATE; // a concurrent consumer inserted the same dispatchId first
        }
    }

    /** Expiry / dispatch-time rejection: claim, then PROCESSING -> {@code terminal}. No provider call, no attempt row. */
    private BusDecision terminalWithoutAttempt(DispatchEnvelope env, MessageRequest m, Channel channel,
            MessageStatus terminal, String reason) {
        if (claim(env, m, channel, null, false) != Claim.WON) {
            return BusDecision.ack();
        }
        notifications.transition(m.getId(), MessageStatus.PROCESSING, terminal, reason, ACTOR);
        log.info("Dispatch {} ended as {} ({})", env.dispatchId(), terminal, reason);
        return BusDecision.ack();
    }

    // ================================================================== render, call, map outcome

    private BusDecision dispatch(DispatchEnvelope env, MessageRequest m, Channel channel, Recipient recipient,
            ProviderRegistry.Selected selected, ResilientProviderInvoker.Permit permit) {
        ChannelProvider provider = selected.provider();
        int attemptNo = env.attemptNo();
        RenderedContent content;
        try {
            content = render(m, channel);
        } catch (TemplateRenderException e) {
            invoker.release(permit);
            return handlePermanent(m, env, channel, recipient, provider.providerId(),
                    ProviderResult.failure(ProviderOutcome.PERMANENT_FAILURE, "TEMPLATE_RENDER_ERROR",
                            e.getCode().name(), 0));
        } catch (RuntimeException e) {
            invoker.release(permit);
            log.warn("Template resolution failed for dispatch {}: {}", env.dispatchId(), e.getClass().getSimpleName());
            return handlePermanent(m, env, channel, recipient, provider.providerId(),
                    ProviderResult.failure(ProviderOutcome.PERMANENT_FAILURE, "TEMPLATE_UNAVAILABLE",
                            e.getClass().getSimpleName(), 0));
        }
        String idempotencyRef = m.getId() + ":" + (m.getFallbackIndex() + 1);
        ProviderRequest request = new ProviderRequest(m.getId(), idempotencyRef, m.getTenantId(), channel,
                recipient.addressFor(channel), content.subject(), content.body(), content.html(), options(m, channel),
                m.getCorrelationId(), Duration.ofMillis(Math.max(100, selected.config().getTimeoutMs())));

        try {
            hooks.orderedStream().forEach(h -> h.beforeProviderCall(env));
        } catch (RuntimeException e) {
            invoker.release(permit);
            throw e;
        }
        ProviderResult result = invoker.invoke(provider, request, permit);
        hooks.orderedStream().forEach(h -> h.afterProviderCall(env, result));
        diagnostics.archiveProviderCall(m.getId(), provider.providerId(), attemptNo, result);

        ProviderOutcome outcome = result.outcome();
        boolean pushRetry = outcome.isUncertain() && channel == Channel.PUSH; // collapse key makes a resend idempotent
        if (outcome == ProviderOutcome.ACCEPTED) {
            return handleAccepted(m, env, provider.providerId(), result);
        } else if (outcome.isRetryable() || pushRetry) {
            return handleRetryable(m, env, channel, recipient, provider.providerId(), result);
        } else if (outcome == ProviderOutcome.PERMANENT_FAILURE) {
            return handlePermanent(m, env, channel, recipient, provider.providerId(), result);
        }
        return handleUncertain(m, env, provider.providerId(), result);
    }

    private RenderedContent render(MessageRequest m, Channel channel) {
        boolean primary = channel == m.getChannel();
        TemplateService.ResolvedTemplate t = primary
                ? templates.resolvePinned(m.getTenantId(), m.getTemplateCode(), channel, m.getTemplateVersionNo(),
                        m.getTemplateLocale())
                : templates.resolvePublished(m.getTenantId(), m.getTemplateCode(), channel, m.getTemplateLocale(), null);
        if (t == null) {
            throw new IllegalStateException("template not available for channel " + channel);
        }
        Map<String, String> variables;
        try {
            variables = json.readValue(m.getPayloadJson(), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("stored variables are unreadable");
        }
        return renderer.render(channel, t.version().getSubject(), t.version().getBody(), t.declaredVariables(),
                variables);
    }

    private Map<String, String> options(MessageRequest m, Channel channel) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m.getOptionsJson() == null) {
            return out;
        }
        try {
            JsonNode root = json.readTree(m.getOptionsJson());
            JsonNode n = root.get(channel.name().toLowerCase());
            if (n == null || !n.isObject()) {
                return out;
            }
            n.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    out.put(e.getKey(), e.getValue().asText());
                } else if (e.getValue().isObject()) {
                    e.getValue().fields().forEachRemaining(h -> out.put(e.getKey() + "." + h.getKey(), h.getValue().asText()));
                }
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
            log.debug("Ignoring unreadable channel options for dispatch");
        }
        return out;
    }

    // ------------------------------------------------------------------ outcome handlers

    private BusDecision handleAccepted(MessageRequest m, DispatchEnvelope env, String providerId, ProviderResult r) {
        tx.executeWithoutResult(s -> {
            finishAttempt(m.getId(), env.attemptNo(), r);
            // false when a receipt overtook us (DELIVERED) or the sweeper already moved on: both are fine
            notifications.transition(m.getId(), MessageStatus.PROCESSING, MessageStatus.SENT_TO_PROVIDER,
                    "accepted by " + providerId, ACTOR);
        });
        metrics.dispatchDelay(Duration.between(m.getCreatedAt(), clock.instant()));
        return BusDecision.ack();
    }

    private BusDecision handleRetryable(MessageRequest m, DispatchEnvelope env, Channel channel, Recipient recipient,
            String providerId, ProviderResult r) {
        int attemptNo = env.attemptNo();
        String reason = reason(r);
        if (retry.attemptsRemaining(attemptNo, m.getMaxAttempts())) {
            Duration delay = retry.backoff(attemptNo, ThreadLocalRandom.current());
            Instant next = clock.instant().plus(delay);
            tx.executeWithoutResult(s -> {
                finishAttempt(m.getId(), attemptNo, r);
                notifications.transition(m.getId(), MessageStatus.PROCESSING, MessageStatus.RETRY_PENDING, reason,
                        ACTOR);
                messages.setNextAttemptAt(m.getId(), next);
            });
            metrics.retryScheduled(channel);
            try {
                bus.publishDelayed(DispatchEnvelope.of(m.getId(), m.getTenantId(), channel, m.getPriority(),
                        attemptNo + 1, m.getCorrelationId(), clock.instant()), delay);
            } catch (RuntimeException e) {
                log.warn("Delayed redelivery not published ({}); the scheduled poller will pick the retry up",
                        e.getClass().getSimpleName());
            }
            return BusDecision.ack();
        }
        // attempts exhausted: fallback channel (policy checked, never bypassing consent) or dead letter
        boolean[] fellBack = {false};
        tx.executeWithoutResult(s -> {
            finishAttempt(m.getId(), attemptNo, r);
            notifications.transition(m.getId(), MessageStatus.PROCESSING, MessageStatus.RETRY_PENDING,
                    "RETRIES_EXHAUSTED after " + attemptNo + " attempts: " + reason, ACTOR);
            Optional<Channel> fb = chooseFallback(m, recipient);
            if (fb.isPresent()) {
                fallbackTo(m, env, channel, fb.get(), MessageStatus.RETRY_PENDING, "retries exhausted");
                fellBack[0] = true;
            } else {
                notifications.deadLetter(m.getId(), MessageStatus.RETRY_PENDING, DlqReason.RETRIES_EXHAUSTED,
                        attemptNo + " attempts, last " + reason, ACTOR);
            }
        });
        return fellBack[0] ? BusDecision.ack() : BusDecision.deadLetter(DlqReason.RETRIES_EXHAUSTED);
    }

    private BusDecision handlePermanent(MessageRequest m, DispatchEnvelope env, Channel channel, Recipient recipient,
            String providerId, ProviderResult r) {
        tx.executeWithoutResult(s -> {
            finishAttempt(m.getId(), env.attemptNo(), r);
            notifications.transition(m.getId(), MessageStatus.PROCESSING, MessageStatus.FAILED, reason(r), ACTOR);
            if (r.errorCode() != null && HARD_BOUNCE_CODES.contains(r.errorCode()) && recipient != null) {
                suppressions.suppress(m.getTenantId(), channel, recipient.addressFor(channel), "HARD_BOUNCE", ACTOR,
                        m.getId());
            }
            Optional<Channel> fb = chooseFallback(m, recipient);
            fb.ifPresent(c -> fallbackTo(m, env, channel, c, MessageStatus.FAILED, "permanent failure"));
        });
        return BusDecision.ack();
    }

    private BusDecision handleUncertain(MessageRequest m, DispatchEnvelope env, String providerId, ProviderResult r) {
        // never a blind resend for EMAIL/SMS (ADR-0010): wait for a callback, then reconciliation asks the provider
        tx.executeWithoutResult(s -> {
            finishAttempt(m.getId(), env.attemptNo(), r);
            notifications.transition(m.getId(), MessageStatus.PROCESSING, MessageStatus.DELIVERY_UNKNOWN, reason(r),
                    ACTOR);
        });
        return BusDecision.ack();
    }

    // ------------------------------------------------------------------ fallback

    private Optional<Channel> chooseFallback(MessageRequest m, Recipient recipient) {
        List<Channel> all = m.fallbackList();
        if (recipient == null || m.getFallbackIndex() >= all.size()) {
            return Optional.empty();
        }
        List<Channel> remaining = all.subList(m.getFallbackIndex(), all.size());
        for (PolicyService.FallbackDecision fd : policy.evaluateFallbacks(recipient, remaining, m.getCategory(),
                m.getPriority(), clock.instant())) {
            if (fd.decision().isAllowed()) { // a deferred or rejected fallback is skipped: consent is never bypassed
                return Optional.of(fd.channel());
            }
        }
        return Optional.empty();
    }

    /** Re-queues the message on a fallback channel with a fresh attempt budget (new attempt on a new channel). */
    private void fallbackTo(MessageRequest m, DispatchEnvelope env, Channel from, Channel to, MessageStatus fromStatus,
            String why) {
        int attemptNo = env.attemptNo();
        notifications.transition(m.getId(), fromStatus, MessageStatus.QUEUED, "fallback " + from + " -> " + to + ": " + why,
                ACTOR);
        int index = m.fallbackList().indexOf(to) + 1;
        messages.applyFallback(m.getId(), index, to, attemptNo + props.dispatch().maxAttempts());
        Instant now = clock.instant();
        outbox.enqueue(DispatchEnvelope.of(m.getId(), m.getTenantId(), to, m.getPriority(), attemptNo + 1,
                m.getCorrelationId(), now), now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from.name());
        detail.put("to", to.name());
        detail.put("reason", why);
        audit.recordAs(ACTOR, "SYSTEM", m.getTenantId(), AuditAction.MESSAGE_FALLBACK,
                NotificationService.ENTITY_MESSAGE, m.getId(), detail);
    }

    // ------------------------------------------------------------------ helpers

    private void finishAttempt(String messageId, int attemptNo, ProviderResult r) {
        attempts.findByMessageIdAndAttemptNo(messageId, attemptNo).ifPresent(a -> {
            a.setState(MessageAttempt.FINISHED);
            a.setOutcome(r.outcome());
            a.setProviderMessageId(r.providerMessageId());
            a.setErrorCode(r.errorCode());
            a.setLatencyMs(r.latencyMs());
            a.setFinishedAt(clock.instant());
            attempts.save(a);
        });
    }

    private static String reason(ProviderResult r) {
        return r.outcome() + (r.errorCode() == null ? "" : ": " + r.errorCode());
    }

    private static Duration clamp(Duration d, Duration min, Duration max) {
        return d.compareTo(min) < 0 ? min : (d.compareTo(max) > 0 ? max : d);
    }

    // ================================================================== poison

    /**
     * Mirrors a bus-level poison dead-letter in the database: a message that is still QUEUED or RETRY_PENDING is
     * moved to DEAD_LETTERED(POISON_MESSAGE) through the legal transition chain. Best effort, never throws.
     */
    @Override
    public void onPoison(DispatchEnvelope env, int deliveryCount, Throwable cause) {
        try {
            MessageRequest m = messages.findById(env.messageId()).orElse(null);
            if (m == null) {
                return;
            }
            tx.executeWithoutResult(s -> {
                MessageStatus from = m.getStatus();
                if (from == MessageStatus.QUEUED) {
                    notifications.transition(m.getId(), MessageStatus.QUEUED, MessageStatus.PROCESSING,
                            "poison envelope", ACTOR);
                    notifications.transition(m.getId(), MessageStatus.PROCESSING, MessageStatus.FAILED,
                            "POISON_MESSAGE after " + deliveryCount + " deliveries", ACTOR);
                    notifications.deadLetter(m.getId(), MessageStatus.FAILED, DlqReason.POISON_MESSAGE,
                            deliveryCount + " deliveries", ACTOR);
                } else if (from == MessageStatus.RETRY_PENDING) {
                    notifications.deadLetter(m.getId(), MessageStatus.RETRY_PENDING, DlqReason.POISON_MESSAGE,
                            deliveryCount + " deliveries", ACTOR);
                }
            });
        } catch (RuntimeException e) {
            log.error("Could not mirror poison dead-letter for dispatch {}: {}", env.dispatchId(),
                    e.getClass().getSimpleName());
        }
    }
}
