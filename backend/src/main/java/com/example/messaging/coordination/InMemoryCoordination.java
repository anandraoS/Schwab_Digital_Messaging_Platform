package com.example.messaging.coordination;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Single-node implementation (local/test, and the fallback when Redis is down). Thread-safe. */
public class InMemoryCoordination implements CoordinationService {

    private static final int MAX_CACHE_ENTRIES = 50_000;

    private record CacheEntry(String value, long expiresAtMillis) {}

    private static final class Bucket {
        double tokens;
        long lastMillis;
    }

    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryCoordination(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public Optional<String> getCached(String key) {
        CacheEntry e = cache.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expiresAtMillis() <= clock.millis()) {
            cache.remove(key, e);
            return Optional.empty();
        }
        return Optional.of(e.value());
    }

    @Override
    public void putCached(String key, String value, Duration ttl) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            long now = clock.millis();
            cache.entrySet().removeIf(en -> en.getValue().expiresAtMillis() <= now);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                return; // bounded: the DB remains the source of truth, so dropping a cache write is safe
            }
        }
        cache.put(key, new CacheEntry(value, clock.millis() + ttl.toMillis()));
    }

    @Override
    public RateLimitDecision tryAcquire(String bucketKey, int ratePerSec, int burst) {
        int rate = Math.max(1, ratePerSec);
        int cap = Math.max(1, burst);
        Bucket b = buckets.computeIfAbsent(bucketKey, k -> {
            Bucket nb = new Bucket();
            nb.tokens = cap;
            nb.lastMillis = clock.millis();
            return nb;
        });
        synchronized (b) {
            long now = clock.millis();
            long delta = Math.max(0, now - b.lastMillis);
            b.tokens = Math.min(cap, b.tokens + delta * rate / 1000.0);
            b.lastMillis = now;
            boolean allowed = b.tokens >= 1.0;
            if (allowed) {
                b.tokens -= 1.0;
            }
            long retryAfter = allowed ? 0 : (long) Math.ceil((1.0 - b.tokens) * 1000.0 / rate);
            long reset = (long) Math.ceil((cap - b.tokens) * 1000.0 / rate);
            return new RateLimitDecision(allowed, cap, (long) Math.floor(b.tokens), retryAfter, reset);
        }
    }
}
