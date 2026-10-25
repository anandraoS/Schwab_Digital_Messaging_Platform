package com.example.messaging.web.dto;

import java.time.Instant;
import java.util.Map;

public final class AuditDtos {

    private AuditDtos() {}

    public record AuditEventView(
            String id,
            String tenantId,
            String actor,
            String actorRole,
            String action,
            String entityType,
            String entityId,
            String correlationId,
            Map<String, Object> detail,
            Instant occurredAt) {}

    public record MeResponse(
            String subject,
            String tenant,
            String effectiveTenant,
            java.util.List<String> roles,
            java.util.List<String> scopes,
            boolean platformAdmin) {}
}
