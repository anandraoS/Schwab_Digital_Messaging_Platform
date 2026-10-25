package com.example.messaging.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Additional {@code messaging.retention.*} keys owned by {@link RetentionJob}. The age keys
 * {@code message-days, audit-days, event-days} stay in
 * {@link com.example.messaging.config.MessagingProperties.Retention}.
 */
@ConfigurationProperties("messaging.retention")
public record RetentionProperties(
        /** Master switch of the scheduled run (manual {@code RetentionJob.run} always works). */
        @DefaultValue("true") boolean enabled,
        /** Count what would be purged, delete nothing. */
        @DefaultValue("false") boolean dryRun,
        /** Rows per transaction (1..1000: stays under the IN-list limit of every supported database). */
        @DefaultValue("500") int batchSize,
        /** Pause between batches so purging yields to online traffic (milliseconds). */
        @DefaultValue("20") long pauseMs,
        /** Safety valve: maximum batches per table and run. */
        @DefaultValue("100000") int maxBatchesPerTable,
        /** {@code processed_message} rows older than this many days are purged (must exceed the replay horizon). */
        @DefaultValue("7") int processedMessageDays,
        /** SENT {@code outbox_event} rows older than this many days are purged. */
        @DefaultValue("7") int outboxSentDays,
        /** {@code replay_guard} rows older than this many hours are purged (at least twice the callback tolerance). */
        @DefaultValue("24") int replayGuardHours,
        /** Also delete bulk upload files older than {@code message-days}. */
        @DefaultValue("true") boolean purgeBulkFiles,
        /** Cron of the scheduled run (server time zone). */
        @DefaultValue("0 30 2 * * *") String cron) {

    public int effectiveBatchSize() {
        return Math.max(1, Math.min(1000, batchSize));
    }
}
