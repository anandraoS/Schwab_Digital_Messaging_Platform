package com.example.messaging.provider;

/** Result of {@link ChannelProvider#send}. {@code providerMessageId} is only present for ACCEPTED/AMBIGUOUS. */
public record ProviderResult(
        ProviderOutcome outcome,
        String providerMessageId,
        String errorCode,
        String errorMessage,
        long latencyMs) {

    public static ProviderResult accepted(String providerMessageId, long latencyMs) {
        return new ProviderResult(ProviderOutcome.ACCEPTED, providerMessageId, null, null, latencyMs);
    }

    public static ProviderResult failure(ProviderOutcome outcome, String errorCode, String message, long latencyMs) {
        return new ProviderResult(outcome, null, errorCode, message, latencyMs);
    }

    public boolean isAccepted() {
        return outcome == ProviderOutcome.ACCEPTED;
    }
}
