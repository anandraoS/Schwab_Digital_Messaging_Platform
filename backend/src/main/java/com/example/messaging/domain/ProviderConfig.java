package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "provider_config")
@Getter
@Setter
@NoArgsConstructor
public class ProviderConfig {

    @Id
    @Column(length = 36)
    private String id;
    private String providerId;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private boolean enabled;
    private int priorityOrder;
    @Column(name = "rate_limit_per_sec")
    private int rateLimitPerSec;
    private int timeoutMs;
    private String hmacKeyRef;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;
}
