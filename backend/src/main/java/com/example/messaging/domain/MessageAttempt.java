package com.example.messaging.domain;

import com.example.messaging.provider.ProviderOutcome;
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

/** One provider call. Written as STARTED before the call, finished afterwards (crash-safe). */
@Entity
@Table(name = "message_attempt")
@Getter
@Setter
@NoArgsConstructor
public class MessageAttempt extends PersistableEntity {

    public static final String STARTED = "STARTED";
    public static final String FINISHED = "FINISHED";

    @Id
    @Column(length = 36)
    private String id;
    private String messageId;
    private int attemptNo;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private String providerId;
    private String state;
    @Enumerated(EnumType.STRING)
    private ProviderOutcome outcome;
    private String providerMessageId;
    private String errorCode;
    private Long latencyMs;
    private Instant startedAt;
    private Instant finishedAt;
}
