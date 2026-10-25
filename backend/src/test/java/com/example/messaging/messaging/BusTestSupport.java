package com.example.messaging.messaging;

import com.example.messaging.TestProps;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.PipelineConfig.BackpressureLimits;
import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.metrics.MessagingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Builders shared by the bus unit tests. */
final class BusTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private BusTestSupport() {}

    static MessagingProperties props(int concurrency, int poisonThreshold) {
        MessagingProperties d = TestProps.defaults();
        return new MessagingProperties(
                new MessagingProperties.Dispatch(concurrency, 16, 5, 1000, 60000, poisonThreshold,
                        Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(2)),
                d.backpressure(), d.bus(), d.coordination(), d.providers(), d.callback(), d.api(), d.bulk(),
                d.retention(), d.security(), d.templates(), d.idempotency());
    }

    static PipelineProperties pipeline(Duration shutdownTimeout) {
        return new PipelineProperties(true, shutdownTimeout, Duration.ofMillis(250), Duration.ofMillis(150),
                Duration.ofHours(24),
                new PipelineProperties.Scheduler(true, 200, 100, 1000, Duration.ofSeconds(5), Duration.ofMinutes(1),
                        10_000, 100, 15_000, 50, 5),
                new PipelineProperties.Resilience(50f, 20, 10, Duration.ofSeconds(5), 3, 16, 64), Duration.ofMinutes(10));
    }

    static InMemoryMessageBus bus(int concurrency, int poisonThreshold, int maxDepth, Duration shutdownTimeout,
            MessagingMetrics metrics) {
        return new InMemoryMessageBus(props(concurrency, poisonThreshold), pipeline(shutdownTimeout),
                new BackpressureLimits(maxDepth), metrics);
    }

    static MessagingMetrics metrics() {
        return new MessagingMetrics(new SimpleMeterRegistry());
    }

    static DispatchEnvelope envelope(Priority priority) {
        return envelope("m-" + SEQ.incrementAndGet(), priority, 1);
    }

    static DispatchEnvelope envelope(String messageId, Priority priority, int attemptNo) {
        return DispatchEnvelope.of(messageId, "tenant-retail", Channel.EMAIL, priority, attemptNo, "corr", Instant.now());
    }
}
