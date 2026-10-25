package com.example.messaging.batch;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Live view of how many chunk writers run at the same time (gauge {@code messaging.bulk.writers.active}). The
 * high-water mark is what the bounded-concurrency test asserts against {@code messaging.bulk.concurrency}.
 */
@Component
public class BulkWriterStats {

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger max = new AtomicInteger();

    public BulkWriterStats(MeterRegistry registry) {
        Gauge.builder("messaging.bulk.writers.active", active, AtomicInteger::get)
                .description("Bulk chunk writers currently running").register(registry);
    }

    void enter() {
        int now = active.incrementAndGet();
        max.accumulateAndGet(now, Math::max);
    }

    void exit() {
        active.decrementAndGet();
    }

    public int active() {
        return active.get();
    }

    public int maxObserved() {
        return max.get();
    }

    public void reset() {
        max.set(active.get());
    }
}
