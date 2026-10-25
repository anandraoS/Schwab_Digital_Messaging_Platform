package com.example.messaging.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.metrics.MessagingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoordinationTest {

    /** Mutable clock for deterministic refill tests. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void tokenBucketAllowsBurstThenRejectsThenRefills() {
        MutableClock clock = new MutableClock();
        InMemoryCoordination c = new InMemoryCoordination(clock);
        for (int i = 0; i < 5; i++) {
            RateLimitDecision d = c.tryAcquire("k", 2, 5);
            assertThat(d.allowed()).isTrue();
            assertThat(d.remaining()).isEqualTo(4 - i);
            assertThat(d.limit()).isEqualTo(5);
        }
        RateLimitDecision denied = c.tryAcquire("k", 2, 5);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isBetween(1L, 500L);
        clock.advance(Duration.ofMillis(500)); // 2/s -> one token
        assertThat(c.tryAcquire("k", 2, 5).allowed()).isTrue();
        assertThat(c.tryAcquire("k", 2, 5).allowed()).isFalse();
        clock.advance(Duration.ofSeconds(60)); // refill is capped at the burst size
        for (int i = 0; i < 5; i++) {
            assertThat(c.tryAcquire("k", 2, 5).allowed()).isTrue();
        }
        assertThat(c.tryAcquire("k", 2, 5).allowed()).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKey() {
        InMemoryCoordination c = new InMemoryCoordination(new MutableClock());
        assertThat(c.tryAcquire("a", 1, 1).allowed()).isTrue();
        assertThat(c.tryAcquire("a", 1, 1).allowed()).isFalse();
        assertThat(c.tryAcquire("b", 1, 1).allowed()).isTrue();
    }

    @Test
    void concurrentCallersNeverExceedTheBurst() throws Exception {
        InMemoryCoordination c = new InMemoryCoordination(new MutableClock());
        AtomicInteger allowed = new AtomicInteger();
        Thread[] threads = new Thread[16];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    if (c.tryAcquire("shared", 1, 100).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    void cacheEntriesExpire() {
        MutableClock clock = new MutableClock();
        InMemoryCoordination c = new InMemoryCoordination(clock);
        c.putCached("k", "v", Duration.ofSeconds(10));
        assertThat(c.getCached("k")).contains("v");
        clock.advance(Duration.ofSeconds(11));
        assertThat(c.getCached("k")).isEmpty();
    }

    /** Primary that always fails, like a Redis outage. */
    private static final class Broken implements CoordinationService {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "broken";
        }

        @Override
        public Optional<String> getCached(String key) {
            calls.incrementAndGet();
            throw new IllegalStateException("redis down");
        }

        @Override
        public void putCached(String key, String value, Duration ttl) {
            calls.incrementAndGet();
            throw new IllegalStateException("redis down");
        }

        @Override
        public RateLimitDecision tryAcquire(String bucketKey, int ratePerSec, int burst) {
            calls.incrementAndGet();
            throw new IllegalStateException("redis down");
        }
    }

    @Test
    void resilientFallsBackCountsTheMetricAndSkipsThePrimaryDuringCooldown() {
        MutableClock clock = new MutableClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagingMetrics metrics = new MessagingMetrics(registry);
        Broken primary = new Broken();
        ResilientCoordination r = new ResilientCoordination(primary, new InMemoryCoordination(clock), metrics, clock,
                Duration.ofSeconds(30));

        assertThat(r.tryAcquire("x", 1, 1).allowed()).as("served by the fallback").isTrue();
        assertThat(r.tryAcquire("x", 1, 1).allowed()).as("fallback keeps enforcing the limit").isFalse();
        r.putCached("k", "v", Duration.ofMinutes(1));
        assertThat(r.getCached("k")).contains("v");

        assertThat(primary.calls.get()).as("primary is skipped during the cooldown").isEqualTo(1);
        assertThat(registry.get(MessagingMetrics.REDIS_FALLBACK).counter().count()).isEqualTo(4.0);

        clock.advance(Duration.ofSeconds(31));
        r.tryAcquire("y", 1, 1);
        assertThat(primary.calls.get()).as("primary is probed again after the cooldown").isEqualTo(2);
        assertThat(r.name()).contains("broken").contains("memory");
    }
}
