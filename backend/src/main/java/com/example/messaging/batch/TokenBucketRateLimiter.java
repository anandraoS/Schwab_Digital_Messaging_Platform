package com.example.messaging.batch;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Small, dependency-free, thread-safe rate limiter (token bucket expressed as a reservation timeline). Callers
 * reserve the next free slot under a lock and then wait outside it, so many threads share one limit without
 * convoying. The bucket holds at most 100 ms worth of permits, so a quiet job cannot save up a large burst.
 */
public final class TokenBucketRateLimiter {

    private static final TokenBucketRateLimiter UNLIMITED = new TokenBucketRateLimiter(0, System::nanoTime, nanos -> { });

    private final long intervalNanos;
    private final long burstNanos;
    private final LongSupplier nanoTime;
    private final LongConsumer sleeper;
    private long nextFree;
    private boolean started;

    public TokenBucketRateLimiter(int permitsPerSecond, LongSupplier nanoTime, LongConsumer sleeper) {
        this.intervalNanos = permitsPerSecond <= 0 ? 0 : TimeUnit.SECONDS.toNanos(1) / permitsPerSecond;
        this.burstNanos = permitsPerSecond <= 0 ? 0 : Math.max(1, permitsPerSecond / 10) * intervalNanos;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    /** Real-clock limiter; {@code permitsPerSecond <= 0} means unlimited. */
    public static TokenBucketRateLimiter perSecond(int permitsPerSecond) {
        return permitsPerSecond <= 0 ? UNLIMITED
                : new TokenBucketRateLimiter(permitsPerSecond, System::nanoTime, TokenBucketRateLimiter::parkNanos);
    }

    public boolean isUnlimited() {
        return intervalNanos == 0;
    }

    /** Blocks until one permit is available. */
    public void acquire() {
        acquire(1);
    }

    public void acquire(int permits) {
        if (intervalNanos == 0 || permits <= 0) {
            return;
        }
        long waitNanos;
        synchronized (this) {
            long now = nanoTime.getAsLong();
            if (!started) {
                started = true;
                nextFree = now - burstNanos;
            }
            long earliest = Math.max(nextFree, now - burstNanos);
            nextFree = earliest + intervalNanos * permits;
            waitNanos = Math.max(0, earliest - now);
        }
        if (waitNanos > 0) {
            sleeper.accept(waitNanos);
        }
    }

    private static void parkNanos(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long remaining = nanos;
        while (remaining > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining = deadline - System.nanoTime();
        }
    }
}
