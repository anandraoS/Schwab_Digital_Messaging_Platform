package com.example.messaging.messaging;

/** Publish refused (backpressure threshold reached or bus unavailable); callers surface 503 + Retry-After. */
public class BusRejectedException extends RuntimeException {

    private final long retryAfterSeconds;

    public BusRejectedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
