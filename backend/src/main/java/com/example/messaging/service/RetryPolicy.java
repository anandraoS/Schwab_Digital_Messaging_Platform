package com.example.messaging.service;

import com.example.messaging.config.MessagingProperties;
import com.example.messaging.provider.ProviderOutcome;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Retry decisions: exponential backoff with jitter ({@code min(cap, base * 2^(n-1))} scaled by a random factor in
 * [0.5, 1.0]) and the retry/no-retry classification of provider outcomes (CONTRACT section 6).
 */
@Component
public class RetryPolicy {

    private final long baseMs;
    private final long capMs;
    private final int maxAttempts;

    @org.springframework.beans.factory.annotation.Autowired
    public RetryPolicy(MessagingProperties props) {
        this(props.dispatch().backoffBaseMs(), props.dispatch().backoffCapMs(), props.dispatch().maxAttempts());
    }

    public RetryPolicy(long baseMs, long capMs, int maxAttempts) {
        this.baseMs = Math.max(1, baseMs);
        this.capMs = Math.max(this.baseMs, capMs);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Un-jittered backoff for the given (1-based) attempt number; overflow-safe. */
    public Duration ceiling(int attemptNo) {
        int shift = Math.min(Math.max(attemptNo - 1, 0), 30);
        long raw = baseMs * (1L << shift);
        if (raw < 0 || raw > capMs) {
            raw = capMs;
        }
        return Duration.ofMillis(raw);
    }

    /** Jittered backoff in [ceiling/2, ceiling]. */
    public Duration backoff(int attemptNo, RandomGenerator random) {
        long ceil = ceiling(attemptNo).toMillis();
        double factor = 0.5 + 0.5 * random.nextDouble();
        return Duration.ofMillis(Math.max(1, (long) (ceil * factor)));
    }

    public boolean attemptsRemaining(int attemptsMade, int maxAttemptsForMessage) {
        return attemptsMade < Math.min(maxAttemptsForMessage <= 0 ? maxAttempts : maxAttemptsForMessage, 1000);
    }

    /** Only transient failures and rate limiting are retried automatically. Uncertain outcomes are never blind-retried. */
    public boolean isRetryable(ProviderOutcome outcome) {
        return outcome != null && outcome.isRetryable();
    }
}
