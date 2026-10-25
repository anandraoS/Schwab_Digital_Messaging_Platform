package com.example.messaging.service;

import com.example.messaging.common.ApiError;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.common.Ids;
import com.example.messaging.common.Redaction;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.IllegalStateTransitionException;
import com.example.messaging.domain.MessageAttempt;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStateMachine;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.OutboxEvent;
import com.example.messaging.domain.Priority;
import com.example.messaging.domain.Recipient;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.messaging.DispatchEnvelope;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.repository.DeliveryEventRepository;
import com.example.messaging.repository.MessageAttemptRepository;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.MessageStatusView;
import com.example.messaging.repository.OutboxEventRepository;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.repository.AuditEventRepository;
import com.example.messaging.repository.Specs;
import com.example.messaging.security.AuthorizationService;
import com.example.messaging.security.TenantContext;
import com.example.messaging.template.TemplateRenderer;
import com.example.messaging.template.VariableValidator;
import com.example.messaging.web.dto.MessageDtos.AttemptView;
import com.example.messaging.web.dto.MessageDtos.DeadLetterItem;
import com.example.messaging.web.dto.MessageDtos.DeliveryEventView;
import com.example.messaging.web.dto.MessageDtos.MessageAcceptedResponse;
import com.example.messaging.web.dto.MessageDtos.MessageDetail;
import com.example.messaging.web.dto.MessageDtos.MessageStatusResponse;
import com.example.messaging.web.dto.MessageDtos.MessageSummary;
import com.example.messaging.web.dto.MessageDtos.RecipientView;
import com.example.messaging.web.dto.MessageDtos.SubmitMessageRequest;
import com.example.messaging.web.dto.MessageDtos.TemplateView;
import com.example.messaging.web.dto.MessageDtos.TimelineEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Submission, query, cancel/requeue and lifecycle-transition entry point.
 *
 * <p>Stage-2 contract: use {@link #transition} for every status change (it enforces the state machine, is a
 * compare-and-set so losing a race returns {@code false}, writes audit and metrics), and
 * {@link #submitInternal} for bulk submissions. The next attempt number of a message is {@code attemptCount + 1}.
 */
@Service
public class NotificationService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:~-]{1,128}");
    private static final Pattern HEADER_NAME = Pattern.compile("X-[A-Za-z0-9-]{1,50}");
    private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(366);
    public static final String ENTITY_MESSAGE = "MESSAGE";

    /** @param replay true when an earlier identical request is being replayed (HTTP 200 + Idempotent-Replay). */
    public record SubmitResult(MessageAcceptedResponse response, boolean replay) {}

    private final MessageRequestRepository messages;
    private final MessageAttemptRepository attempts;
    private final DeliveryEventRepository events;
    private final AuditEventRepository auditEvents;
    private final RecipientRepository recipients;
    private final OutboxEventRepository outbox;
    private final TemplateService templateService;
    private final PolicyService policy;
    private final IdempotencyService idempotency;
    private final TemplateRenderer renderer;
    private final VariableValidator variableValidator;
    private final AuditService audit;
    private final AuthorizationService authz;
    private final TenantContext tenantContext;
    private final MessagingMetrics metrics;
    private final MessagingProperties props;
    private final ObjectMapper mapper;
    private final ObjectMapper canonicalMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public NotificationService(MessageRequestRepository messages, MessageAttemptRepository attempts,
            DeliveryEventRepository events, AuditEventRepository auditEvents, RecipientRepository recipients,
            OutboxEventRepository outbox, TemplateService templateService, PolicyService policy,
            IdempotencyService idempotency, TemplateRenderer renderer, VariableValidator variableValidator,
            AuditService audit, AuthorizationService authz, TenantContext tenantContext, MessagingMetrics metrics,
            MessagingProperties props, ObjectMapper mapper, Clock clock, PlatformTransactionManager tm) {
        this.messages = messages;
        this.attempts = attempts;
        this.events = events;
        this.auditEvents = auditEvents;
        this.recipients = recipients;
        this.outbox = outbox;
        this.templateService = templateService;
        this.policy = policy;
        this.idempotency = idempotency;
        this.renderer = renderer;
        this.variableValidator = variableValidator;
        this.audit = audit;
        this.authz = authz;
        this.tenantContext = tenantContext;
        this.metrics = metrics;
        this.props = props;
        this.mapper = mapper;
        this.canonicalMapper = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    // ================================================================== submission

    /** API submission: tenant comes from the authenticated principal. */
    public SubmitResult submit(SubmitMessageRequest req, String idempotencyKey) {
        String tenantId = tenantContext.requireTenant();
        String clientApp = tenantContext.requirePrincipal().subject();
        return submitInternal(tenantId, req, idempotencyKey, null, clientApp);
    }

    /**
     * Submission with an explicit tenant (bulk jobs, tests). {@code bulkJobId} may be null. Same validation,
     * policy, idempotency and atomic outbox/audit write as the API path.
     */
    public SubmitResult submitInternal(String tenantId, SubmitMessageRequest req, String idempotencyKey,
            String bulkJobId, String clientApp) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException.ValidationException("Idempotency-Key",
                    "Idempotency-Key is required (1-128 chars of A-Z a-z 0-9 . _ : ~ -)");
        }
        String hash = requestHash(req);
        IdempotencyService.Outcome outcome = idempotency.begin(tenantId, idempotencyKey, hash);
        if (outcome.isReplay()) {
            return new SubmitResult(readResponse(outcome.replay().body()), true);
        }
        try {
            MessageAcceptedResponse response = tx.execute(status ->
                    persistSubmission(tenantId, req, idempotencyKey, hash, bulkJobId, clientApp, outcome.claimId()));
            return new SubmitResult(response, false);
        } catch (RuntimeException e) {
            idempotency.release(outcome.claimId());
            throw e;
        }
    }

    private MessageAcceptedResponse persistSubmission(String tenantId, SubmitMessageRequest req, String key, String hash,
            String bulkJobId, String clientApp, String claimId) {
        Instant now = clock.instant();
        Recipient recipient = resolveRecipient(tenantId, req);
        String locale = req.template().locale() != null && !req.template().locale().isBlank()
                ? req.template().locale() : recipient.getLocale();

        TemplateService.ResolvedTemplate resolved = resolveTemplate(tenantId, req.template().code(), req.channel(),
                locale, req.template().version(), "template");
        if (resolved.template().getCategory() != req.category()) {
            throw new ApiException.ValidationException("category",
                    "category must match the template category " + resolved.template().getCategory());
        }
        variableValidator.validateSupplied(resolved.declaredVariables(), req.variables());
        renderer.render(req.channel(), resolved.version().getSubject(), resolved.version().getBody(),
                resolved.declaredVariables(), req.variables()); // render-check only, output is discarded

        List<Channel> fallbacks = validateFallbacks(tenantId, req, recipient, locale);
        validateOptions(req);
        validateMetadata(req.metadata());

        Instant sendAt = req.schedule() != null && req.schedule().sendAt() != null
                && req.schedule().sendAt().isAfter(now) ? req.schedule().sendAt() : now;
        if (sendAt.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
            throw new ApiException.ValidationException("schedule.sendAt", "sendAt may be at most 366 days ahead");
        }
        Instant expiresAt = req.schedule() == null ? null : req.schedule().expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(sendAt)) {
            throw new ApiException.ValidationException("schedule.expiresAt", "expiresAt must be after sendAt");
        }

        // policy is evaluated at the time the message would actually be sent
        PolicyDecision decision = policy.evaluate(recipient, req.channel(), req.category(), req.priority(), sendAt);
        MessageStatus status = MessageStatus.VALIDATED;
        String reason = null;
        Instant scheduledAt = sendAt.isAfter(now) ? sendAt : null;
        if (decision.isRejected()) {
            status = MessageStatus.REJECTED;
            reason = decision.reason().name();
        } else if (decision.isDeferred()) {
            scheduledAt = decision.until();
            reason = "DEFERRED_QUIET_HOURS";
            if (expiresAt != null && !expiresAt.isAfter(scheduledAt)) {
                status = MessageStatus.REJECTED;
                reason = "EXPIRES_BEFORE_DEFERRED_SEND";
            }
        }
        MessageStateMachine.assertTransition(MessageStatus.ACCEPTED, status);

        String correlationId = MDC.get("correlationId") != null ? MDC.get("correlationId") : Ids.newId();
        MessageRequest m = new MessageRequest();
        m.setId(Ids.newId());
        m.setTenantId(tenantId);
        m.setClientApp(clientApp);
        m.setClientReference(req.clientReference());
        m.setRecipientId(recipient.getId());
        m.setChannel(req.channel());
        m.setCategory(req.category());
        m.setPriority(req.priority());
        m.setStatus(status);
        m.setStatusReason(reason);
        m.setTemplateCode(req.template().code());
        m.setTemplateVersionNo(resolved.version().getVersionNo());
        m.setTemplateLocale(resolved.version().getLocale());
        m.setPayloadJson(write(req.variables()));
        m.setOptionsJson(req.channelOptions() == null ? null : write(req.channelOptions()));
        m.setMetadataJson(req.metadata().isEmpty() ? null : write(req.metadata()));
        m.setFallbackChannels(fallbacks.isEmpty() ? null : String.join(",", fallbacks.stream().map(Enum::name).toList()));
        m.setCorrelationId(correlationId);
        m.setIdempotencyKey(key);
        m.setRequestHash(hash);
        m.setScheduledAt(scheduledAt);
        m.setExpiresAt(expiresAt);
        m.setMaxAttempts(props.dispatch().maxAttempts());
        m.setBulkJobId(bulkJobId);
        m.setStatusChangedAt(now);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        if (m.getPayloadJson().length() > 8000) {
            throw new ApiException.ValidationException("variables", "serialised variables exceed 8000 characters");
        }
        messages.save(m);

        if (status == MessageStatus.VALIDATED) {
            DispatchEnvelope env = DispatchEnvelope.of(m.getId(), tenantId, m.getChannel(), m.getPriority(), 1,
                    correlationId, now);
            writeOutbox(m.getId(), env, scheduledAt != null ? scheduledAt : now, now);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("channel", m.getChannel().name());
        detail.put("category", m.getCategory().name());
        detail.put("priority", m.getPriority().name());
        detail.put("templateCode", m.getTemplateCode());
        detail.put("templateVersion", m.getTemplateVersionNo());
        detail.put("recipientId", m.getRecipientId());
        detail.put("status", status.name());
        if (reason != null) {
            detail.put("reason", reason);
        }
        if (scheduledAt != null) {
            detail.put("scheduledAt", scheduledAt.toString());
        }
        audit.record(tenantId, status == MessageStatus.REJECTED ? AuditAction.MESSAGE_REJECTED
                : AuditAction.MESSAGE_ACCEPTED, ENTITY_MESSAGE, m.getId(), detail);

        MessageAcceptedResponse response = new MessageAcceptedResponse(m.getId(), status, m.getChannel(), correlationId,
                scheduledAt, reason, links(m.getId()));
        idempotency.complete(tenantId, key, claimId, hash, 202, write(response), m.getId());
        afterCommit(() -> {
            metrics.accepted(m.getChannel(), m.getPriority(), tenantId);
            metrics.transition(MessageStatus.ACCEPTED, MessageStatus.VALIDATED.equals(m.getStatus())
                    ? MessageStatus.VALIDATED : MessageStatus.REJECTED);
        });
        return response;
    }

    private Recipient resolveRecipient(String tenantId, SubmitMessageRequest req) {
        String ext = req.recipient() == null ? null : req.recipient().externalRef();
        boolean hasId = req.recipientId() != null && !req.recipientId().isBlank();
        boolean hasExt = ext != null && !ext.isBlank();
        if (hasId == hasExt) {
            throw new ApiException.ValidationException("recipientId",
                    "Exactly one of recipientId or recipient.externalRef is required");
        }
        Recipient r = hasId
                ? recipients.findById(req.recipientId()).filter(x -> x.getTenantId().equals(tenantId)).orElse(null)
                : recipients.findByTenantIdAndExternalRef(tenantId, ext).orElse(null);
        if (r == null) {
            throw new ApiException.UnprocessableException(ErrorCode.RECIPIENT_NOT_FOUND, "Recipient not found");
        }
        return r;
    }

    private TemplateService.ResolvedTemplate resolveTemplate(String tenantId, String code, Channel channel, String locale,
            Integer version, String field) {
        TemplateService.ResolvedTemplate t = templateService.resolvePublished(tenantId, code, channel, locale, version);
        if (t == null) {
            throw new ApiException.UnprocessableException(ErrorCode.TEMPLATE_NOT_FOUND,
                    "No PUBLISHED template '" + code + "' for channel " + channel
                            + (version != null ? " version " + version : "") + " and locale " + locale,
                    List.of(new ApiError.Detail(field, "no published template")));
        }
        return t;
    }

    private List<Channel> validateFallbacks(String tenantId, SubmitMessageRequest req, Recipient recipient, String locale) {
        List<Channel> out = new ArrayList<>(new LinkedHashSet<>(req.fallbackChannels()));
        if (out.size() != req.fallbackChannels().size()) {
            throw new ApiException.ValidationException("fallbackChannels", "fallbackChannels must not contain duplicates");
        }
        for (Channel c : out) {
            if (c == req.channel()) {
                throw new ApiException.ValidationException("fallbackChannels",
                        "a fallback channel must differ from the primary channel");
            }
            // a fallback needs a published template of the same code on that channel; render-check it too
            TemplateService.ResolvedTemplate ft = resolveTemplate(tenantId, req.template().code(), c, locale, null,
                    "fallbackChannels");
            if (ft.template().getCategory() != req.category()) {
                throw new ApiException.ValidationException("fallbackChannels",
                        "fallback template category must match the primary category");
            }
            variableValidator.validateSupplied(ft.declaredVariables(), req.variables());
            renderer.render(c, ft.version().getSubject(), ft.version().getBody(), ft.declaredVariables(), req.variables());
        }
        return out;
    }

    private void validateOptions(SubmitMessageRequest req) {
        var o = req.channelOptions();
        if (o == null || o.email() == null || o.email().headers() == null) {
            return;
        }
        o.email().headers().forEach((k, v) -> {
            if (k == null || !HEADER_NAME.matcher(k).matches()) {
                throw new ApiException.ValidationException("channelOptions.email.headers",
                        "custom header names must match X-[A-Za-z0-9-]+");
            }
            if (v == null || v.length() > 500 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
                throw new ApiException.ValidationException("channelOptions.email.headers",
                        "header values must be single-line and at most 500 characters");
            }
        });
    }

    private void validateMetadata(Map<String, String> metadata) {
        metadata.forEach((k, v) -> {
            if (k == null || k.length() > 100 || (v != null && v.length() > 500)) {
                throw new ApiException.ValidationException("metadata", "metadata keys max 100 and values max 500 characters");
            }
        });
    }

    private void writeOutbox(String messageId, DispatchEnvelope env, Instant availableAt, Instant now) {
        OutboxEvent e = new OutboxEvent();
        e.setId(Ids.newId());
        e.setAggregateId(messageId);
        e.setTopic(OutboxEvent.TOPIC_DISPATCH);
        e.setPayload(write(env));
        e.setStatus(OutboxEvent.NEW);
        e.setAvailableAt(availableAt);
        e.setCreatedAt(now);
        outbox.save(e);
    }

    private String requestHash(SubmitMessageRequest req) {
        try {
            return Ids.sha256Hex(canonicalMapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash request", e);
        }
    }

    private MessageAcceptedResponse readResponse(String json) {
        try {
            return mapper.readValue(json, MessageAcceptedResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Serialisation failed", e);
        }
    }

    private static Map<String, String> links(String id) {
        Map<String, String> l = new LinkedHashMap<>();
        l.put("self", "/api/v1/messages/" + id);
        l.put("status", "/api/v1/messages/" + id + "/status");
        l.put("history", "/api/v1/messages/" + id + "/history");
        l.put("cancel", "/api/v1/messages/" + id + "/cancel");
        return l;
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public MessageDetail get(String id) {
        MessageRequest m = requireMessage(id);
        Recipient r = recipients.findById(m.getRecipientId()).orElse(null);
        List<AttemptView> av = attempts.findByMessageIdOrderByAttemptNoAsc(id).stream()
                .map(a -> new AttemptView(a.getAttemptNo(), a.getChannel(), a.getProviderId(), a.getOutcome(),
                        a.getProviderMessageId(), a.getErrorCode(), a.getLatencyMs(), a.getStartedAt())).toList();
        List<DeliveryEventView> ev = events.findByMessageIdOrderByOccurredAtAsc(id).stream().map(this::toView).toList();
        RecipientView rv = r == null ? new RecipientView(m.getRecipientId(), null, null)
                : new RecipientView(r.getId(), r.getExternalRef(), maskedAddress(r, m.getChannel()));
        return new MessageDetail(m.getId(), m.getTenantId(), m.getClientReference(), rv, m.getChannel(),
                m.getChannelUsed(), m.getCategory(), m.getPriority(), m.getStatus(), m.getStatusReason(),
                m.getStatus().isTerminal(),
                new TemplateView(m.getTemplateCode(), m.getTemplateVersionNo(), m.getTemplateLocale()),
                m.getCorrelationId(), m.getIdempotencyKey(), m.getScheduledAt(), m.getExpiresAt(), m.getAttemptCount(),
                m.getMaxAttempts(), m.getNextAttemptAt(), m.getCreatedAt(), m.getUpdatedAt(), av, ev);
    }

    private DeliveryEventView toView(com.example.messaging.domain.DeliveryEvent e) {
        return new DeliveryEventView(e.getId(), e.getMessageId(), e.getProviderId(), e.getProviderEventId(), e.getType(),
                e.getOccurredAt(), e.getReceivedAt(), e.isApplied(), e.getIgnoredReason());
    }

    private static String maskedAddress(Recipient r, Channel channel) {
        return switch (channel) {
            case EMAIL -> Redaction.maskEmail(r.getEmail());
            case SMS -> Redaction.maskPhone(r.getPhoneE164());
            case PUSH -> Redaction.maskToken(r.getPushToken());
        };
    }

    @Transactional(readOnly = true)
    public MessageStatusResponse status(String id) {
        MessageStatusView v = messages.findStatusViewById(id).orElseThrow(() -> new ApiException.NotFoundException("Message"));
        authz.assertTenantAccess(v.getTenantId(), "Message");
        return toStatus(v);
    }

    private static MessageStatusResponse toStatus(MessageStatusView v) {
        return new MessageStatusResponse(v.getId(), v.getStatus(), v.getStatusReason(), v.getChannelUsed(),
                v.getAttemptCount(), v.getUpdatedAt(), v.getStatus().isTerminal());
    }

    /** Filter set for {@link #list}. Null members are ignored. */
    public record MessageFilter(MessageStatus status, Channel channel, Category category, Priority priority,
            String recipientId, String correlationId, Instant from, Instant to, String q) {}

    @Transactional(readOnly = true)
    public Page<MessageSummary> list(MessageFilter f, Pageable pageable) {
        return messages.findAll(spec(f, null), pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Page<MessageSummary> listByStatuses(List<MessageStatus> statuses, MessageFilter f, Pageable pageable) {
        return messages.findAll(spec(f, statuses), pageable).map(this::toSummary);
    }

    private Specification<MessageRequest> spec(MessageFilter f, List<MessageStatus> statuses) {
        return Specification.<MessageRequest>where(Specs.eq("tenantId", tenantContext.scopeTenant()))
                .and(Specs.eq("status", f.status()))
                .and(Specs.in("status", statuses))
                .and(Specs.eq("channel", f.channel()))
                .and(Specs.eq("category", f.category()))
                .and(Specs.eq("priority", f.priority()))
                .and(Specs.eq("recipientId", f.recipientId()))
                .and(Specs.eq("correlationId", f.correlationId()))
                .and(Specs.from("createdAt", f.from()))
                .and(Specs.to("createdAt", f.to()))
                .and(Specs.containsAny(f.q(), "clientReference"));
    }

    private MessageSummary toSummary(MessageRequest m) {
        return new MessageSummary(m.getId(), m.getClientReference(), m.getRecipientId(), m.getChannel(), m.getCategory(),
                m.getPriority(), m.getStatus(), m.getStatusReason(), m.getCorrelationId(), m.getAttemptCount(),
                m.getScheduledAt(), m.getCreatedAt(), m.getUpdatedAt(), m.getChannelUsed(), m.getTemplateCode(),
                m.getNextAttemptAt());
    }

    @Transactional(readOnly = true)
    public Page<DeadLetterItem> listDeadLetters(Pageable pageable) {
        Specification<MessageRequest> s = Specification.<MessageRequest>where(Specs.eq("tenantId", tenantContext.scopeTenant()))
                .and(Specs.eq("status", MessageStatus.DEAD_LETTERED));
        return messages.findAll(s, pageable).map(m -> new DeadLetterItem(m.getId(), m.getTenantId(), m.getChannel(),
                m.getCategory(), m.getPriority(), m.getDlqReason(), m.getStatusReason(), m.getAttemptCount(),
                m.isDiscarded(), m.getStatusChangedAt()));
    }

    /** Merged, time-ordered lifecycle: audit events + attempts + delivery events. */
    @Transactional(readOnly = true)
    public List<TimelineEntry> history(String id) {
        requireMessage(id);
        List<TimelineEntry> out = new ArrayList<>();
        auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(ENTITY_MESSAGE, id).forEach(a -> {
            Map<String, Object> d = new LinkedHashMap<>(audit.parseDetail(a.getDetailJson()));
            d.put("actor", a.getActor());
            out.add(new TimelineEntry(a.getOccurredAt(), "AUDIT", a.getAction(), a.getAction(), d));
        });
        for (MessageAttempt a : attempts.findByMessageIdOrderByAttemptNoAsc(id)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("attemptNo", a.getAttemptNo());
            d.put("channel", a.getChannel().name());
            d.put("providerId", a.getProviderId());
            d.put("outcome", a.getOutcome() == null ? null : a.getOutcome().name());
            d.put("errorCode", a.getErrorCode());
            d.put("latencyMs", a.getLatencyMs());
            out.add(new TimelineEntry(a.getStartedAt(), "ATTEMPT", "ATTEMPT_" + a.getState(),
                    "Attempt " + a.getAttemptNo() + " via " + a.getProviderId(), d));
        }
        for (var e : events.findByMessageIdOrderByOccurredAtAsc(id)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("providerId", e.getProviderId());
            d.put("applied", e.isApplied());
            d.put("ignoredReason", e.getIgnoredReason());
            out.add(new TimelineEntry(e.getOccurredAt(), "DELIVERY_EVENT", e.getType().name(),
                    "Provider receipt " + e.getType(), d));
        }
        out.sort(Comparator.comparing(TimelineEntry::at));
        return out;
    }

    private MessageRequest requireMessage(String id) {
        MessageRequest m = messages.findById(id).orElseThrow(() -> new ApiException.NotFoundException("Message"));
        authz.assertTenantAccess(m.getTenantId(), "Message");
        return m;
    }

    // ================================================================== commands

    /** Cancels a message that has not been handed to a provider (VALIDATED, QUEUED, RETRY_PENDING). */
    @Transactional
    public MessageStatusResponse cancel(String id) {
        for (int i = 0; i < 3; i++) {
            MessageStatusView v = messages.findStatusViewById(id).orElseThrow(() -> new ApiException.NotFoundException("Message"));
            authz.assertTenantAccess(v.getTenantId(), "Message");
            if (!MessageStateMachine.canCancel(v.getStatus())) {
                throw new IllegalStateTransitionException(v.getStatus(), "cancel");
            }
            if (doTransition(v, MessageStatus.CANCELLED, "cancelled by " + authz.actor(), authz.actor(),
                    AuditAction.MESSAGE_CANCELLED, false)) {
                return status(id);
            }
            // lost a race with the pipeline (e.g. VALIDATED -> QUEUED): re-read and try again
        }
        MessageStatusView latest = messages.findStatusViewById(id).orElseThrow(() -> new ApiException.NotFoundException("Message"));
        throw new IllegalStateTransitionException(latest.getStatus(), "cancel");
    }

    /** Operator action: FAILED or DEAD_LETTERED goes back to QUEUED (published by the outbox publisher). */
    @Transactional
    public MessageStatusResponse requeue(String id) {
        return requeueInternal(id, false, AuditAction.MESSAGE_REQUEUED);
    }

    /** Operator replay of a DEAD_LETTERED message. */
    @Transactional
    public MessageStatusResponse replay(String id) {
        return requeueInternal(id, true, AuditAction.MESSAGE_REPLAYED);
    }

    private MessageStatusResponse requeueInternal(String id, boolean deadLetterOnly, String action) {
        MessageStatusView v = messages.findStatusViewById(id).orElseThrow(() -> new ApiException.NotFoundException("Message"));
        authz.assertTenantAccess(v.getTenantId(), "Message");
        boolean allowed = deadLetterOnly ? v.getStatus() == MessageStatus.DEAD_LETTERED
                : MessageStateMachine.canRequeue(v.getStatus());
        if (!allowed) {
            throw new IllegalStateTransitionException(v.getStatus(), deadLetterOnly ? "replay" : "requeue");
        }
        if (!doTransition(v, MessageStatus.QUEUED, "operator " + (deadLetterOnly ? "replay" : "requeue") + " by "
                + authz.actor(), authz.actor(), action, false)) {
            MessageStatusView latest = messages.findStatusViewById(id).orElseThrow(() -> new ApiException.NotFoundException("Message"));
            throw new IllegalStateTransitionException(latest.getStatus(), "requeue");
        }
        MessageRequest m = messages.findById(id).orElseThrow();
        m.setDlqReason(null);
        m.setDiscarded(false);
        m.setNextAttemptAt(null);
        m.setLockedUntil(null);
        // an operator action grants a fresh attempt budget (otherwise an exhausted message would be exhausted again
        // by its very first failure)
        m.setMaxAttempts(m.getAttemptCount() + props.dispatch().maxAttempts());
        messages.save(m);
        Instant now = clock.instant();
        writeOutbox(id, DispatchEnvelope.of(id, m.getTenantId(), m.getChannel(), m.getPriority(),
                m.getAttemptCount() + 1, m.getCorrelationId(), now), now, now);
        return status(id);
    }

    /** Marks a dead letter as discarded (status stays DEAD_LETTERED; audited). */
    @Transactional
    public MessageStatusResponse discard(String id, String reason) {
        MessageRequest m = requireMessage(id);
        if (m.getStatus() != MessageStatus.DEAD_LETTERED) {
            throw new IllegalStateTransitionException(m.getStatus(), "discard");
        }
        m.setDiscarded(true);
        m.setUpdatedAt(clock.instant());
        messages.save(m);
        audit.record(m.getTenantId(), AuditAction.MESSAGE_DISCARDED, ENTITY_MESSAGE, id,
                reason == null ? Map.of() : Map.of("reason", reason));
        return status(id);
    }

    // ================================================================== lifecycle (used by stage 2)

    /**
     * Compare-and-set lifecycle transition. Throws {@link IllegalStateTransitionException} when {@code from -> to} is
     * not in the state table; returns {@code false} when the message was no longer in {@code from} (lost race:
     * callers ack and skip). Audited and counted ({@code messaging.transitions}).
     *
     * @param actor audit actor; {@code null} uses the authenticated principal or "system"
     */
    @Transactional
    public boolean transition(String messageId, MessageStatus from, MessageStatus to, String reason, String actor) {
        return transition(messageId, from, to, reason, actor, false);
    }

    /** As {@link #transition} but also allows receipt-driven PROCESSING -> DELIVERED/FAILED (out-of-order callbacks). */
    @Transactional
    public boolean transition(String messageId, MessageStatus from, MessageStatus to, String reason, String actor,
            boolean viaReceipt) {
        MessageStatusView v = messages.findStatusViewById(messageId)
                .orElseThrow(() -> new ApiException.NotFoundException("Message"));
        if (v.getStatus() != from) {
            if (!(viaReceipt ? MessageStateMachine.canTransitionViaReceipt(from, to) : MessageStateMachine.canTransition(from, to))) {
                throw new IllegalStateTransitionException(from, to);
            }
            return false;
        }
        return doTransition(v, to, reason, actor, AuditAction.MESSAGE_TRANSITION, viaReceipt);
    }

    /** Transition into DEAD_LETTERED recording the reason on the message. */
    @Transactional
    public boolean deadLetter(String messageId, MessageStatus from, DlqReason reason, String detail, String actor) {
        boolean ok = transition(messageId, from, MessageStatus.DEAD_LETTERED, reason.name()
                + (detail == null ? "" : ": " + detail), actor);
        if (ok) {
            MessageRequest m = messages.findById(messageId).orElseThrow();
            m.setDlqReason(reason);
            messages.save(m);
        }
        return ok;
    }

    private boolean doTransition(MessageStatusView v, MessageStatus to, String reason, String actor, String action,
            boolean viaReceipt) {
        MessageStatus from = v.getStatus();
        if (!(viaReceipt ? MessageStateMachine.canTransitionViaReceipt(from, to) : MessageStateMachine.canTransition(from, to))) {
            throw new IllegalStateTransitionException(from, to);
        }
        Instant now = clock.instant();
        String shortReason = reason == null ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason);
        if (messages.casStatus(v.getId(), from, to, shortReason, now) == 0) {
            return false;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from.name());
        detail.put("to", to.name());
        if (shortReason != null) {
            detail.put("reason", shortReason);
        }
        String who = actor != null ? actor : authz.actor();
        audit.recordAs(who, authz.actorRoles(), v.getTenantId(), action, ENTITY_MESSAGE, v.getId(), detail);
        afterCommit(() -> metrics.transition(from, to));
        return true;
    }

    private void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
