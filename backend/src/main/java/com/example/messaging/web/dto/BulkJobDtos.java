package com.example.messaging.web.dto;

import com.example.messaging.domain.BulkJobStatus;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Bulk job payloads, field for field as in the committed OpenAPI schemas {@code BulkJob} and friends. */
public final class BulkJobDtos {

    private BulkJobDtos() {}

    public record BulkJobView(
            String id,
            String tenantId,
            String name,
            BulkJobStatus status,
            long total,
            long processed,
            long succeeded,
            long failed,
            long duplicate,
            String fileName,
            Integer ratePerSecond,
            String createdBy,
            Instant startedAt,
            Instant finishedAt) {}

    /** {@code lineNo} 0 is the job-level failure (why the job as a whole failed). The row itself is only a hash. */
    public record BulkJobFailureView(long lineNo, String reason, String rawLineHash) {}

    public record GenerateSyntheticRequest(
            @NotNull @Min(1) @Max(10_000_000) Long rows,
            @DecimalMin("0.0") @DecimalMax("1.0") Double duplicateRate,
            @DecimalMin("0.0") @DecimalMax("1.0") Double invalidRate,
            @Size(max = 100) String templateCode,
            Channel channel,
            Category category) {}
}
