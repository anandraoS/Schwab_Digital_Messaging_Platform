package com.example.messaging.provider;

/** Normalised result of a provider call (CONTRACT 3.2). */
public enum ProviderOutcome {
    ACCEPTED, TRANSIENT_FAILURE, PERMANENT_FAILURE, RATE_LIMITED, TIMEOUT, AMBIGUOUS;

    /** Outcomes that are safe to retry without asking the provider first. */
    public boolean isRetryable() {
        return this == TRANSIENT_FAILURE || this == RATE_LIMITED;
    }

    /** Outcomes where we do not know whether the provider accepted the message. */
    public boolean isUncertain() {
        return this == TIMEOUT || this == AMBIGUOUS;
    }
}
