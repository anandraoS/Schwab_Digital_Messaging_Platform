package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    /** Deterministic clock: sleeping advances time, nothing really waits. */
    static final class FakeTime {
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final List<Long> sleeps = new ArrayList<>();

        long now() {
            return nanos.get();
        }

        void sleep(long n) {
            synchronized (sleeps) {
                sleeps.add(n);
            }
            nanos.addAndGet(n);
        }
    }

    @Test
    void paceIsTheConfiguredRate_afterTheSmallBurst() {
        FakeTime t = new FakeTime();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(10, t::now, t::sleep); // one permit per 100 ms
        long start = t.now();
        for (int i = 0; i < 51; i++) {
            l.acquire();
        }
        double seconds = (t.now() - start) / 1e9;
        // 51 permits at 10/s: about 5 s, minus the (at most 100 ms) burst
        assertThat(seconds).isBetween(4.8, 5.1);
        assertThat(t.sleeps).allMatch(s -> s <= 101_000_000L); // never a longer single wait than one interval
    }

    @Test
    void anIdleLimiter_doesNotSaveUpAHugeBurst() {
        FakeTime t = new FakeTime();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(100, t::now, t::sleep);
        l.acquire();
        t.nanos.addAndGet(60_000_000_000L); // one idle minute
        long before = t.sleeps.size();
        for (int i = 0; i < 100; i++) {
            l.acquire();
        }
        // only the 100 ms burst (10 permits) is free; the other 90 had to wait
        assertThat(t.sleeps.size() - before).isGreaterThanOrEqualTo(85);
    }

    @Test
    void permitsCanBeAcquiredInBulk() {
        FakeTime t = new FakeTime();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(10, t::now, t::sleep);
        long start = t.now();
        l.acquire(20);
        l.acquire(1);
        assertThat((t.now() - start) / 1e9).isBetween(1.8, 2.1);
    }

    @Test
    void unlimited_neverWaits() {
        TokenBucketRateLimiter l = TokenBucketRateLimiter.perSecond(0);
        assertThat(l.isUnlimited()).isTrue();
        long t0 = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) {
            l.acquire();
        }
        assertThat((System.nanoTime() - t0) / 1e9).isLessThan(1.0);
        assertThat(TokenBucketRateLimiter.perSecond(-5).isUnlimited()).isTrue();
        assertThat(TokenBucketRateLimiter.perSecond(5).isUnlimited()).isFalse();
    }

    @Test
    void manyThreadsShareOneLimit_inRealTime() throws Exception {
        TokenBucketRateLimiter l = TokenBucketRateLimiter.perSecond(200);
        int threads = 8;
        int each = 25; // 200 permits in total, burst of 20: about 0.9 s at 200/s
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                for (int k = 0; k < each; k++) {
                    l.acquire();
                }
                return null;
            }));
        }
        long t0 = System.nanoTime();
        go.countDown();
        for (var f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        pool.shutdownNow();
        assertThat(seconds).as("threads must not multiply the rate").isGreaterThanOrEqualTo(0.7);
        assertThat(seconds).isLessThan(5.0);
    }
}
