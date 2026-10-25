package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "recipient_preference")
@Getter
@Setter
@NoArgsConstructor
public class RecipientPreference {

    @Id
    @Column(name = "recipient_id", length = 36)
    private String recipientId;
    private boolean marketingOptIn;
    private boolean emailEnabled = true;
    private boolean smsEnabled = true;
    private boolean pushEnabled = true;
    private LocalTime quietStart;
    private LocalTime quietEnd;
    private String fallbackOrder;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    public boolean channelEnabled(Channel channel) {
        return switch (channel) {
            case EMAIL -> emailEnabled;
            case SMS -> smsEnabled;
            case PUSH -> pushEnabled;
        };
    }
}
