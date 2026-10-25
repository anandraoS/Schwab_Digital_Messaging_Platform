package com.example.messaging.service;

import com.example.messaging.domain.DeliveryEvent;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.repository.DeliveryEventRepository;
import com.example.messaging.repository.Specs;
import com.example.messaging.security.TenantContext;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped search over delivery events (the table itself has no tenant column: scoped via the message). */
@Service
public class DeliveryEventQueryService {

    private final DeliveryEventRepository events;
    private final TenantContext tenantContext;

    public DeliveryEventQueryService(DeliveryEventRepository events, TenantContext tenantContext) {
        this.events = events;
        this.tenantContext = tenantContext;
    }

    @Transactional(readOnly = true)
    public Page<DeliveryEvent> search(String messageId, String providerId, DeliveryEventType type, Instant from,
            Instant to, Boolean applied, Pageable pageable) {
        Specification<DeliveryEvent> spec = Specification.<DeliveryEvent>where(inTenant(tenantContext.scopeTenant()))
                .and(Specs.eq("messageId", messageId))
                .and(Specs.eq("providerId", providerId))
                .and(Specs.eq("type", type))
                .and(Specs.from("occurredAt", from))
                .and(Specs.to("occurredAt", to))
                .and(Specs.eq("applied", applied));
        return events.findAll(spec, pageable);
    }

    private static Specification<DeliveryEvent> inTenant(String tenantId) {
        return (root, query, cb) -> {
            if (tenantId == null) {
                return null;
            }
            Subquery<String> sub = query.subquery(String.class);
            var m = sub.from(MessageRequest.class);
            sub.select(m.get("id")).where(cb.equal(m.get("id"), root.get("messageId")),
                    cb.equal(m.get("tenantId"), tenantId));
            return cb.exists(sub);
        };
    }
}
