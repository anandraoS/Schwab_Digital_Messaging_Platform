package com.example.messaging.service;

import com.example.messaging.common.Ids;
import com.example.messaging.common.Redaction;
import com.example.messaging.domain.AuditEvent;
import com.example.messaging.repository.AuditEventRepository;
import com.example.messaging.repository.Specs;
import com.example.messaging.security.AuthorizationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only audit trail. Writes join the caller's transaction so state change and audit commit atomically. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAIL = 4000;

    private final AuditEventRepository repository;
    private final AuthorizationService authz;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, AuthorizationService authz, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.authz = authz;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent record(String tenantId, String action, String entityType, String entityId,
            Map<String, ?> detail) {
        return recordAs(authz.actor(), authz.actorRoles(), tenantId, action, entityType, entityId, detail);
    }

    /** For system actors (consumers, schedulers, callbacks) that have no authenticated principal. */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordAs(String actor, String actorRole, String tenantId, String action, String entityType,
            String entityId, Map<String, ?> detail) {
        AuditEvent e = new AuditEvent();
        e.setId(Ids.newId());
        e.setTenantId(tenantId);
        e.setActor(actor);
        e.setActorRole(actorRole);
        e.setAction(action);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setCorrelationId(MDC.get("correlationId"));
        e.setDetailJson(serialize(detail));
        e.setOccurredAt(clock.instant());
        return repository.save(e);
    }

    private String serialize(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            String json = mapper.writeValueAsString(Redaction.redactKeys(detail));
            return json.length() <= MAX_DETAIL ? json : "{\"truncated\":true}";
        } catch (Exception ex) {
            log.warn("Could not serialise audit detail: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    public Map<String, Object> parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(String tenantScope, String entityType, String entityId, String action, String actor,
            Instant from, Instant to, Pageable pageable) {
        Specification<AuditEvent> spec = Specification.<AuditEvent>where(Specs.eq("tenantId", tenantScope))
                .and(Specs.eq("entityType", entityType))
                .and(Specs.eq("entityId", entityId))
                .and(Specs.eq("action", action))
                .and(Specs.eq("actor", actor))
                .and(Specs.from("occurredAt", from))
                .and(Specs.to("occurredAt", to));
        return repository.findAll(spec, pageable);
    }
}
