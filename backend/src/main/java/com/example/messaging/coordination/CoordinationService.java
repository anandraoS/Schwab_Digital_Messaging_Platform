package com.example.messaging.coordination;

import java.time.Duration;
import java.util.Optional;

/**
 * Port for cross-instance coordination: idempotency fast-path cache and token-bucket rate limiting.
 * Implementations: {@link InMemoryCoordination}, {@link RedisCoordination}, decorated by
 * {@link ResilientCoordination}. The database stays the source of truth for idempotency: this is only a fast path.
 */
public interface CoordinationService {

    String name();

    Optional<String> getCached(String key);

    void putCached(String key, String value, Duration ttl);

    /**
     * Token bucket: capacity {@code burst}, refilled at {@code ratePerSec}. Consumes one token when available.
     */
    RateLimitDecision tryAcquire(String bucketKey, int ratePerSec, int burst);
}
