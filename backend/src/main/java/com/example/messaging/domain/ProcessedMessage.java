package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Consumer-side dedupe: one row per dispatchId that has been handled. */
@Entity
@Table(name = "processed_message")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedMessage extends PersistableEntity {

    @Id
    @Column(length = 36)
    private String id;
    private String dispatchId;
    private String messageId;
    private Instant processedAt;
}
