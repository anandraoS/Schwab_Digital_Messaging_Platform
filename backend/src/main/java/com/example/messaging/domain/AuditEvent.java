package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

/** Append-only audit trail (Hibernate never issues UPDATE for {@link Immutable} entities). */
@Entity
@Immutable
@Table(name = "audit_event")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent extends PersistableEntity {

    @Id
    @Column(length = 36)
    private String id;
    private String tenantId;
    private String actor;
    private String actorRole;
    private String action;
    private String entityType;
    private String entityId;
    private String correlationId;
    @Column(length = 4000)
    private String detailJson;
    private Instant occurredAt;
}
