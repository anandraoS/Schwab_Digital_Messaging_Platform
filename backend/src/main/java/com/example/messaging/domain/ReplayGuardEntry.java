package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Callback replay cache row keyed by provider {@code X-Event-Id}. */
@Entity
@Table(name = "replay_guard")
@Getter
@Setter
@NoArgsConstructor
public class ReplayGuardEntry extends PersistableEntity {

    @Id
    @Column(name = "event_id", length = 200)
    private String eventId;
    private Instant seenAt;

    @Override
    public String getId() {
        return eventId;
    }
}
