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
@Table(name = "bulk_job")
@Getter
@Setter
@NoArgsConstructor
public class BulkJob {

    @Id
    @Column(length = 36)
    private String id;
    private String tenantId;
    private String name;
    @Enumerated(EnumType.STRING)
    private BulkJobStatus status;
    private String templateCode;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Category category;
    private long total;
    private long processed;
    private long succeeded;
    private long failed;
    private long duplicate;
    private String fileName;
    @Column(name = "rate_limit_per_sec")
    private Integer rateLimitPerSec;
    private String createdBy;
    private Instant startedAt;
    private Instant finishedAt;
    private Long springBatchExecutionId;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;
}
