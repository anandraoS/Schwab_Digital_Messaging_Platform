package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Synthetic recipient (customer). Addresses are PII-like: never logged, always masked in API responses. */
@Entity
@Table(name = "recipient")
@Getter
@Setter
@NoArgsConstructor
public class Recipient {

    @Id
    @Column(length = 36)
    private String id;
    private String tenantId;
    private String externalRef;
    private String displayName;
    private String email;
    @Column(name = "phone_e164")
    private String phoneE164;
    private String pushToken;
    private String locale;
    private String timeZone;
    private boolean deleted;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    public String addressFor(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> phoneE164;
            case PUSH -> pushToken;
        };
    }
}
