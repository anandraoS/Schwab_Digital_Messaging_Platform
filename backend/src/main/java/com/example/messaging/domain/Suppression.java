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

/** Global suppression entry (hard bounce / complaint / regulatory) keyed by address hash. */
@Entity
@Table(name = "suppression")
@Getter
@Setter
@NoArgsConstructor
public class Suppression extends PersistableEntity {

    @Id
    @Column(length = 36)
    private String id;
    private String tenantId;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private String addressHash;
    private String reason;
    private Instant createdAt;
}
