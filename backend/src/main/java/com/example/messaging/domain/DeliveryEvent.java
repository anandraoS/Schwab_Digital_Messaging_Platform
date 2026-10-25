package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Normalised provider receipt. Unique on (provider_id, provider_event_id) for dedupe. */
@Entity
@Table(name = "delivery_event")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryEvent extends PersistableEntity {

    @Id
    @Column(length = 36)
    private String id;
    private String messageId;
    private String providerId;
    private String providerEventId;
    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private DeliveryEventType type;
    private Instant occurredAt;
    private Instant receivedAt;
    private boolean applied;
    private String ignoredReason;
    private String rawRef;
}
