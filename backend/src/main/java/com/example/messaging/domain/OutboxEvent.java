package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Transactional outbox row. {@code payload} is a JSON serialised {@code DispatchEnvelope};
 * {@code availableAt} lets scheduled/deferred messages stay invisible to the publisher until due.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent extends PersistableEntity {

    public static final String NEW = "NEW";
    public static final String SENT = "SENT";
    public static final String TOPIC_DISPATCH = "dispatch";

    @Id
    @Column(length = 36)
    private String id;
    private String aggregateId;
    private String topic;
    @Column(length = 4000)
    private String payload;
    private String status;
    private Instant availableAt;
    private Instant createdAt;
    private Instant sentAt;
}
