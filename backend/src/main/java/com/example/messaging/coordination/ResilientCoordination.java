package com.example.messaging.coordination;

import com.example.messaging.metrics.MessagingMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator: uses the primary (Redis) and falls back to the local in-memory implementation when the primary throws.
 * After a failure the primary is skipped for {@code cooldown} so a Redis outage does not add latency to each request.
 * Every fallback increments {@code messaging.redis.fallback}. Degraded mode is per-instance (limits are no longer
 * global), which is the accepted trade-off versus failing requests.
 */
public class ResilientCoordination implements CoordinationService {

    private static final Logger log = LoggerFactory.getLogger(ResilientCoordination.class);

    private final CoordinationService primary;
    private final CoordinationService fallback;
    private final MessagingMetrics metrics;
    private final Clock clock;
    private final long cooldownMillis;
    private final AtomicLong primaryBlockedUntil = new AtomicLong(0);

    public ResilientCoordination(CoordinationService primary, CoordinationService fallback, MessagingMetrics metrics,
            Clock clock, Duration cooldown) {
        this.primary = primary;
        this.fallback = fallback;
        this.metrics = metrics;
        this.clock = clock;
        this.cooldownMillis = cooldown.toMillis();
    }

    @Override
    public String name() {
        return primary.name() + "+" + fallback.name() + "-fallback";
    }

    @Override
    public Optional<String> getCached(String key) {
        if (primaryAvailable()) {
            try {
                return primary.getCached(key);
            } catch (RuntimeException e) {
                tripped(e);
            }
        }
        metrics.redisFallback();
        return fallback.getCached(key);
    }

    @Override
    public void putCached(String key, String value, Duration ttl) {
        if (primaryAvailable()) {
            try {
                primary.putCached(key, value, ttl);
                return;
            } catch (RuntimeException e) {
                tripped(e);
            }
        }
        metrics.redisFallback();
        fallback.putCached(key, value, ttl);
    }

    @Override
    public RateLimitDecision tryAcquire(String bucketKey, int ratePerSec, int burst) {
        if (primaryAvailable()) {
            try {
                return primary.tryAcquire(bucketKey, ratePerSec, burst);
            } catch (RuntimeException e) {
                tripped(e);
            }
        }
        metrics.redisFallback();
        return fallback.tryAcquire(bucketKey, ratePerSec, burst);
    }

    private boolean primaryAvailable() {
        return clock.millis() >= primaryBlockedUntil.get();
    }

    private void tripped(RuntimeException e) {
        primaryBlockedUntil.set(clock.millis() + cooldownMillis);
        log.warn("Coordination backend '{}' failed ({}); using in-memory fallback for {} ms", primary.name(),
                e.getClass().getSimpleName(), cooldownMillis);
    }
}
