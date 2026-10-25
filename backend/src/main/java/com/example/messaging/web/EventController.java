package com.example.messaging.web;

import com.example.messaging.common.PageResponse;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.security.TenantContext;
import com.example.messaging.service.AuditService;
import com.example.messaging.service.DeliveryEventQueryService;
import com.example.messaging.web.dto.AuditDtos.AuditEventView;
import com.example.messaging.web.dto.MessageDtos.DeliveryEventView;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit trail and delivery event search. */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Audit and events")
public class EventController {

    private static final Set<String> SORT_AUDIT = Set.of("occurredAt", "action", "actor");
    private static final Set<String> SORT_EVENTS = Set.of("occurredAt", "receivedAt", "type");

    private final AuditService audit;
    private final DeliveryEventQueryService deliveryEvents;
    private final TenantContext tenantContext;

    public EventController(AuditService audit, DeliveryEventQueryService deliveryEvents, TenantContext tenantContext) {
        this.audit = audit;
        this.deliveryEvents = deliveryEvents;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/audit-events")
    @PreAuthorize("hasAnyRole('AUDITOR','TENANT_ADMIN','PLATFORM_ADMIN')")
    public PageResponse<AuditEventView> auditEvents(
            @RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String action, @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = Pagination.of(page, size, sort, SORT_AUDIT, "occurredAt", Sort.Direction.DESC);
        return PageResponse.of(audit.search(tenantContext.scopeTenant(), entityType, entityId, action, actor, from, to,
                pageable), a -> new AuditEventView(a.getId(), a.getTenantId(), a.getActor(), a.getActorRole(),
                        a.getAction(), a.getEntityType(), a.getEntityId(), a.getCorrelationId(),
                        audit.parseDetail(a.getDetailJson()), a.getOccurredAt()));
    }

    @GetMapping("/delivery-events")
    @PreAuthorize("hasAnyRole('OPERATOR','AUDITOR','TENANT_ADMIN','PLATFORM_ADMIN')")
    public PageResponse<DeliveryEventView> deliveryEvents(
            @RequestParam(required = false) String messageId, @RequestParam(required = false) String providerId,
            @RequestParam(required = false) DeliveryEventType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Boolean applied, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        var pageable = Pagination.of(page, size, sort, SORT_EVENTS, "occurredAt", Sort.Direction.DESC);
        return PageResponse.of(deliveryEvents.search(messageId, providerId, type, from, to, applied, pageable),
                e -> new DeliveryEventView(e.getId(), e.getMessageId(), e.getProviderId(), e.getProviderEventId(),
                        e.getType(), e.getOccurredAt(), e.getReceivedAt(), e.isApplied(), e.getIgnoredReason()));
    }
}
