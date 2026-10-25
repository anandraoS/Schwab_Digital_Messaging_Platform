package com.example.messaging.coordination;

/**
 * @param limit the bucket capacity (burst)
 * @param remaining tokens left after this call
 * @param retryAfterMillis how long until one token is available (0 when allowed)
 * @param resetMillis how long until the bucket is full again
 */
public record RateLimitDecision(boolean allowed, int limit, long remaining, long retryAfterMillis, long resetMillis) {}
