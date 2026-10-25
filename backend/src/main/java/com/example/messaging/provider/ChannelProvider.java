package com.example.messaging.provider;

import com.example.messaging.domain.Channel;
import java.util.Optional;

/**
 * Port to a downstream channel provider. Implementations must never throw for provider-side problems: they return a
 * {@link ProviderResult} with the matching {@link ProviderOutcome}. All sends are idempotent with respect to
 * {@link ProviderRequest#idempotencyRef()} when the provider supports it.
 */
public interface ChannelProvider {

    /** Stable id, matches {@code provider_config.provider_id} and the callback path segment. */
    String providerId();

    Channel channel();

    ProviderResult send(ProviderRequest request);

    /**
     * Reconciliation: asks the provider what it knows about {@code idempotencyRef}. Empty when the provider has no
     * lookup capability (reconciliation then relies on callbacks only).
     */
    Optional<ProviderStatus> lookup(String idempotencyRef);
}
