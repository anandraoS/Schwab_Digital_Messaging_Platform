package com.example.messaging.metrics;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.QueueStats;
import com.example.messaging.provider.ProviderOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * All custom meters (names are API for Grafana/alerts, CONTRACT section 8). Counters for stage-1 flows are wired by
 * the services in this module; the remaining helpers are used by stage 2 (dispatch, providers, callbacks, bulk).
 */
@Component
public class MessagingMetrics {

    public static final String ACCEPTED = "messaging.messages.accepted";
    public static final String TRANSITIONS = "messaging.transitions";
    public static final String DISPATCH_DELAY = "messaging.dispatch.delay";
    public static final String PROVIDER_CALLS = "messaging.provider.calls";
    public static final String PROVIDER_LATENCY = "messaging.provider.latency";
    public static final String CIRCUIT_STATE = "messaging.provider.circuit.state";
    public static final String QUEUE_DEPTH = "messaging.queue.depth";
    public static final String QUEUE_INFLIGHT = "messaging.queue.inflight";
    public static final String QUEUE_DLQ_DEPTH = "messaging.queue.dlq.depth";
    public static final String QUEUE_OLDEST_AGE = "messaging.queue.oldest.age";
    public static final String RETRY_SCHEDULED = "messaging.retry.scheduled";
    public static final String DEADLETTER_TOTAL = "messaging.deadletter.total";
    public static final String CALLBACKS = "messaging.callbacks";
    public static final String BULK_PROGRESS = "messaging.bulk.progress";
    public static final String IDEMPOTENCY_HITS = "messaging.idempotency.hits";
    public static final String RATELIMIT_REJECTED = "messaging.ratelimit.rejected";
    public static final String REDIS_FALLBACK = "messaging.redis.fallback";
    public static final String DIAGNOSTICS_WRITE_FAILURES = "messaging.diagnostics.write.failures";

    private final MeterRegistry registry;
    private final Map<String, AtomicReference<Double>> bulkProgress = new ConcurrentHashMap<>();
    /** Micrometer keeps gauge state objects weakly: suppliers handed to bind* must stay reachable for the app's life. */
    private final java.util.List<Object> gaugeReferences = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Counter idempotencyHits;
    private final Counter redisFallback;
    private final Counter diagnosticsWriteFailures;

    public MessagingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.idempotencyHits = Counter.builder(IDEMPOTENCY_HITS).description("Idempotent replays served").register(registry);
        this.redisFallback = Counter.builder(REDIS_FALLBACK).description("Coordination calls served by in-memory fallback")
                .register(registry);
        this.diagnosticsWriteFailures = Counter.builder(DIAGNOSTICS_WRITE_FAILURES)
                .description("Best-effort provider diagnostics writes that failed").register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    // ---- stage 1 wired ----

    public void accepted(Channel channel, Priority priority, String tenant) {
        registry.counter(ACCEPTED, "channel", channel.name(), "priority", priority.name(), "tenant", tenant).increment();
    }

    public void transition(MessageStatus from, MessageStatus to) {
        registry.counter(TRANSITIONS, "from", from.name(), "to", to.name()).increment();
    }

    public void idempotencyHit() {
        idempotencyHits.increment();
    }

    public void rateLimitRejected(String scope) {
        registry.counter(RATELIMIT_REJECTED, "scope", scope).increment();
    }

    public void redisFallback() {
        redisFallback.increment();
    }

    // ---- helpers for stage 2 ----

    public void diagnosticsWriteFailure() {
        diagnosticsWriteFailures.increment();
    }

    public void providerCall(String provider, Channel channel, ProviderOutcome outcome) {
        registry.counter(PROVIDER_CALLS, "provider", provider, "channel", channel.name(), "outcome", outcome.name())
                .increment();
    }

    public void providerLatency(String provider, Duration latency) {
        Timer.builder(PROVIDER_LATENCY).tag("provider", provider).publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(latency);
    }

    public void dispatchDelay(Duration delay) {
        Timer.builder(DISPATCH_DELAY).publishPercentileHistogram().publishPercentiles(0.5, 0.95, 0.99).register(registry)
                .record(delay);
    }

    public void retryScheduled(Channel channel) {
        registry.counter(RETRY_SCHEDULED, "channel", channel.name()).increment();
    }

    public void deadLetter(DlqReason reason) {
        registry.counter(DEADLETTER_TOTAL, "reason", reason.name()).increment();
    }

    /** @param result accepted, duplicate, invalid_signature, replayed, out_of_order, stale */
    public void callback(String result) {
        registry.counter(CALLBACKS, "result", result).increment();
    }

    /** Registers (once) and updates the {@code messaging.bulk.progress{jobId}} gauge (0.0 - 1.0). */
    public void bulkProgress(String jobId, double fraction) {
        bulkProgress.computeIfAbsent(jobId, id -> {
            AtomicReference<Double> ref = new AtomicReference<>(0.0);
            Gauge.builder(BULK_PROGRESS, ref, AtomicReference::get).tag("jobId", id).register(registry);
            return ref;
        }).set(fraction);
    }

    /** Binds depth gauges (per priority), in-flight and DLQ depth to a live {@link QueueStats} supplier. */
    public void bindQueueStats(Supplier<QueueStats> stats) {
        gaugeReferences.add(stats);
        for (Priority p : Priority.values()) {
            Gauge.builder(QUEUE_DEPTH, stats, s -> s.get().depthByPriority().getOrDefault(p, 0L)).tags(Tags.of("priority", p.name()))
                    .register(registry);
        }
        Gauge.builder(QUEUE_INFLIGHT, stats, s -> s.get().inFlight()).register(registry);
        Gauge.builder(QUEUE_DLQ_DEPTH, stats, s -> s.get().dlqDepth()).register(registry);
        Gauge.builder(QUEUE_OLDEST_AGE, stats, s -> {
            Long age = s.get().oldestAgeMillis();
            return age == null ? 0.0 : age / 1000.0;
        }).baseUnit("seconds").description("Age of the oldest message waiting on the bus").register(registry);
    }

    /** @param state 0 closed, 1 half-open, 2 open */
    public void bindCircuitState(String provider, Supplier<Integer> state) {
        gaugeReferences.add(state);
        Gauge.builder(CIRCUIT_STATE, state, s -> s.get().doubleValue()).tag("provider", provider).register(registry);
    }
}
