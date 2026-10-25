package com.example.messaging.provider;

import java.time.Instant;

/** Answer of {@link ChannelProvider#lookup}: what the provider knows about an idempotency reference. */
public record ProviderStatus(State state, String providerMessageId, Instant at) {

    public enum State {
        /** Provider has it and accepted it for delivery. */
        ACCEPTED,
        DELIVERED,
        FAILED,
        /** Provider has never seen the reference: a retry is safe. */
        NOT_RECEIVED
    }
}
