package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "idempotency_record")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord extends PersistableEntity {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";

    @Id
    @Column(length = 36)
    private String id;
    private String tenantId;
    @Column(name = "idem_key")
    private String idemKey;
    private String requestHash;
    private String status;
    private Integer responseStatus;
    @Column(length = 4000)
    private String responseBody;
    private String messageId;
    private Instant createdAt;
    private Instant expiresAt;
}
