package com.example.messaging.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Runtime knobs of the dispatch pipeline (stage 2): consumer wiring, schedulers and provider resilience. Bound to
 * {@code messaging.pipeline.*}; the older {@code messaging.dispatch.*} keys (concurrency, prefetch, attempts,
 * backoff, poison threshold, lock TTL, reconcile-after, receipt-timeout) stay in {@link MessagingProperties}.
 */
@ConfigurationProperties("messaging.pipeline")
public record PipelineProperties(
        /** Subscribe the dispatch consumer and start bus workers on startup. Tests of lower layers switch it off. */
        @DefaultValue("true") boolean autoStart,
        @DefaultValue("30s") Duration shutdownTimeout,
        /** Delay used when the provider token bucket is empty (no attempt consumed). */
        @DefaultValue("250ms") Duration rateLimitRequeueDelay,
        /** Delay used when an envelope overtakes the outbox publisher's commit (message still VALIDATED). */
        @DefaultValue("150ms") Duration validatedRequeueDelay,
        /** How long an X-Event-Id is remembered by the callback replay guard. */
        @DefaultValue("24h") Duration replayWindow,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Resilience resilience,
        /**
         * A QUEUED message older than this without a processed dispatch is re-published by the poller (its envelope
         * was probably lost with a restarted in-memory bus). Duplicates are harmless: the consumer is idempotent.
         */
        @DefaultValue("10m") Duration queuedStaleAfter) {

    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("200") long outboxIntervalMs,
            @DefaultValue("100") int outboxBatch,
            @DefaultValue("1000") long pollerIntervalMs,
            /** Overdue time before the poller steps in for a retry whose delayed envelope never arrived. */
            @DefaultValue("5s") Duration retryGrace,
            /** A VALIDATED message without any pending outbox row is re-enqueued after this age. */
            @DefaultValue("1m") Duration orphanAge,
            @DefaultValue("10000") long sweeperIntervalMs,
            @DefaultValue("100") int sweepBatch,
            @DefaultValue("15000") long reconcileIntervalMs,
            @DefaultValue("50") int reconcileBatch,
            @DefaultValue("5") int reconcileMaxChecks) {}

    public record Resilience(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("20") int slidingWindowSize,
            @DefaultValue("10") int minimumCalls,
            @DefaultValue("5s") Duration openStateWait,
            @DefaultValue("3") int halfOpenCalls,
            @DefaultValue("16") int executorThreads,
            @DefaultValue("64") int executorQueue) {}
}
