package com.example.messaging.batch;

import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;

/** Immutable, per-run view of the {@code bulk_job} row plus the CSV location (loaded once per step execution). */
public record BulkJobContext(String jobId, String tenantId, String name, String templateCode, Channel channel,
        Category category, int ratePerSecond, String createdBy, String filePath) {}
