package com.example.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.provider.ProviderOutcome;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(1000, 60000, 5);

    @Test
    void ceilingDoublesPerAttemptAndIsCapped() {
        assertThat(policy.ceiling(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.ceiling(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.ceiling(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.ceiling(6)).isEqualTo(Duration.ofSeconds(32));
        assertThat(policy.ceiling(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.ceiling(100)).as("no overflow for absurd attempt numbers").isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.ceiling(0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void jitteredBackoffStaysBetweenHalfAndFullCeiling() {
        Random random = new Random(42);
        for (int attempt = 1; attempt <= 10; attempt++) {
            long ceil = policy.ceiling(attempt).toMillis();
            for (int i = 0; i < 200; i++) {
                long ms = policy.backoff(attempt, random).toMillis();
                assertThat(ms).isBetween(ceil / 2, ceil);
            }
        }
    }

    @Test
    void onlyTransientAndRateLimitedOutcomesAreRetriedAutomatically() {
        assertThat(policy.isRetryable(ProviderOutcome.TRANSIENT_FAILURE)).isTrue();
        assertThat(policy.isRetryable(ProviderOutcome.RATE_LIMITED)).isTrue();
        assertThat(policy.isRetryable(ProviderOutcome.PERMANENT_FAILURE)).isFalse();
        assertThat(policy.isRetryable(ProviderOutcome.ACCEPTED)).isFalse();
        assertThat(policy.isRetryable(ProviderOutcome.TIMEOUT)).as("uncertain outcomes are never blind-retried").isFalse();
        assertThat(policy.isRetryable(ProviderOutcome.AMBIGUOUS)).isFalse();
        assertThat(policy.isRetryable(null)).isFalse();
    }

    @Test
    void attemptsRemainingHonoursThePerMessageLimit() {
        assertThat(policy.attemptsRemaining(0, 3)).isTrue();
        assertThat(policy.attemptsRemaining(2, 3)).isTrue();
        assertThat(policy.attemptsRemaining(3, 3)).isFalse();
        assertThat(policy.attemptsRemaining(4, 0)).as("0 falls back to the configured default of 5").isTrue();
        assertThat(policy.attemptsRemaining(5, 0)).isFalse();
    }
}
