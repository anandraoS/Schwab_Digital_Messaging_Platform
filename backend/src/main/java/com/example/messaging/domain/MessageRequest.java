package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Aggregate root of the delivery lifecycle. Stores template variables (not the rendered body) per the
 * "redacted at rest" policy. Status changes after submission go through NotificationService (CAS update).
 */
@Entity
@Table(name = "message_request")
@Getter
@Setter
@NoArgsConstructor
public class MessageRequest {

    @Id
    @Column(length = 36)
    private String id;
    private String tenantId;
    private String clientApp;
    private String clientReference;
    private String recipientId;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Category category;
    @Enumerated(EnumType.STRING)
    private Priority priority;
    @Enumerated(EnumType.STRING)
    private MessageStatus status;
    private String statusReason;
    private String templateCode;
    private int templateVersionNo;
    private String templateLocale;
    @Column(length = 8000)
    private String payloadJson;
    @Column(length = 2000)
    private String optionsJson;
    @Column(length = 2000)
    private String metadataJson;
    /** Comma separated, e.g. {@code SMS,PUSH}. */
    private String fallbackChannels;
    private int fallbackIndex;
    private String correlationId;
    private String idempotencyKey;
    private String requestHash;
    private Instant scheduledAt;
    private Instant expiresAt;
    private int attemptCount;
    private int maxAttempts = 5;
    private Instant nextAttemptAt;
    @Enumerated(EnumType.STRING)
    private Channel channelUsed;
    private Instant lockedUntil;
    @Enumerated(EnumType.STRING)
    private DlqReason dlqReason;
    private boolean discarded;
    private String bulkJobId;
    private Instant statusChangedAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    public List<Channel> fallbackList() {
        List<Channel> out = new ArrayList<>();
        if (fallbackChannels != null && !fallbackChannels.isBlank()) {
            for (String s : fallbackChannels.split(",")) {
                out.add(Channel.valueOf(s.trim()));
            }
        }
        return out;
    }
}
