package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bulk_job_failure")
@Getter
@Setter
@NoArgsConstructor
public class BulkJobFailure extends PersistableEntity {

    @Id
    @Column(length = 36)
    private String id;
    private String jobId;
    private long lineNo;
    private String reason;
    private String rawLineHash;
    private Instant createdAt;
}
